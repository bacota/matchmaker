terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
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
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api.invoke_arn
  payload_format_version = "2.0"
}

# A single catch-all route: the application routes by path itself, so there is nothing to gain
# from restating every path here and keeping the two in step.
resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"
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
