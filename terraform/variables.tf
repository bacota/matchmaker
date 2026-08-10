variable "environment" {
  description = "Which environment this run targets. Selects the policy block in main.tf and prefixes every resource name."
  type        = string

  validation {
    # A format check rather than a list of names: every environment now supplies its own values,
    # so adding one means adding files under environments/, never editing this configuration.
    # The shape is constrained because it prefixes resource names, several of which are
    # restricted to lowercase alphanumerics and hyphens.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?$", var.environment))
    error_message = "Must be 1-20 lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

# ---------------------------------------------------------------------------
# Per-environment policy
# ---------------------------------------------------------------------------
#
# Deliberately without defaults. These four are the decisions that differ between environments,
# and every one of them is wrong in a way that matters if it silently falls back to the other
# environment's value: prod with dev's log retention loses its audit trail, prod with advanced
# security off loses compromised-credential blocking, dev with prod's settings costs money.
#
# So a run that does not supply them fails rather than guesses. `tf.sh` passes
# `environments/<env>.settings.tfvars`, which is committed — unlike the account facts above, these
# are decisions worth reviewing in a diff.

variable "lambda_memory_mb" {
  description = "Lambda memory, which also sets its CPU share. The JVM cold start is sensitive to this."
  type        = number
  default = 2048

  validation {
    condition     = var.lambda_memory_mb >= 512
    error_message = "Below 512 MB the JVM cold start becomes painful; raise it rather than lowering it."
  }
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default = 7

  validation {
    # The values CloudWatch actually accepts. An arbitrary number is rejected at apply time with a
    # message that does not say this is the problem.
    condition = contains(
      [1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653],
      var.log_retention_days
    )
    error_message = "Must be a retention period CloudWatch supports: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

variable "advanced_security_mode" {
  description = <<-EOT
    Cognito advanced security: "OFF", "AUDIT" (log risk findings) or "ENFORCED" (block and
    challenge). Billed per monthly active user, which is why it is a per-environment decision.
  EOT
  type        = string
  default = "OFF"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "Must be OFF, AUDIT or ENFORCED."
  }
}

variable "refresh_token_validity_days" {
  description = "How long a refresh token stays usable, and so how long a player stays signed in."
  type        = number
  default = 30

  validation {
    condition     = var.refresh_token_validity_days >= 1 && var.refresh_token_validity_days <= 3650
    error_message = "Cognito accepts 1 to 3650 days."
  }
}

# ---------------------------------------------------------------------------
# The account this is deployed into. All from environments/<env>.tfvars.
# ---------------------------------------------------------------------------

variable "rds_endpoint" {
  description = "RDS writer endpoint, with or without a port."
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

# ---------------------------------------------------------------------------
# The browser UI
# ---------------------------------------------------------------------------

variable "ui_dir" {
  description = "Directory holding the UI's index.html and app.css."
  type        = string
  default     = "../matchmaker/ui"
}

variable "main_js_path" {
  description = <<-EOT
    Linked JavaScript to upload. Default is the `fullLinkJS` output, which is optimised and
    minified; `fastLinkJS` output is several times larger and is for local development.

    Build it with `mill matchmaker.ui.fullLinkJS` before applying.
  EOT
  type        = string
  default     = "../out/matchmaker/ui/fullLinkJS.dest/main.js"
}

variable "ui_price_class" {
  description = "CloudFront price class for the UI distribution."
  type        = string
  default     = "PriceClass_100"
}
