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
