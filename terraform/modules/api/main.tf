terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.50"
    }
  }
}

locals {
  name = "matchmaker-${var.environment}"

  # Empty means "derive one"; see the domain resource in cognito.tf for why it is built this way.
  # substr of a sha256 rather than the account id itself, so the public hostname does not carry
  # the AWS account number. Deterministic, so the sign-in URL is stable across applies.
  hosted_login_domain = (
    var.hosted_login_domain_prefix != ""
    ? var.hosted_login_domain_prefix
    : "${local.name}-${substr(sha256("${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"), 0, 8)}"
  )

  # rds_endpoint may or may not carry a port; Aurora's endpoint attribute does not, while the
  # console shows one. Accept both rather than making callers normalize it.
  endpoint_parts = split(":", var.rds_endpoint)
  db_host        = local.endpoint_parts[0]
  db_port        = length(local.endpoint_parts) > 1 ? local.endpoint_parts[1] : "5432"
}

# ---------------------------------------------------------------------------
# Execution role
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  name               = "${local.name}-lambda"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Required for a VPC-attached function: without it Lambda cannot create the network interfaces
# it needs and every invocation fails before reaching any code.
resource "aws_iam_role_policy_attachment" "vpc_access" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# ---------------------------------------------------------------------------
# Function
# ---------------------------------------------------------------------------

# Declared rather than left to Lambda's implicit creation, so that retention is enforced and the
# group is destroyed along with everything else.
resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.name}-api"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "api" {
  function_name = "${local.name}-api"
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = "com.vivi.matchmaker.api.Handler::handleRequest"

  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_timeout_s

  /* SnapStart: the JVM is initialized once at publish time and every cold start resumes that
   * snapshot instead of booting a JVM and loading classes again.
   *
   * Two things make this correct rather than merely faster:
   *
   * - It only applies to *published versions*, never $LATEST. That is why `publish` is on and why
   *   the integration below invokes the alias — pointing the gateway at the unqualified function
   *   would silently opt out and leave nothing but the publish cost.
   * - Nothing that must be unique per environment may be captured in the snapshot. The handler's
   *   database pool sits behind a `lazy val` that the Lambda runtime does not touch while
   *   constructing the handler, so the snapshot holds loaded classes and an initialized JVM but no
   *   sockets. Priming the pool during init would restore every execution environment onto the
   *   same dead TCP connections, and would need `org.crac` checkpoint/restore hooks to be safe.
   */
  dynamic "snap_start" {
    for_each = var.lambda_snap_start ? [1] : []
    content {
      apply_on = "PublishedVersions"
    }
  }

  # Each apply publishes a new immutable version, which the alias then moves to. Required by
  # SnapStart, and independently useful: a bad deploy is rolled back by repointing the alias.
  # Unconditional, so that toggling lambda_snap_start does not also rearrange how the gateway
  # reaches the function.
  publish = true

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = {
      DB_HOST      = local.db_host
      DB_PORT      = local.db_port
      DB_NAME      = var.db_name
      DB_USER      = var.db_user
      DB_POOL_SIZE = tostring(var.db_pool_size)

      # In the function's configuration in plaintext, readable by anyone with lambda:GetFunction,
      # and in the terraform state. That is the trade this variable makes; see its description.
      DB_PASSWORD = var.db_password

      # Selects how the caller is identified. "gateway" means the claims the JWT authorizer put
      # in the request context are trusted, which is only sound because the route above cannot be
      # reached without passing that authorizer.
      AUTH_MODE = "gateway"

      # Matchmaker's own base url, which it hands to a game engine when creating a game so the
      # engine knows where to send the callbacks above. Built from the stage rather than written
      # down, so it cannot drift from where the API actually is.
      # Built from the api id rather than read off the stage: the stage's integration points at
      # this function, so taking its invoke_url here would close a dependency cycle.
      MATCHMAKER_BASE_URL = "https://${aws_apigatewayv2_api.api.id}.execute-api.${data.aws_region.current.region}.amazonaws.com"
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_iam_role_policy_attachment.vpc_access,
    aws_cloudwatch_log_group.lambda,
  ]
}

/* The alias everything invokes, always pointing at the version this apply published.
 *
 * SnapStart is the reason it has to exist — a snapshot is taken per published version, and only a
 * qualified invocation can resume one — but it is worth having on its own: the gateway names a
 * stable ARN, and a bad deploy can be rolled back by moving the alias to the previous version
 * without touching the API.
 *
 * Publishing a version with SnapStart on is not instant: AWS runs the init phase and takes the
 * snapshot before the version becomes usable, so expect an apply that changes the jar to sit here
 * for a minute or two.
 */
