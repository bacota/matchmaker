output "queue_url" {
  description = "The mail queue's URL, which the API function enqueues to."
  value       = aws_sqs_queue.mail.url
}

output "queue_arn" {
  description = "The mail queue's ARN, for the API function's sqs:SendMessage grant."
  value       = aws_sqs_queue.mail.arn
}

output "dead_letter_queue_url" {
  description = "Where mail that could not be sent ends up. Worth looking at when nobody is receiving anything."
  value       = aws_sqs_queue.dead_letter.url
}
