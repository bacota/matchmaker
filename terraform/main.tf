terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.50"
    }
  }
}

provider "aws" {
  region = var.region
}

# One root for every environment. Everything that differs between them is a variable: account
# facts come from environments/<env>.tfvars, policy from environments/<env>.settings.tfvars, and
# `tf.sh` passes both. Nothing in this file names an environment.
module "api" {
  source = "./modules/api"

  environment = var.environment

  # Account facts, from environments/<env>.tfvars.
  rds_endpoint                = var.rds_endpoint
  db_name                     = var.db_name
  db_secret_name              = var.db_secret_name
  subnet_ids                  = var.subnet_ids
  security_group_ids          = var.security_group_ids
  secrets_extension_layer_arn = var.secrets_extension_layer_arn
  lambda_jar_path             = var.lambda_jar_path

  hosted_login_domain_prefix = var.hosted_login_domain_prefix
  callback_urls              = var.callback_urls
  logout_urls                = var.logout_urls
  cors_allowed_origins       = var.cors_allowed_origins

  # Policy, from environments/<env>.settings.tfvars.
  lambda_memory_mb            = var.lambda_memory_mb
  log_retention_days          = var.log_retention_days
  advanced_security_mode      = var.advanced_security_mode
  refresh_token_validity_days = var.refresh_token_validity_days
}
