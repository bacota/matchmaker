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
