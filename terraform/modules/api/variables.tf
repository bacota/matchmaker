variable "environment" {
  description = "Environment name, used to prefix every resource so environments can share an account."
  type        = string
}

variable "rds_endpoint" {
  description = "Database endpoint, either \"host\" or \"host:port\"."
  type        = string
}

variable "db_name" {
  description = "Name of the database to connect to."
  type        = string
}

variable "db_secret_name" {
  description = <<-EOT
    Name of an existing Secrets Manager secret holding the database credentials, in the standard
    RDS shape: {"username": ..., "password": ...}. This module reads the secret's ARN to scope the
    Lambda's permissions; it never creates the secret or reads its value.
  EOT
  type        = string
}

variable "secrets_extension_layer_arn" {
  description = <<-EOT
    ARN of the AWS Parameters and Secrets Lambda Extension layer, which serves Secrets Manager to
    the function over localhost. The function reads its database credentials through it instead of
    bundling the AWS SDK.

    The ARN is region- and version-specific and AWS publishes no wildcard for it, so it has to be
    given explicitly. Look up the current one for your region under "Parameters and Secrets Lambda
    extension" in the AWS Secrets Manager User Guide. It looks like:
    arn:aws:lambda:us-east-1:177933569100:layer:AWS-Parameters-and-Secrets-Lambda-Extension:17
  EOT
  type        = string
}

variable "secrets_extension_port" {
  description = "Port the secrets extension listens on inside the sandbox."
  type        = number
  default     = 2773
}

variable "subnet_ids" {
  description = "Private subnets the Lambda is attached to. Must be able to reach the database."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups for the Lambda's network interfaces. The database must accept traffic from these."
  type        = list(string)
}

variable "lambda_jar_path" {
  description = "Path to the assembly jar built by `mill matchmaker.api.assembly`."
  type        = string
}

variable "lambda_memory_mb" {
  description = "Lambda memory, which also determines its CPU share. The JVM cold start is sensitive to this."
  type        = number
  default     = 1024
}

variable "lambda_timeout_s" {
  description = "Lambda timeout in seconds."
  type        = number
  default     = 30
}

variable "db_pool_size" {
  description = "Maximum pooled database connections per Lambda container."
  type        = number
  default     = 4
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default     = 30
}
