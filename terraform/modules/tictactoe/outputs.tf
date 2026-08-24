output "create_game_url" {
  description = <<-EOT
    What to record as the game's `url` in matchmaker: the endpoint matchmaker POSTs a
    CreateGameRequest to in step 1.
  EOT
  value       = "${aws_apigatewayv2_stage.default.invoke_url}games"
}

output "api_endpoint" {
  description = "Base url of the engine's HTTP API."
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "auth_callback_url" {
  description = <<-EOT
    Where the hosted login must be allowed to redirect back to, so the board page can complete a
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

output "api_host" {
  description = <<-EOT
    The host matchmaker calls this engine on. Matchmaker files an engine's API key by host, since
    the host is all its client knows about the engine it is about to call — pass this as the key
    of an entry in matchmaker's `game_engine_api_keys`.
  EOT
  value       = "${aws_apigatewayv2_api.engine.id}.execute-api.${data.aws_region.current.region}.amazonaws.com"
}

output "lambda_function_name" {
  description = "For `aws logs tail` and manual invocation."
  value       = aws_lambda_function.engine.function_name
}

output "match_table_name" {
  description = "The DynamoDB table holding matches in progress."
  value       = aws_dynamodb_table.matches.name
}