resource "aws_lambda_alias" "live" {
  name             = "live"
  description      = "Version currently serving the HTTP API."
  function_name    = aws_lambda_function.api.function_name
  function_version = aws_lambda_function.api.version
}

# ---------------------------------------------------------------------------
# HTTP API
# ---------------------------------------------------------------------------

resource "aws_apigatewayv2_api" "api" {
  name          = "${local.name}-api"
  protocol_type = "HTTP"

  # The UI is served from somewhere else — S3, a static host, or a local port during development
  # — so every call it makes is cross-origin and needs this. Origins are listed rather than
  # wildcarded: `*` is incompatible with sending credentials, and there is no reason for an
  # arbitrary page to be able to call this API with a token it somehow obtained.
  #
  # API Gateway answers the OPTIONS preflight itself, before the JWT authorizer runs. That matters
  # because a preflight carries no Authorization header and would otherwise be rejected with 401,
  # which the browser reports only as an opaque CORS failure.
  cors_configuration {
    allow_origins = var.cors_allowed_origins
    allow_methods = ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
    allow_headers = ["authorization", "content-type"]
    max_age       = 3600
  }
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id           = aws_apigatewayv2_api.api.id
  integration_type = "AWS_PROXY"
  # The alias, not the function. An unqualified invoke_arn reaches $LATEST, which has no snapshot
  # and so would quietly cold-start a JVM on every new execution environment.
  integration_uri        = aws_lambda_alias.live.invoke_arn
  payload_format_version = "2.0"
}

# Verifies the Cognito token before the function is invoked: signature, expiry, issuer and
# audience. An unverified request is rejected by the gateway with 401 and never reaches any code,
# which is why `Authenticator.GatewayClaims` reads the `sub` claim without re-checking it.
#
# `audience` is the app client id, which matches the `aud` claim of an *ID* token. Cognito's
# access tokens carry `client_id` instead and would be rejected here, so callers send the ID
# token — see terraform/README.md.
resource "aws_apigatewayv2_authorizer" "cognito" {
  api_id           = aws_apigatewayv2_api.api.id
  name             = "${local.name}-cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.users.id}"
    audience = [aws_cognito_user_pool_client.app.id]
  }
}

/* Every route the application serves, restated here.
 *
 * A single $default route would be less to maintain, and was what this used to be — but $default
 * matches *every* method, OPTIONS included, and API Gateway answers a CORS preflight itself only
 * when no route matches it. So the preflight went to the JWT authorizer, arrived without an
 * Authorization header (a preflight never carries credentials) and came back 401, which a browser
 * reports as nothing more useful than a failed access control check.
 *
 * Listing the routes leaves OPTIONS unmatched, which is precisely what lets the gateway handle
 * preflights: it answers them from cors_configuration above, without the authorizer and without
 * invoking the function. Unknown paths are rejected at the edge for the same reason.
 *
 * The cost is that this list and `Router.scala` are two copies of one table. A route added there
 * and not here returns 404 with nothing in the function's logs, because the request never reaches
 * it. RouterSpec's `routed` list is the third copy; keep all three in step.
 *
 * Path parameter names are arbitrary to the gateway — the function re-parses the path itself — but
 * they match the router's names so the two read the same.
 */
locals {
  routes = [
    "POST /register",

    "GET /me",
    "GET /me/acceptances",
    "GET /me/matches",
    "GET /me/matches/due",
    "GET /me/matches/completed",

    "GET /games",
    "POST /games",
    "GET /games/{gameId}/challenges",
    "GET /games/{gameId}/characters",
    "POST /games/{gameId}/characters",

    "PUT /characters/{characterId}",
    # X-External-Id carries the game's shared secret here, not a player's id — but the route is
    # still behind the authorizer, so a signed-in caller is required either way.
    "PUT /characters/{characterId}/state",

    "POST /challenges",
    "DELETE /challenges/{gameId}/{challengeId}",
    "POST /challenges/{gameId}/{challengeId}/acceptances",
    "DELETE /challenges/{gameId}/{challengeId}/acceptances/{playerId}",
    # Turning a challenge into a match, and the two match routes that go with it. All three are
    # player actions: the challenger starts, and a participant reads or refreshes.
    "POST /challenges/{gameId}/{challengeId}/start",
    "GET /games/{gameId}/matches/{matchId}",
    "POST /games/{gameId}/matches/{matchId}/refresh",
  ]

  /* The game engine's callbacks, which are not player actions at all: a game engine tells
   * matchmaker that a player has moved, or that a match is over.
   *
   * These carry AWS_IAM rather than the Cognito authorizer, so the caller is an AWS principal
   * whose SigV4 signature the gateway verifies — the mirror image of matchmaker signing its own
   * calls to the game API. The engine has no Cognito identity of its own, and giving it one to
   * impersonate would be a password shared between two systems.
   *
   * The function identifies the caller by the role it assumed and matches that against the
   * game's `external_id`, so a game's external_id must be set to the engine's role ARN
   * (arn:aws:iam::<account>:role/<role>) for its callbacks to be accepted. See
   * `Authenticator.GatewayIam`.
   */
  iam_routes = [
    "POST /games/{gameId}/matches/{matchId}/moves",
    "POST /games/{gameId}/matches/{matchId}/results",
  ]
}

