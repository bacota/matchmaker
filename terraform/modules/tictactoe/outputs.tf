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
  description = <<-EOT
    The engine's execution role. Two things need it: matchmaker's `game_engine_role_arns`, so the
    callbacks are granted, and the game's `external_id` column, which is what matchmaker compares
    the callback's verified principal against (see Authenticator.GatewayIam).
  EOT
  value       = aws_iam_role.lambda.arn
}

output "api_execution_arns" {
  description = <<-EOT
    The two routes matchmaker calls, as execute-api ARNs — pass these to matchmaker's
    `game_api_execution_arns` so its own role may invoke them.
  EOT
  value = [
    "${aws_apigatewayv2_api.engine.execution_arn}/*/POST/games",
    "${aws_apigatewayv2_api.engine.execution_arn}/*/GET/matches/*/status",
  ]
}

output "invoke_policy_arn" {
  description = "Policy allowing the two matchmaker-facing routes, for a caller in another account to attach."
  value       = aws_iam_policy.invoke.arn
}

output "lambda_function_name" {
  description = "For `aws logs tail` and manual invocation."
  value       = aws_lambda_function.engine.function_name
}

output "match_table_name" {
  description = "The DynamoDB table holding matches in progress."
  value       = aws_dynamodb_table.matches.name
}
