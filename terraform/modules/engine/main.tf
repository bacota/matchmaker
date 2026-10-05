terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.50"
    }
  }
}

/* A game engine: its function, its match table, and the API matchmaker and its players reach it
 * through. Every bundled engine is one of these, told apart by its name, its handler and any
 * player route only it has.
 */

locals {
  name = "${var.name}-${var.environment}"

  # The engine's own base url: its friendly name when it has one (domain.tf), and otherwise built
  # from the api id rather than taken from the stage.
  #
  # The stage depends on the integration, which depends on the function, so a function whose
  # environment referenced the stage's invoke_url would close a cycle. The api's id is settled
  # before any of that, and a $default stage adds no path, so this is the same string the stage
  # would report. The friendly name is a variable, and closes nothing.
  execute_api_url = "https://${aws_apigatewayv2_api.engine.id}.execute-api.${data.aws_region.current.region}.amazonaws.com"
  base_url        = var.domain_name != "" ? "https://${var.domain_name}" : local.execute_api_url

  # Play Live's WebSocket API, on a named stage — a WebSocket API has no $default stage to hide
  # behind. Built from the api's id for the same reason as the base url above: the function's
  # environment names it, and the stage depends on the function.
  live_stage    = "live"
  live_host     = "${aws_apigatewayv2_api.live.id}.execute-api.${data.aws_region.current.region}.amazonaws.com"
  live_url      = "wss://${local.live_host}/${local.live_stage}"
  live_endpoint = "https://${local.live_host}/${local.live_stage}"

  # Matchmaker's calls in. These are the only routes it makes, and the only ones that require
  # the shared API key.
  matchmaker_routes = [
    "POST /games",
    "GET /matches/{matchId}/status",
    # A cancelled match, which the engine drops (GameEngine.cancel). Matchmaker posts here at the
    # cancelUrl the create answered with.
    "POST /matches/{matchId}/cancel",
  ]

  # A player's own routes: the state they see and the moves they make, and any the game adds — a
  # fighter is built through one. Behind the same Cognito user pool matchmaker signs its players in
  # with, because the seat a player may move in is found by the `sub` of their token — and in a
  # game where both move at once a seat is also the right to see a move the other cannot, since
  # the state route withholds the opponent's until it is answered.
  player_routes = concat([
    "GET /matches/{matchId}/state",
    "POST /matches/{matchId}/moves",
  ], var.extra_player_routes)

  # Served to anyone. The play page carries no game state for a caller with no seat — it is the
  # shell that starts the sign-in — and the callback page redeems the code the hosted login comes
  # back with. Neither can require a token: a browser navigation cannot carry an Authorization
  # header, so an authorizer here would make the page unreachable rather than protected.
  #
  # The public board is open in the same way, and discloses no more than a watcher may know: that
  # a player has moved, never what, while the move is still to be answered.
  open_routes = concat([
    "GET /matches/{matchId}/play",
    "GET /matches/{matchId}/board",
    "GET /matches/{matchId}/board/state",
    "GET /auth/callback",
    "GET /health",
  ], var.extra_open_routes)

  # Player routes need somewhere to verify tokens against. Without a pool the module still
  # applies — useful for an engine driven only by tests — and those routes are simply absent
  # rather than open, which is the failure worth having.
  has_pool = var.cognito_issuer != "" && var.cognito_client_id != ""
}

data "aws_region" "current" {}



# ---------------------------------------------------------------------------
# Matches
# ---------------------------------------------------------------------------

/* Where a match lives between invocations.
 *
 * On-demand billing because the load is a handful of writes per match and nothing between
 * matches; a provisioned table would be paying for an idle match. `version` is not a key — it is
 * the attribute the engine's conditional write compares, so two players moving at once cannot
 * both write over the other (see DynamoDbMatchStore).
 *
 * That compare-and-set matters most in a game where both players are on the clock at once — rock-
 * paper-scissors, a boxing round — where two moves arriving together is the ordinary case rather
 * than a rare race, and a lost one would be a move a player believes they made.
 */