# Every route is authenticated: there is deliberately no public one, because even registration
# creates a player *for* an existing Cognito identity. A new route added to the list above is
# therefore authenticated by construction — there is no per-route decision to forget.
resource "aws_apigatewayv2_route" "routes" {
  for_each = toset(local.routes)

  api_id    = aws_apigatewayv2_api.api.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"

  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
}

resource "aws_apigatewayv2_route" "iam_routes" {
  for_each = toset(local.iam_routes)

  api_id    = aws_apigatewayv2_api.api.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"

  # No authorizer_id: AWS_IAM is enforced by the gateway itself, from the request's signature.
  authorization_type = "AWS_IAM"
}

/* Permission to call the callback routes, for a game engine's role to attach.
 *
 * A route being AWS_IAM-authorized establishes *who* is calling; it does not by itself let
 * anyone in. The caller's role also needs execute-api:Invoke on the route. An HTTP API has no
 * resource policy — that is a REST API feature — so the grant can only be identity-based, which
 * means it has to live on the engine's role.
 *
 * The engine's role belongs to whoever runs the engine and is not managed here, so this module
 * publishes the policy and leaves attaching it to them (or set game_engine_role_names, below, if
 * the roles do live in this account). Nothing can call the callbacks until that happens, which is
 * the right default: unnamed is unreachable.
 */
data "aws_iam_policy_document" "engine_callbacks" {
  statement {
    actions = ["execute-api:Invoke"]
    resources = [
      for route in local.iam_routes :
      # execute-api ARNs address a route as <stage>/<METHOD>/<path>; a path parameter is a
      # wildcard there rather than a name in braces, so "{gameId}" becomes "*". The second
      # replace's pattern is slash-wrapped, which is how terraform marks a regex.
      "${aws_apigatewayv2_api.api.execution_arn}/*/${replace("${split(" ", route)[0]}${split(" ", route)[1]}", "/{[^}]*}/", "*")}"
    ]
  }
}

resource "aws_iam_policy" "engine_callbacks" {
  name        = "${local.name}-engine-callbacks"
  description = "Allows a game engine to post move and result callbacks to matchmaker."
  policy      = data.aws_iam_policy_document.engine_callbacks.json
}

resource "aws_iam_role_policy_attachment" "engine_callbacks" {
  for_each = toset(var.game_engine_role_names)

  role       = each.value
  policy_arn = aws_iam_policy.engine_callbacks.arn
}

/* The other direction: matchmaker calling a game engine's API.
 *
 * Same rule, mirrored — the game API's routes are AWS_IAM-authorized, so this function's own role
 * needs execute-api:Invoke on them, and every request it sends is SigV4-signed (see `SigV4` in
 * the api module). Also empty by default: a game whose API has not been named here will have its
 * create-game call rejected by that API rather than by anything in matchmaker.
 */
data "aws_iam_policy_document" "call_game_apis" {
  count = length(var.game_api_execution_arns) > 0 ? 1 : 0

  statement {
    actions   = ["execute-api:Invoke"]
    resources = var.game_api_execution_arns
  }
}

resource "aws_iam_role_policy" "call_game_apis" {
  count = length(var.game_api_execution_arns) > 0 ? 1 : 0

  name   = "${local.name}-call-game-apis"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.call_game_apis[0].json
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${local.name}-api"
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.api.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId        = "$context.requestId"
      httpMethod       = "$context.httpMethod"
      path             = "$context.path"
      status           = "$context.status"
      responseLatency  = "$context.responseLatency"
      integrationError = "$context.integrationErrorMessage"
    })
  }
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowInvocationFromApiGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api.execution_arn}/*/*"

  # Scoped to the alias. A permission on the unqualified function does not authorize invoking a
  # qualified one, so without this every request would come back as 500 with an
  # AccessDeniedException in the gateway's access log and nothing at all in the function's.
  qualifier = aws_lambda_alias.live.name
}
