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

# ---------------------------------------------------------------------------
# Per-environment policy
# ---------------------------------------------------------------------------
#
# One root configuration for every environment. What differs between them is values, and the
# values that are decisions rather than account facts live here, committed, side by side — you can
# read off what prod does differently from dev without opening two files or diffing two states.
#
# What is *not* here is anything account-specific: subnet ids, the database endpoint, the secret
# name, the domain prefix. Those come from `environments/<env>.tfvars`, which is gitignored
# because it names real infrastructure.
locals {
  settings = {
    dev = {
      # Small and cheap. Advanced security is billed per monthly active user and there is nothing
      # in dev worth protecting.
      lambda_memory_mb            = 1024
      log_retention_days          = 7
      advanced_security_mode      = "OFF"
      refresh_token_validity_days = 30
    }

    prod = {
      # More memory buys proportionally more CPU, which is what shortens the JVM cold start; logs
      # are kept far longer. Advanced security blocks sign-ins using credentials known to be
      # compromised, and a stolen refresh token is worth more here, so sessions are shorter.
      lambda_memory_mb            = 2048
      log_retention_days          = 90
      advanced_security_mode      = "ENFORCED"
      refresh_token_validity_days = 7
    }
  }

  settings_for_this_environment = local.settings[var.environment]
}

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

  # Policy, from the table above.
  lambda_memory_mb            = local.settings_for_this_environment.lambda_memory_mb
  log_retention_days          = local.settings_for_this_environment.log_retention_days
  advanced_security_mode      = local.settings_for_this_environment.advanced_security_mode
  refresh_token_validity_days = local.settings_for_this_environment.refresh_token_validity_days
}