resource "aws_dynamodb_table" "matches" {
  name         = "${local.name}-matches"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "matchId"

  attribute {
    name = "matchId"
    type = "S"
  }

  # A finished match is worth keeping only as long as someone might reload the page. The engine
  # does not write this attribute, so nothing expires until it does — the setting is here so that
  # turning it on is a one-line change rather than a schema decision.
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }
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

/* The table, and nothing else in it: the engine reads and writes one item per match by key and
 * never queries or scans. It deletes one once the match is archived (ArchivingMatchStore) or
 * cancelled; without DeleteItem every finished match would stay here for good. */
data "aws_iam_policy_document" "matches" {
  statement {
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.matches.arn]
  }
}

resource "aws_iam_role_policy" "matches" {
  name   = "${local.name}-matches"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.matches.json
}

/* Play Live: the connections table, by connection and by match, and pushing down this stage's
 * connections — no other api's, and no other stage's. */
data "aws_iam_policy_document" "live" {
  statement {
    actions   = ["dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.connections.arn]
  }

  statement {
    actions   = ["dynamodb:Query"]
    resources = ["${aws_dynamodb_table.connections.arn}/index/byMatch"]
  }

  statement {
    actions   = ["execute-api:ManageConnections"]
    resources = ["${aws_apigatewayv2_api.live.execution_arn}/${local.live_stage}/POST/@connections/*"]
  }
}

resource "aws_iam_role_policy" "live" {
  name   = "${local.name}-live"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.live.json
}

# ---------------------------------------------------------------------------
# Function
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "engine" {
  function_name = local.name
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = var.handler

  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_timeout_s

  /* SnapStart, as on matchmaker's api function and with the same two conditions.
   *
   * - It applies to published versions only, so `publish` is on and both gateways invoke the
   *   alias below rather than the function: an unqualified invoke reaches $LATEST, which has no
   *   snapshot.
   * - Nothing per-execution-environment may be captured. Each `Handler` builds its routes behind a
   *   lazy val, after any restore, and the DynamoDB signer asks the SDK's credential provider at
   *   every signature rather than reading the execution role's keys from the environment once --
   *   see `AwsCredentials.provider` in engines/common, and the api function's comment for what
   *   happened when matchmaker did the latter.
   */
  dynamic "snap_start" {
    for_each = var.lambda_snap_start ? [1] : []
    content {
      apply_on = "PublishedVersions"
    }
  }

  # Unconditional, as on the api function, so that toggling lambda_snap_start does not also
  # rearrange how the gateways reach the function.
  publish = true

  # Not in a VPC: the engine reaches DynamoDB and matchmaker's public API, both over the
  # internet. Attaching it to one would add ENI setup to every cold start for nothing.

  environment {
    variables = {
      BASE_URL    = local.base_url
      MATCH_TABLE = aws_dynamodb_table.matches.name

      # Only used when matchmaker is running in header-auth mode, which a deployed one is not:
      # there the callbacks are signed and matchmaker identifies this engine by the role ARN
      # below. Set it in a dev environment that points at a local matchmaker.
      GAME_EXTERNAL_ID = var.game_external_id

      # The secret this engine and matchmaker authenticate each other with, in both directions:
      # matchmaker presents it on the two routes above, and this engine presents it on its move
      # and result callbacks (and, in a character game, on the write that keeps a character's
      # state), where it is also what tells matchmaker which engine is calling.
      #
      # The function refuses to start without it when it is running in Lambda, so an empty value
      # here is a failed cold start rather than an engine that serves game creation to anyone.
      MATCHMAKER_API_KEY = var.matchmaker_api_key

      # Where a character game reports a character a player has built here. Empty for a game
      # without characters, which has nothing to report.
      MATCHMAKER_URL = var.matchmaker_url

      # The sign-in the play page offers, and the pool whose claims the authorizer below
      # verifies. The same three values matchmaker's own UI is configured with.
      COGNITO_ISSUER    = var.cognito_issuer
      COGNITO_CLIENT_ID = var.cognito_client_id
      HOSTED_LOGIN_URL  = var.hosted_login_url

      # Play Live: where the page connects, where the engine pushes, and where it remembers who
      # is connected. LIVE_URL is what puts the switch on the play page.
      LIVE_URL      = local.live_url
      LIVE_ENDPOINT = local.live_endpoint
      LIVE_TABLE    = aws_dynamodb_table.connections.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.lambda]
}

/* The alias both gateways invoke, always pointing at the version this apply published.
 *
 * Named "current" rather than "live", which is what the api function's alias is called, because
 * "live" already means Play Live in this module.
 *
 * Publishing with SnapStart on is not instant -- AWS runs the init phase and snapshots it before
 * the version is usable -- so an apply that changes the jar waits here for a minute or two.
 */
resource "aws_lambda_alias" "current" {
  name             = "current"
  description      = "Version currently serving the engine's HTTP and Play Live APIs."
  function_name    = aws_lambda_function.engine.function_name
  function_version = aws_lambda_function.engine.version
}

# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------

resource "aws_apigatewayv2_api" "engine" {
  name          = local.name
  protocol_type = "HTTP"

  # The play page is opened in a browser and fetches its own state from the same origin, so no
  # cross-origin access is needed. Matchmaker's UI links to the url; it does not read it.
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id                 = aws_apigatewayv2_api.engine.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_alias.current.invoke_arn
  payload_format_version = "2.0"
}

/* Matchmaker's two routes, authorized by the API key the pair shares.
 *
 * NONE at the gateway, and checked in the function instead (see `Routes.fromMatchmaker`). An
 * HTTP API has no built-in API key support — that is a REST API feature — so there is nothing
 * here to configure; what makes these routes matchmaker's is that the function refuses a request
 * that does not carry the key in MATCHMAKER_API_KEY.
 *
 * This is weaker than the AWS_IAM authorization it replaces, in one specific way: an unauthorized
 * call now reaches the function before it is refused, where the gateway used to reject it for
 * free. What it buys is an engine that needs no AWS identity of its own, which is the point —
 * an engine is a separate system and need not be running in this account, or on AWS at all.
 */
resource "aws_apigatewayv2_route" "matchmaker" {
  for_each = toset(local.matchmaker_routes)

  api_id             = aws_apigatewayv2_api.engine.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.lambda.id}"
  authorization_type = "NONE"
}

/* Verifies the player's Cognito token before the function is invoked — signature, expiry, issuer
 * and audience — exactly as matchmaker's own authorizer does, against the same pool.
 *
 * `audience` is the app client id, which matches the `aud` claim of an *ID* token; Cognito's
 * access tokens carry `client_id` instead and are rejected here. The engine refuses them a second
 * time on the `token_use` claim, which is what keeps the local server (where there is no
 * authorizer) from being the weaker of the two.
 */
resource "aws_apigatewayv2_authorizer" "cognito" {
  count = local.has_pool ? 1 : 0

  api_id           = aws_apigatewayv2_api.engine.id
  name             = "${local.name}-cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = var.cognito_issuer
    audience = [var.cognito_client_id]
  }
}

