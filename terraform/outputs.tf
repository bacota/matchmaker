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

output "ui_url" {
  description = "Where the deployed UI is served. Already registered as a callback URL and CORS origin."
  value       = module.ui.url
}

output "ui_bucket" {
  value = module.ui.bucket
}

output "ui_distribution_id" {
  description = "For `aws cloudfront create-invalidation` if you need to bypass the cache."
  value       = module.ui.distribution_id
}

output "ui_distribution_domain_name" {
  description = <<-EOT
    The distribution's own *.cloudfront.net name. With a custom domain configured this differs from
    ui_url, and comparing the two is the quickest way to tell a DNS problem from a CloudFront one.
  EOT
  value       = module.ui.distribution_domain_name
}

# Empty unless cognito_sender_email is set; see the admin user in modules/api/cognito.tf.
output "admin_external_id" {
  description = "The admin Cognito user's `sub`. deploy.sh reads this to seed the admin player row."
  value       = module.api.admin_external_id
}

output "admin_email" {
  value = module.api.admin_email
}

# ---------------------------------------------------------------------------
# Tic-tac-toe engine
# ---------------------------------------------------------------------------
#
# Empty strings when deploy_tictactoe is false, rather than absent: `output -raw` on an output
# that does not exist is an error, and deploy-tictactoe.sh reads these to print the game row that
# has to be created by hand.

output "tictactoe_create_game_url" {
  description = "What to record as the game's `url` in matchmaker. Empty when the engine is not deployed."
  value       = one(module.tictactoe[*].create_game_url)
}

output "tictactoe_role_arn" {
  description = <<-EOT
    What to record as the game's `external_id`: the engine's execution role, which is the
    principal API Gateway verifies on its callbacks. Empty when the engine is not deployed.
  EOT
  value       = one(module.tictactoe[*].lambda_role_arn)
}

output "tictactoe_api_endpoint" {
  description = "Base url of the engine's API, whose /matches/<id>/play is where a player plays."
  value       = one(module.tictactoe[*].api_endpoint)
}
