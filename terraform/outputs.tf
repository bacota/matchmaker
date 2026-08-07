output "api_endpoint" {
  value = module.api.api_endpoint
}

output "lambda_function_name" {
  value = module.api.lambda_function_name
}

output "lambda_role_arn" {
  value = module.api.lambda_role_arn
}

# The three public values the UI's config block needs. None are secret: the client id is a query
# parameter of the authorize URL.
output "user_pool_id" {
  value = module.api.user_pool_id
}

output "user_pool_client_id" {
  value = module.api.user_pool_client_id
}

output "hosted_login_url" {
  value = module.api.hosted_login_url
}

output "jwt_issuer" {
  value = module.api.jwt_issuer
}
