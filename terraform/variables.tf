variable "environment" {
  description = "Which environment this run targets. Selects the policy block in main.tf and prefixes every resource name."
  type        = string

  validation {
    # Not a free-form string: a typo would otherwise index into `local.settings` and fail with an
    # error about a missing key rather than about the thing that is actually wrong.
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "Must be dev or prod. Adding an environment means adding a block to local.settings in main.tf."
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

# ---------------------------------------------------------------------------
# The account this is deployed into. All from environments/<env>.tfvars.
# ---------------------------------------------------------------------------

variable "rds_endpoint" {
  description = "Aurora writer endpoint, with or without a port."
  type        = string
}

variable "db_name" { type = string }

variable "db_secret_name" {
  description = "Existing Secrets Manager secret holding {\"username\": ..., \"password\": ...}. Never created here."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets that can reach the database."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups the database accepts traffic from."
  type        = list(string)
}

variable "secrets_extension_layer_arn" {
  description = "AWS Parameters and Secrets Lambda Extension layer. Region- and version-specific."
  type        = string
}

variable "lambda_jar_path" {
  description = "Built with `mill matchmaker.api.assembly`."
  type        = string
  default     = "../out/matchmaker/api/assembly.dest/out.jar"
}

# ---------------------------------------------------------------------------
# Cognito and the UI
# ---------------------------------------------------------------------------

variable "hosted_login_domain_prefix" {
  description = "Prefix of the hosted login domain. Unique across all AWS accounts, so it cannot be derived from the environment name."
  type        = string
}

variable "callback_urls" {
  description = <<-EOT
    URLs hosted login may return to, matched literally by Cognito — the trailing slash counts.
    These are page roots rather than a /callback path, because the UI handles the redirect on the
    page it left from.
  EOT
  type        = list(string)
}

variable "logout_urls" {
  description = "URLs hosted login may return to after sign-out. Same exact-match rule."
  type        = list(string)
}

variable "cors_allowed_origins" {
  description = "Origins the UI is served from: scheme, host and port, no path and no trailing slash."
  type        = list(string)
}
