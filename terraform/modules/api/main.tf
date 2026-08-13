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

  # Scoped to the alias. A permission on the unqualified function does not authorize invoking a
  # qualified one, so without this every request would come back as 500 with an
  # AccessDeniedException in the gateway's access log and nothing at all in the function's.
  qualifier = aws_lambda_alias.live.name
}
