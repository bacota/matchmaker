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
  default     = 2048

  validation {
    condition     = var.lambda_memory_mb >= 512
    error_message = "Below 512 MB the JVM cold start becomes painful; raise it rather than lowering it."
  }
}

variable "lambda_snap_start" {
  description = <<-EOT
    Snapshot the initialized JVM at publish time so cold starts resume it rather than booting one.
    The largest cold-start win available to a JVM Lambda, and the reason the function is published
    and invoked through an alias.

    Per-environment because it is a trade: publishing a version takes a minute or two longer, since
    AWS runs the init phase and snapshots it before the version is usable. Worth turning off in an
    environment where deploys are frequent and cold-start latency does not matter.
  EOT
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default     = 7

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
  default     = "OFF"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "Must be OFF, AUDIT or ENFORCED."
  }
}

variable "refresh_token_validity_days" {
  description = "How long a refresh token stays usable, and so how long a player stays signed in."
  type        = number
  default     = 30

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

variable "db_user" {
  description = "Database user the function connects as."
  type        = string
}

variable "db_password" {
  description = <<-EOT
    Password for db_user. Reaches the function as a Lambda environment variable.

    Sensitive, so terraform redacts it from plan and apply output — but that is only the console.
    The value is written in plaintext to the terraform state, and into the Lambda's configuration
    where any principal with lambda:GetFunction can read it. Protect the state bucket accordingly,
    and keep this in the gitignored environments/<env>.tfvars.
  EOT
  type        = string
  sensitive   = true
}

variable "subnet_ids" {
  description = "Private subnets that can reach the database."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups the database accepts traffic from."
  type        = list(string)
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
  description = <<-EOT
    Prefix of the hosted login domain. Leave empty to derive "matchmaker-<environment>-<8 hex>"
    from the account and region — unique without having to guess a free name, and stable across
    applies. Set it only to pin a specific name.
  EOT
  type        = string
  default     = ""
}

variable "cognito_sender_email" {
  description = <<-EOT
    Address Cognito sends sign-in codes and verification mail from. Must be a verified SES identity
    in this account and region.

    Empty uses Cognito's built-in sender, which is limited to 50 emails a day for the whole pool —
    acceptable in dev, not in an environment where players sign in with an emailed one-time code.
  EOT
  type        = string
  default     = ""
}

variable "cognito_sender_identity_arn" {
  description = <<-EOT
    ARN of the verified SES identity behind cognito_sender_email. Empty derives
    identity/<cognito_sender_email>, which is correct when that exact address was verified. Set it
    when a domain was verified instead — the derived ARN would name an identity that does not exist.
  EOT
  type        = string
  default     = ""
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

variable "ui_bucket_name" {
  description = <<-EOT
    Bucket the built UI is uploaded to. S3 bucket names are global rather than per-account, so the
    environment name alone does not guarantee one is available.

    Empty falls back to "matchmaker-<environment>-ui". Belongs in environments/<env>.tfvars, with
    the other account facts — it names a real bucket, and changing it replaces that bucket.
  EOT
  type        = string
  default     = ""
}

variable "ui_domain_name" {
  description = <<-EOT
    Domain the UI is served from, e.g. "matchmaker.example.com". Empty keeps the generated
    *.cloudfront.net name and writes nothing to Route 53 — which is the usual choice for dev.

    Setting it requires hosted_zone_id and ui_certificate_arn as well, and moves the user pool's
    callback URLs and the API's CORS origins onto the new name.
  EOT
  type        = string
  default     = ""
}

variable "ui_certificate_arn" {
  description = <<-EOT
    ARN of an existing ACM certificate covering ui_domain_name. Required whenever ui_domain_name is
    set, and must be in us-east-1 — the only region CloudFront reads certificates from — and
    already ISSUED.

    Referenced, never created, for the same reason as the hosted zone and the database secret: a
    certificate normally outlives and is shared beyond this stack.
  EOT
  type        = string
  default     = ""
}

variable "hosted_zone_id" {
  description = <<-EOT
    Existing Route 53 public hosted zone that ui_domain_name sits in, e.g. "Z1234567890ABC".
    Required whenever ui_domain_name is set.

    The zone is looked up, never created or destroyed: it generally holds records unrelated to this
    application.
  EOT
  type        = string
  default     = ""
}

variable "ui_price_class" {
  description = <<-EOT
    CloudFront price class for the UI distribution. PriceClass_All serves from every edge region;
    PriceClass_200 and PriceClass_100 are cheaper and progressively slower outside North America
    and Europe.

    Set it per environment in environments/<env>.settings.tfvars if dev does not need the reach.
  EOT
  type        = string
  default     = "PriceClass_All"
}

variable "admin_initial_password" {
  description = <<-EOT
    Password for the admin Cognito user, set once at creation. Empty mails a temporary password to
    cognito_sender_email instead; set it when that address cannot receive mail yet.

    A credential, so it belongs in environments/<env>.secrets.tfvars. Stored in plaintext in the
    state; treat it as a bootstrap value and change it in managed login after the first sign-in.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}
