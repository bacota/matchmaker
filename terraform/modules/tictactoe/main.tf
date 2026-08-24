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
  name = "tictactoe-${var.environment}"

  # The engine's own base url, built from the api id rather than taken from the stage.
  #
  # The stage depends on the integration, which depends on the function, so a function whose
  # environment referenced the stage's invoke_url would close a cycle. The api's id is settled
  # before any of that, and a $default stage adds no path, so this is the same string the stage
  # would report.
  base_url = "https://${aws_apigatewayv2_api.engine.id}.execute-api.${data.aws_region.current.region}.amazonaws.com"

  # Matchmaker's calls in. These are the only routes it makes, and the only ones that require
  # the shared API key.
  matchmaker_routes = [
    "POST /games",
    "GET /matches/{matchId}/status",
  ]

  # A player's own routes: the board they see and the moves they make. Behind the same Cognito
  # user pool matchmaker signs its players in with, because the seat a player may move in is
  # found by the `sub` of their token.
  player_routes = [
    "GET /matches/{matchId}/state",
    "POST /matches/{matchId}/moves",
  ]

  # Served to anyone. The play page carries no game state for a caller with no seat — it is the
  # shell that starts the sign-in — and the callback page redeems the code the hosted login comes
  # back with. Neither can require a token: a browser navigation cannot carry an Authorization
  # header, so an authorizer here would make the board unreachable rather than protected.
  open_routes = [
    "GET /matches/{matchId}/play",
    "GET /matches/{matchId}/board",
    "GET /matches/{matchId}/board/state",
    "GET /auth/callback",
    "GET /health",
  ]

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
 * matches; a provisioned table would be paying for an idle board. `version` is not a key — it is
 * the attribute the engine's conditional write compares, so two players moving at once cannot
 * both write over the other (see DynamoDbMatchStore).
 */
resource "aws_dynamodb_table" "matches" {
  name         = "${local.name}-matches"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "matchId"

  attribute {
    name = "matchId"
    type = "S"
  }

  # A finished match is worth keeping only as long as someone might reload the board. The engine
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
 * never queries or scans. */
data "aws_iam_policy_document" "matches" {
  statement {
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [aws_dynamodb_table.matches.arn]
  }
}

resource "aws_iam_role_policy" "matches" {
  name   = "${local.name}-matches"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.matches.json
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
  handler       = "com.vivi.tictactoe.Handler::handleRequest"

  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_timeout_s

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
      # and result callbacks, where it is also what tells matchmaker which engine is calling.
      #
      # The function refuses to start without it when it is running in Lambda, so an empty value
      # here is a failed cold start rather than an engine that serves game creation to anyone.
      MATCHMAKER_API_KEY = var.matchmaker_api_key

      # The sign-in the board page offers, and the pool whose claims the authorizer below
      # verifies. The same three values matchmaker's own UI is configured with.
      COGNITO_ISSUER    = var.cognito_issuer
      COGNITO_CLIENT_ID = var.cognito_client_id
      HOSTED_LOGIN_URL  = var.hosted_login_url
    }
  }

  depends_on = [aws_cloudwatch_log_group.lambda]
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
  integration_uri        = aws_lambda_function.engine.invoke_arn
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
}