resource "aws_apigatewayv2_route" "player" {
  for_each = local.has_pool ? toset(local.player_routes) : toset([])

  api_id             = aws_apigatewayv2_api.engine.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.lambda.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito[0].id
}

resource "aws_apigatewayv2_route" "open" {
  for_each = toset(local.open_routes)

  api_id             = aws_apigatewayv2_api.engine.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.lambda.id}"
  authorization_type = "NONE"
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.engine.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId      = "$context.requestId"
      httpMethod     = "$context.httpMethod"
      path           = "$context.path"
      status         = "$context.status"
      responseLength = "$context.responseLength"
      errorMessage   = "$context.error.message"
    })
  }
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowExecutionFromAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.engine.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.engine.execution_arn}/*/*"

  # Scoped to the alias the integration invokes. A permission on the unqualified function does
  # not authorize a qualified invoke, and every request would come back 500.
  qualifier = aws_lambda_alias.current.name
}

# ---------------------------------------------------------------------------
# Play Live
# ---------------------------------------------------------------------------

/* Who is watching which match, for the play pages a player has switched Play Live on in.
 *
 * Keyed by connection, since that is what a disconnect names, with an index by match, since that
 * is what a move names. The index is eventually consistent, which the page allows for — see
 * DynamoDbSubscriptions. `expiresAt` cleans up after a disconnect that never arrived: API Gateway
 * closes every connection within two hours, and the engine sets three.
 */
