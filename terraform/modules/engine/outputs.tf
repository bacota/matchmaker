output "create_game_url" {
  description = <<-EOT
    What to record as the game's `url` in matchmaker: the endpoint matchmaker POSTs a
    CreateGameRequest to in step 1.
  EOT
  value       = "${local.base_url}/games"
}

output "api_endpoint" {
  description = "Base url of the engine's HTTP API: its friendly url when it has one."
  value       = "${local.base_url}/"
}

output "execute_api_endpoint" {
  description = "The engine's API Gateway host, which goes on answering whether or not it has a friendly url."
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "auth_callback_url" {
  description = <<-EOT
    Where the hosted login must be allowed to redirect back to, so the play page can complete a
    sign-in: add it to the user pool client's callback urls (matchmaker's `callback_urls`).

    A fixed path rather than a per-match one, because Cognito matches callback urls exactly and
    cannot be given a pattern.
  EOT
  value       = "${local.base_url}/auth/callback"
}

output "lambda_role_arn" {
  description = "The engine's execution role. Nothing outside this module needs it any more; kept for `aws` CLI work."
  value       = aws_iam_role.lambda.arn
}

output "lambda_function_name" {
  description = "For `aws logs tail` and manual invocation."
  value       = aws_lambda_function.engine.function_name
}

output "match_table_name" {
  description = "The DynamoDB table holding matches in progress."
  value       = aws_dynamodb_table.matches.name
}

output "live_url" {
  description = "Where a play page opens its Play Live connection. The engine is told it as LIVE_URL; nothing else needs it."
  value       = local.live_url
}

output "connection_table_name" {
  description = "The DynamoDB table of open Play Live connections."
  value       = aws_dynamodb_table.connections.name
}
