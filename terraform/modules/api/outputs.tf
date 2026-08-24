output "api_endpoint" {
  description = "Base URL of the HTTP API."
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "lambda_function_name" {
  description = "Name of the API Lambda, for `aws logs tail` and manual invocation."
  value       = aws_lambda_function.api.function_name
}

output "lambda_role_arn" {
  description = "ARN of the Lambda execution role, for granting it further access."
  value       = aws_iam_role.lambda.arn
}

output "lambda_security_group_ids" {
  description = "Security groups attached to the Lambda, to reference from the database's ingress rules."
  value       = var.security_group_ids
}

# ---------------------------------------------------------------------------
# Cognito
# ---------------------------------------------------------------------------
#
# None of these are secret: the client id and the pool's endpoints appear in the browser on every
# sign-in. They are outputs so a UI can be configured from them rather than by transcription.

output "user_pool_id" {
  description = "Id of this environment's user pool."
  value       = aws_cognito_user_pool.users.id
}

output "user_pool_client_id" {
  description = "App client id. Public: it is a query parameter of the authorize URL."
  value       = aws_cognito_user_pool_client.app.id
}

output "hosted_login_url" {
  description = "Base URL of the hosted UI. Sign-in starts at ${"$"}{hosted_login_url}/login."
  value       = "https://${aws_cognito_user_pool_domain.hosted_login.domain}.auth.${data.aws_region.current.region}.amazoncognito.com"
}

output "jwt_issuer" {
  description = "Token issuer, whose /.well-known/jwks.json serves the public keys. Used by the local server to verify tokens itself."
  value       = "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.users.id}"
}

# ---------------------------------------------------------------------------
# Admin user
# ---------------------------------------------------------------------------
#
# Both empty when cognito_sender_email is unset, in which case no admin user was created.

output "admin_external_id" {
  description = <<-EOT
    The admin user's `sub`, which is the external_id of its player row. deploy.sh passes this to
    the admin seeder; a player row carrying any other value is not this account.
  EOT
  value       = one(aws_cognito_user.admin[*].sub)
}

output "admin_email" {
  description = "Address the admin user signs in with, and where its invitation was sent."
  value       = one(aws_cognito_user.admin[*].username)
}
