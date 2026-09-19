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

/* Putting a mail on the notification queue, and nothing else about SQS.
 *
 * Scoped to the one queue: this function has no business reading it (the mailer does that) and no
 * business touching any other. Absent entirely when there is no queue, so that an environment
 * with notifications off grants nothing rather than granting a permission over an empty string.
 */
data "aws_iam_policy_document" "mail_queue" {
  # Counted on the flag, never on the arn. The arn is an attribute of a queue that does not exist
  # yet on the first apply, and terraform must know how many instances a resource has while
  # planning -- "Invalid count argument: the count value depends on resource attributes that cannot
  # be determined until apply" is what a count on the arn produces. The flag is a plain variable,
  # so it is known before anything is created; the arn is only ever used for the policy's contents,
  # where an unknown value is fine.
  count = var.mail_enabled ? 1 : 0

  statement {
    actions   = ["sqs:SendMessage"]
    resources = [var.mail_queue_arn]
  }
}

resource "aws_iam_role_policy" "mail_queue" {
  count = var.mail_enabled ? 1 : 0

  name   = "${local.name}-mail-queue"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.mail_queue[0].json
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
   * - Nothing that must be unique per execution environment may be captured in the snapshot. The
   *   handler's database pool sits behind a `lazy val` that the Lambda runtime does not touch
   *   while constructing the handler, so the snapshot holds loaded classes and an initialized JVM
   *   but no sockets. Priming the pool during init would restore every execution environment onto
   *   the same dead TCP connections, and would need `org.crac` checkpoint/restore hooks to be
   *   safe.
   *
   * The second condition is what turned this off once, and is now the thing to watch. The
   * execution role's credentials are per-execution-environment and arrive as environment
   * variables, and Java fixes System.getenv at JVM start -- so a restored function signed its
   * calls to the game engine with nothing and was answered with 403. Engine calls no longer sign:
   * they take a shared API key, set on this function like any other variable and therefore present
   * in the snapshot.
   *
   * The one call that came back -- putting a notification on the mail queue -- is why there is an
   * AWS SDK client in this codebase at all: its credential provider survives a restore, where a
   * hand-signed request reading a frozen environment does not. See notify.SqsNotifier.
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

      # Notifications. All three are empty unless deploy_mail is on, and the function checks for
      # all three: no queue, no sender or no link each mean it sends nothing rather than sending
      # something broken. See com.vivi.matchmaker.notify.MailSettings.
      MAIL_QUEUE_URL = var.mail_queue_url
      MAIL_SENDER    = var.mail_sender
      UI_BASE_URL    = var.ui_base_url

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

      # The shared secrets, one per game engine, in the two forms the function looks them up by:
      # by external_id for a callback arriving (whose key says which engine sent it), and by host
      # for a call going out (where a url is all there is to go on). Both are `name=key` lists.
      ENGINE_API_KEYS      = join(",", [for name, key in var.engine_api_keys : "${name}=${key}"])
      GAME_ENGINE_API_KEYS = join(",", [for host, key in var.game_engine_api_keys : "${host}=${key}"])
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
    # Renaming yourself. The password is changed at Cognito, not here.
    "PUT /me",
    # Recording an email change that Cognito has already confirmed, so matchmaker knows where to
    # write to. The change itself still happens at Cognito; this only reports it.
    "PUT /me/email",
    # What the caller wants to be told about: their settings everywhere, and their settings for one
    # game. The per-match level is on the match's own route below.
    "GET /me/notifications",
    "PUT /me/notifications",
    # Try my address again, after a bounce. See the comment in Router.scala: the row is released
    # rather than deleted, so this is a POST and not a DELETE.
    "POST /me/notifications/retry",
    "PUT /me/notifications/games/{gameId}",
    "GET /me/acceptances",
    # What the caller has been asked to play, across every game — the invitations half of the list
    # above, and the one invitation route that is not about a challenge the caller already holds.
    "GET /me/invitations",
    "GET /me/matches",
    "GET /me/matches/due",
    "GET /me/matches/completed",
    # How the caller's finished matches ended: every seat of every one of them, in one call.
    "GET /me/results",

    # Finding another player by a prefix of their nickname, and the two lists their page shows:
    # the matches they marked public, running and finished. The prefix is a query parameter, so it
    # needs no route of its own beyond "GET /players".
    "GET /players",
    "GET /players/{playerId}/matches",
    "GET /players/{playerId}/matches/completed",

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
    # Asking particular players to a challenge, and then removing an invitation (V22). The POST is
    # the challenger's alone; DELETE lets the invitee reject their own invitation or the challenger
    # revoke it. The create route above carries the invitations a challenge starts with.
    "POST /challenges/{gameId}/{challengeId}/invitations",
    "DELETE /challenges/{gameId}/{challengeId}/invitations/{playerId}",
    # Turning a challenge into a match, and the two match routes that go with it. All three are
    # player actions: the challenger starts, and a participant reads or refreshes.
    "POST /challenges/{gameId}/{challengeId}/start",
    "GET /games/{gameId}/matches/{matchId}",
    "POST /games/{gameId}/matches/{matchId}/refresh",
    # Calling a match off, which only its creator may do.
    "POST /games/{gameId}/matches/{matchId}/cancel",
    # Muting one match: the most specific thing a player can say about notifications.
    "GET /games/{gameId}/matches/{matchId}/notifications",
    "PUT /games/{gameId}/matches/{matchId}/notifications",
  ]

  /* The game engine's callbacks, which are not player actions at all: a game engine tells
   * matchmaker that a player has moved, or that a match is over.
   *
   * These carry no authorizer. The engine authenticates with the API key it and matchmaker
   * share, which the function checks itself (see `Authenticator.ApiKey`) — an HTTP API has no
   * built-in API key support, that being a REST API feature. The engine has no Cognito identity
   * of its own, and giving it one to impersonate would be a password shared between two systems
   * with a great deal more reach than a key scoped to two routes.
   *
   * Matchmaker holds a different key per engine, so the key also says *which* engine is calling:
   * it is filed under the name that must be the game's `external_id`. See `engine_api_keys`.
   */
  engine_routes = [
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

resource "aws_apigatewayv2_route" "engine_routes" {
  for_each = toset(local.engine_routes)

  api_id    = aws_apigatewayv2_api.api.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"

  # NONE at the gateway, and refused in the function when the key is missing or unknown. Weaker
  # than the AWS_IAM authorization it replaces in one specific way — an unauthenticated call now
  # reaches the function before it is refused — and in exchange an engine needs no AWS identity,
  # which is what lets one run somewhere other than this account.
  authorization_type = "NONE"
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

# ---------------------------------------------------------------------------
# The bounce consumer
# ---------------------------------------------------------------------------

/* A second function from the same jar, recording what SES says happened to the mail we sent.
 *
 * In this module rather than in the mail module, which is the one decision worth explaining. The
 * mail module is deliberately outside the VPC: it has nothing in there to reach, so it needs
 * neither a NAT gateway nor an interface endpoint, and its own comment says so. This function
 * writes to the database, so it has to be inside -- which means it belongs beside the other
 * function that is, sharing this module's subnets, security groups and database configuration.
 *
 * The queue it drains is still the mail module's, because that module is what produces the events.
 * Only the arn crosses over.
 *
 * Counted on mail_enabled, not on the arn: an unknown-until-apply value cannot decide how many
 * instances a resource has. Same reasoning as the mail_queue policy above, and the same failure if
 * it were otherwise.
 */
resource "aws_cloudwatch_log_group" "bounce" {
  count = var.mail_enabled ? 1 : 0

  name              = "/aws/lambda/${local.name}-bounce"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "bounce_queue" {
  count = var.mail_enabled ? 1 : 0

  # Reading the bounce queue. The event source mapping polls as this role, so these three are what
  # make the trigger work at all rather than what the function's own code calls -- exactly as the
  # mailer's policy does for the mail queue.
  statement {
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [var.bounce_queue_arn]
  }
}

resource "aws_iam_role_policy" "bounce_queue" {
  count = var.mail_enabled ? 1 : 0

  name   = "${local.name}-bounce-queue"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.bounce_queue[0].json
}

resource "aws_lambda_function" "bounce" {
  count = var.mail_enabled ? 1 : 0

  function_name = "${local.name}-bounce"
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = "com.vivi.matchmaker.bounce.Handler::handleRequest"

  # The same jar as the api function, and so the same source_code_hash: one build, two handlers.
  # A deploy that replaced one and not the other would be two versions of the model reading the
  # same tables.
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  timeout     = var.bounce_timeout_s

  /* The same execution role as the api function.
   *
   * A role of its own would be tighter -- this one can send mail and call the engines, neither of
   * which it does. It shares anyway because the expensive permission is the database, which is not
   * an IAM permission at all: the password is in the environment, so a separate role would confer
   * no separation over the one thing worth separating, while doubling the number of places the
   * VPC and log-group attachments have to be got right.
   */

  /* No SnapStart, no publish and no alias, unlike the api function.
   *
   * Nothing invokes this by name: the event source mapping below points at the unqualified
   * function, so $LATEST is what runs. An alias would be a second thing every deploy has to
   * remember to move, and the cold start it would accelerate is one a queue absorbs -- nobody is
   * waiting on a bounce. Rolling this one back is redeploying the previous jar.
   */

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
      DB_PASSWORD  = var.db_password
      DB_POOL_SIZE = tostring(var.bounce_db_pool_size)
    }
  }

  # Six variables, where the api function has a dozen. Nothing else is set because nothing else is
  # read: this function has no gateway in front of it to authenticate a caller for, no engines to
  # call, and no mail to send. AUTH_MODE in particular is absent on purpose -- there is no request
  # and so nobody to identify, and the handler never builds an Authenticator.

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_iam_role_policy_attachment.vpc_access,
    aws_cloudwatch_log_group.bounce,
  ]
}

