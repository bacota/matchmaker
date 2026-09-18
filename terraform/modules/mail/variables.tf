variable "environment" {
  description = "Environment name, used to name every resource in this module."
  type        = string
}

variable "lambda_jar_path" {
  description = <<-EOT
    Path to the mailer's assembled jar, from `mill -j 4 --ticker false matchmaker.mailer.assembly`.
    Deployed as the function's code; `deploy-mailer.sh` builds it and applies.
  EOT
  type        = string
}

variable "sender_identity_arn" {
  description = <<-EOT
    ARN of the SES identity mail is sent from. The function may send as this identity and no
    other, which is what keeps a compromised queue message from sending as somebody else. Empty
    disables the grant entirely, which leaves a function that can do nothing -- use it only to
    stand the queue up before the identity exists.
  EOT
  type        = string
  default     = ""
}

variable "lambda_memory_mb" {
  description = "Memory for the mailer. It parses a little JSON and makes one HTTPS call."
  type        = number
  default     = 512
}

variable "lambda_timeout_s" {
  description = <<-EOT
    How long one batch may take. Ten messages at a second or two each, with room for SES being
    slow; the queue's visibility timeout below is derived from this and must stay larger.
  EOT
  type        = number
  default     = 60
}

variable "batch_size" {
  description = <<-EOT
    How many messages Lambda hands the function at once. Ten rather than one so that a busy
    moment is a few invocations rather than dozens, and no larger because a batch is also the
    unit of the timeout above.
  EOT
  type        = number
  default     = 10
}

variable "max_receive_count" {
  description = <<-EOT
    How many times a message is retried before it goes to the dead-letter queue. Three: a
    transient SES failure is nearly always over by the second attempt, and a message that is
    still failing on the third is failing for a reason retrying will not fix.
  EOT
  type        = number
  default     = 3
}

variable "message_retention_seconds" {
  description = <<-EOT
    How long an undelivered message stays on the queue. Four days rather than the maximum
    fourteen: a "your match has started" mail that arrives a week late is worse than one that
    never arrives.
  EOT
  type        = number
  default     = 345600
}

variable "log_retention_days" {
  description = "CloudWatch retention for the mailer's log group."
  type        = number
  default     = 14
}

variable "bounce_timeout_s" {
  description = <<-EOT
    Timeout for the bounce consumer, which lives in the api module but is polled from this
    module's queue -- so the queue's visibility timeout is derived from it here.

    Small: recording a bounce is one upsert per address against a database in the same region.
    The value matters mainly for the first invocation of a cold container, which pays for a JVM
    and a connection pool before it writes anything.
  EOT
  type        = number
  default     = 30
}