resource "aws_dynamodb_table" "connections" {
  name         = "${local.name}-connections"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "connectionId"

  attribute {
    name = "connectionId"
    type = "S"
  }

  attribute {
    name = "matchId"
    type = "S"
  }

  # The index's key as a key_schema block: the provider deprecated `hash_key` inside an index (not on
  # the table) in favour of it. The same key, so the index itself is unchanged.
  global_secondary_index {
    name            = "byMatch"
    projection_type = "KEYS_ONLY"

    key_schema {
      attribute_name = "matchId"
      key_type       = "HASH"
    }
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
}

/* The WebSocket API a Play Live page connects to.
 *
 * Nothing is sent down it but "this match changed"; the page answers by fetching its state from
 * the HTTP API above, through the JWT authorizer, so what a player may see is decided there and
 * only there.
 *
 * A WebSocket API has no JWT authorizer, so $connect is open at the gateway and the function
 * verifies the token the page sends with it — the same checks against the same pool. A refused
 * connect is a refused connection.
 */
resource "aws_apigatewayv2_api" "live" {
  name                       = "${local.name}-live"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

resource "aws_apigatewayv2_integration" "live" {
  api_id             = aws_apigatewayv2_api.live.id
  integration_type   = "AWS_PROXY"
  integration_method = "POST"
  integration_uri    = aws_lambda_alias.current.invoke_arn
}

resource "aws_apigatewayv2_route" "live" {
  for_each = toset(["$connect", "$disconnect"])

  api_id             = aws_apigatewayv2_api.live.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.live.id}"
  authorization_type = "NONE"
}

/* The page's keep-alive, `{"action":"ping"}` every five minutes, which keeps a quiet match's
 * connection inside the gateway's ten-minute idle limit. Answered by the gateway itself: a ping is
 * nothing for the function to do, and invoking it for one would cost more than the message. */
resource "aws_apigatewayv2_integration" "ping" {
  api_id                        = aws_apigatewayv2_api.live.id
  integration_type              = "MOCK"
  template_selection_expression = "\\$default"
  request_templates = {
    "$default" = jsonencode({ statusCode = 200 })
  }
}

resource "aws_apigatewayv2_route" "ping" {
  api_id    = aws_apigatewayv2_api.live.id
  route_key = "ping"
  target    = "integrations/${aws_apigatewayv2_integration.ping.id}"
}

resource "aws_apigatewayv2_stage" "live" {
  api_id      = aws_apigatewayv2_api.live.id
  name        = local.live_stage
  auto_deploy = true

  # Every message is billed, so a client that floods one is capped rather than paid for.
  default_route_settings {
    throttling_burst_limit = 100
    throttling_rate_limit  = 50
  }
}

resource "aws_lambda_permission" "live" {
  statement_id  = "AllowExecutionFromLiveApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.engine.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.live.execution_arn}/*"

  # As above: the Play Live integration invokes the alias too.
  qualifier = aws_lambda_alias.current.name
}
