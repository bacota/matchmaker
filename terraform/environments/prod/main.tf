terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "rds_endpoint" { type = string }
variable "db_name" { type = string }
variable "db_secret_name" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_ids" { type = list(string) }

variable "lambda_jar_path" {
  description = "Built with `mill matchmaker.api.assembly`."
  type        = string
  default     = "../../../out/matchmaker/api/assembly.dest/out.jar"
}

module "api" {
  source = "../../modules/api"

  environment        = "prod"
  rds_endpoint       = var.rds_endpoint
  db_name            = var.db_name
  db_secret_name     = var.db_secret_name
  subnet_ids         = var.subnet_ids
  security_group_ids = var.security_group_ids
  lambda_jar_path    = var.lambda_jar_path

  # More memory buys proportionally more CPU, which is what shortens the JVM cold start; logs are
  # kept far longer than in dev.
  lambda_memory_mb   = 2048
  log_retention_days = 90
}

output "api_endpoint" {
  value = module.api.api_endpoint
}

output "lambda_function_name" {
  value = module.api.lambda_function_name
}
