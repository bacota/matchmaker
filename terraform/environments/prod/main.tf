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

# No defaults and no localhost: prod's callback URLs are the real site's, and every one listed
# here is a URL an authorization code may be delivered to.
variable "callback_urls" { type = list(string) }
variable "logout_urls" { type = list(string) }

// Origin the UI is served from. No localhost here.
variable "cors_allowed_origins" { type = list(string) }

module "api" {
  source = "../../modules/api"

  environment        = "prod"
  rds_endpoint       = var.rds_endpoint
  db_name            = var.db_name
  db_secret_name     = var.db_secret_name
  subnet_ids         = var.subnet_ids
  security_group_ids = var.security_group_ids

  secrets_extension_layer_arn = var.secrets_extension_layer_arn
  lambda_jar_path             = var.lambda_jar_path

  # More memory buys proportionally more CPU, which is what shortens the JVM cold start; logs are
  # kept far longer than in dev.
  lambda_memory_mb   = 2048
  log_retention_days = 90

  hosted_login_domain_prefix = var.hosted_login_domain_prefix
  cors_allowed_origins       = var.cors_allowed_origins
  callback_urls              = var.callback_urls
  logout_urls                = var.logout_urls

  # Real accounts: block sign-ins with credentials known to be compromised, and challenge risky
  # ones. This is billed per monthly active user.
  advanced_security_mode = "ENFORCED"

  # A shorter session than dev's, since a stolen refresh token here is worth something.
  refresh_token_validity_days = 7
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
