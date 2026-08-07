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
variable "secrets_extension_layer_arn" { type = string }

variable "lambda_jar_path" {
  description = "Built with `mill matchmaker.api.assembly`."
  type        = string
  default     = "../../../out/matchmaker/api/assembly.dest/out.jar"
}

variable "hosted_login_domain_prefix" { type = string }

variable "callback_urls" {
  type = list(string)
  # The local UI and local server run against the dev pool, so that hosted login and PKCE are
  # exercised for real before anything is deployed. Cognito allows http only for localhost.
  default = ["http://localhost:8080/callback", "http://localhost:5173/callback"]
}

variable "logout_urls" {
  type    = list(string)
  default = ["http://localhost:8080/", "http://localhost:5173/"]
}

module "api" {
  source = "../../modules/api"

  environment        = "dev"
  rds_endpoint       = var.rds_endpoint
  db_name            = var.db_name
  db_secret_name     = var.db_secret_name
  subnet_ids         = var.subnet_ids
  security_group_ids = var.security_group_ids

  secrets_extension_layer_arn = var.secrets_extension_layer_arn
  lambda_jar_path             = var.lambda_jar_path

  hosted_login_domain_prefix = var.hosted_login_domain_prefix
  callback_urls              = var.callback_urls
  logout_urls                = var.logout_urls

  lambda_memory_mb   = 1024
  log_retention_days = 7

  # Off in dev: it is billed per monthly active user and there is nothing here worth protecting.
  advanced_security_mode = "OFF"
}

output "api_endpoint" {
  value = module.api.api_endpoint
}

output "lambda_function_name" {
  value = module.api.lambda_function_name
}

output "user_pool_id" {
  value = module.api.user_pool_id
}

output "user_pool_client_id" {
  value = module.api.user_pool_client_id
}

output "hosted_login_url" {
  value = module.api.hosted_login_url
}

output "jwt_issuer" {
  value = module.api.jwt_issuer
}
