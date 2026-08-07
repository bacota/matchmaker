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

  # rds_endpoint may or may not carry a port; Aurora's endpoint attribute does not, while the
  # console shows one. Accept both rather than making callers normalize it.
  endpoint_parts = split(":", var.rds_endpoint)
  db_host        = local.endpoint_parts[0]
  db_port        = length(local.endpoint_parts) > 1 ? local.endpoint_parts[1] : "5432"
}

# The secret itself is managed elsewhere: its value is a credential this configuration should
# neither set nor be able to show in a plan. Only its ARN is needed, to scope the grant below.
data "aws_secretsmanager_secret" "db" {
  name = var.db_secret_name
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

data "aws_iam_policy_document" "read_db_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [data.aws_secretsmanager_secret.db.arn]
  }
}

resource "aws_iam_role_policy" "read_db_secret" {
  name   = "${local.name}-read-db-secret"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.read_db_secret.json
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

  # Serves Secrets Manager over localhost, so the function does not have to carry the AWS SDK to
  # read one secret. The grant below is still what authorizes the read.
  layers = [var.secrets_extension_layer_arn]

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = {
      DB_HOST        = local.db_host
      DB_PORT        = local.db_port
      DB_NAME        = var.db_name
      DB_SECRET_NAME = var.db_secret_name
      DB_POOL_SIZE   = tostring(var.db_pool_size)

      # Selects how the caller is identified. "gateway" means the claims the JWT authorizer put
      # in the request context are trusted, which is only sound because the route above cannot be
      # reached without passing that authorizer.
      AUTH_MODE = "gateway"

      PARAMETERS_SECRETS_EXTENSION_HTTP_PORT = tostring(var.secrets_extension_port)
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_iam_role_policy_attachment.vpc_access,
    aws_cloudwatch_log_group.lambda,
  ]
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
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api.invoke_arn
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

# A single catch-all route: the application routes by path itself, so there is nothing to gain
# from restating every path here and keeping the two in step.
#
# The authorizer is attached here rather than per-route, so that a route added to the application
# is authenticated by default. There is deliberately no public route: even registration requires a
# signed-in user, because a player is created *for* a Cognito identity.
resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"

  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
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
}
