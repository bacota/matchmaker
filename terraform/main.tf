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
  rds_endpoint       = var.rds_endpoint
  db_name            = var.db_name
  db_user            = var.db_user
  db_password        = var.db_password
  subnet_ids         = var.subnet_ids
  security_group_ids = var.security_group_ids
  lambda_jar_path    = var.lambda_jar_path

  hosted_login_domain_prefix = var.hosted_login_domain_prefix
  cognito_sender_email       = var.cognito_sender_email

  # The deployed UI's own URL is always allowed, without anyone having to copy it into a tfvars
  # after the first apply — that copy is exactly the kind of thing that goes stale and produces a
  # sign-in that fails with an opaque error. The variables add to it: localhost in dev, a custom
  # domain in prod.
  callback_urls        = concat([module.ui.url], var.callback_urls)
  logout_urls          = concat([module.ui.url], var.logout_urls)
  cors_allowed_origins = concat([module.ui.origin], var.cors_allowed_origins)

  # Policy, from environments/<env>.settings.tfvars.
  lambda_memory_mb            = var.lambda_memory_mb
  lambda_snap_start           = var.lambda_snap_start
  log_retention_days          = var.log_retention_days
  advanced_security_mode      = var.advanced_security_mode
  refresh_token_validity_days = var.refresh_token_validity_days
}

/* The browser UI: an S3 bucket behind CloudFront.
 *
 * A separate module from the API because its lifecycle is separate — the UI is redeployed by
 * uploading three files, where the API is redeployed by replacing a Lambda's code.
 *
 * The two modules reference each other, which is fine because no *resource* does: the bucket and
 * distribution are built first, the Cognito client then takes the distribution's URL as a
 * callback, and only then is config.js written with that client's id.
 */
module "ui" {
  source = "./modules/ui"

  environment  = var.environment
  bucket_name  = var.ui_bucket_name
  ui_dir       = var.ui_dir
  main_js_path = var.main_js_path
  price_class  = var.ui_price_class

  domain_name     = var.ui_domain_name
  hosted_zone_id  = var.hosted_zone_id
  certificate_arn = var.ui_certificate_arn

  api_endpoint        = module.api.api_endpoint
  hosted_login_url    = module.api.hosted_login_url
  user_pool_client_id = module.api.user_pool_client_id
}
