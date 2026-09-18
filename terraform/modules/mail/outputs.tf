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

output "configuration_set_name" {
  description = <<-EOT
    The SES configuration set every send is attributed to, and the reason bounces come back at
    all. Passed to the mailer as MAIL_CONFIG_SET.
  EOT
  value       = aws_sesv2_configuration_set.mail.configuration_set_name
}

output "configuration_set_arn" {
  description = "The configuration set's ARN, for an ses:SendEmail grant that names it."
  value       = aws_sesv2_configuration_set.mail.arn
}

output "bounce_queue_arn" {
  description = <<-EOT
    Where SES's bounce, complaint and delivery-delay events wait. The api module's bounce consumer
    polls this, which is why the arn leaves this module: the consumer needs the database, and so
    needs the VPC this module deliberately stays out of.
  EOT
  value       = aws_sqs_queue.bounce.arn
}

output "bounce_dead_letter_queue_url" {
  description = <<-EOT
    Events that could not be recorded three times. An address in here is one matchmaker is still
    mailing when it should have stopped.
  EOT
  value       = aws_sqs_queue.bounce_dead_letter.url
}