/* The poller. Lambda long-polls the bounce queue as the role above and invokes with a batch.
 *
 * `ReportBatchItemFailures`, as the mailer has: the handler answers with the ids it could not
 * record, so one unwritable event costs one redelivery rather than redelivering nine that were
 * already written -- which, for an upsert that counts occurrences, would count them twice and
 * bring a threshold forward.
 */
resource "aws_lambda_event_source_mapping" "bounce" {
  count = var.mail_enabled ? 1 : 0

  event_source_arn = var.bounce_queue_arn
  function_name    = aws_lambda_function.bounce[0].arn

  batch_size                         = 10
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_batching_window_in_seconds = 20

  /* A ceiling on how many of these may run at once, which is about the database and not about
   * Lambda.
   *
   * Without it an event source mapping scales out on queue depth alone: five instances after a
   * minute, then more, up to the account's concurrency limit. Every one of them is a container in
   * the VPC with a pool of up to bounce_db_pool_size connections to the same RDS instance the api
   * function uses -- so a burst of SES feedback, which is exactly what a bad send looks like, would
   * spend the database's connections on recording bounces while players' requests wait for one.
   * The ordering is the wrong way round: nobody is waiting on a bounce, and a player is waiting on
   * every API call.
   *
   * So the consumer is deliberately slow and bounded. The arithmetic is
   * bounce_max_concurrency * bounce_db_pool_size connections at worst -- 4 by default -- against a
   * database this module does not create and cannot ask about, which is why the numbers are small
   * and explicit rather than derived. Depth is absorbed by the queue, whose fourteen-day retention
   * is there for precisely this: an event recorded a few minutes late is worth the same as one
   * recorded at once.
   */
  scaling_config {
    maximum_concurrency = var.bounce_max_concurrency
  }
}
