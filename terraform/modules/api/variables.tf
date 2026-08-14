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

variable "db_user" {
  description = "Database user the function connects as."
  type        = string
}

variable "db_password" {
  description = <<-EOT
    Password for db_user, passed to the function as an environment variable.

    Marked sensitive so it is redacted from plan and apply output, but note what that does not
    cover: the value is stored in plaintext in the terraform state, and in the Lambda's own
    configuration where anyone holding lambda:GetFunction can read it back.
  EOT
  type        = string
  sensitive   = true
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

variable "lambda_snap_start" {
  description = <<-EOT
    Whether to snapshot the initialized JVM at publish time so cold starts resume it instead of
    booting one. This is the single largest cold-start win available to a JVM Lambda.

    Safe here only because the handler builds its database pool, credentials and session token
    lazily, on the first request rather than during init — so none of them are captured in the
    snapshot and restored into many execution environments at once. Priming them at init would
    require org.crac checkpoint/restore hooks first.

    Turning this off still leaves the function published and invoked through the "live" alias; only
    the snapshot goes away.
  EOT
  type        = bool
  default     = true
}

variable "db_pool_size" {
  description = "Maximum pooled database connections per Lambda container."
  type        = number
  default     = 4
}

variable "hosted_login_domain_prefix" {
  description = <<-EOT
    Prefix of the Cognito hosted login domain, giving
    https://<prefix>.auth.<region>.amazoncognito.com.

    Empty — the default — derives it as "matchmaker-<environment>-<8 hex>", where the hex is a hash
    of the account id and region. The prefix must be unique across all AWS accounts, not only
    yours, so the pool name alone will not do; the hash is what makes it unique without anyone
    having to pick a free name by trial and error. It is deterministic, so the sign-in URL does not
    move between applies.

    Set it only to override that: an existing pool already on another prefix, or a name chosen for
    how it reads. Changing it on a live environment invalidates every callback URL registered with
    an identity provider, and moves the URL players sign in at.
  EOT
  type        = string
  default     = ""

  validation {
    # Must start and end with a letter or digit: Cognito rejects a leading or trailing hyphen, and
    # doing so here fails the plan rather than the apply, after the rest of the run has succeeded.
    condition     = var.hosted_login_domain_prefix == "" || can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", var.hosted_login_domain_prefix))
    error_message = "Must be 1-63 lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "cognito_sender_email" {
  description = <<-EOT
    Address the pool sends from: one-time sign-in codes, sign-up verification, password resets.

    Empty — the default — uses Cognito's built-in sender, which is capped at 50 emails a day for
    the whole pool and sends from a no-reply@verificationemail.com address. That is fine until
    sign-in depends on those emails arriving, which it does now that EMAIL_OTP is a sign-in factor.

    Setting it switches the pool to SES. The address must be a **verified SES identity in this
    account and region**, and the account must be out of the SES sandbox to mail anyone who has not
    separately confirmed the address. Neither is checked here, and neither fails until an apply.

    A bare address, not "Name <addr@example.com>": by default the SES identity ARN is derived from
    this value, so it has to be the identity itself. Set cognito_sender_identity_arn when the
    verified identity is something else — a domain, most often.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.cognito_sender_email == "" || can(regex("^[^@[:space:]<>]+@[a-z0-9.-]+\\.[a-z]{2,}$", var.cognito_sender_email))
    error_message = "Must be a single bare email address, without a display name."
  }
}

variable "cognito_sender_identity_arn" {
  description = <<-EOT
    ARN of the verified SES identity the pool sends as. Only consulted when cognito_sender_email is
    set; empty derives it as identity/<cognito_sender_email> in this account and region.

    That derivation is right only when the individual address was verified. Verifying a *domain* —
    the usual choice, since it covers every address under it — produces identity/example.com
    instead, and the derived ARN names an identity that does not exist. The apply then fails on the
    user pool with a message about the ARN, not about which address was being sent from.

    So: verified address, leave this empty. Verified domain, set it to the domain identity's ARN.
    SES must consider cognito_sender_email to be within it either way.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.cognito_sender_identity_arn == "" || can(regex("^arn:aws[a-z-]*:ses:[a-z0-9-]+:[0-9]{12}:identity/", var.cognito_sender_identity_arn))
    error_message = "Must be an SES identity ARN: arn:aws:ses:<region>:<account>:identity/<domain or address>."
  }
}

variable "callback_urls" {
  description = <<-EOT
    URLs the hosted UI may redirect to after sign-in. Exact matches, including the path: a URL not
    listed here fails at the authorize step with an opaque error, which is the usual cause of a
    login that will not start.

    Cognito requires https except for http://localhost, which is what lets a local UI use the real
    dev pool.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.callback_urls) > 0
    error_message = "At least one callback URL is required, or hosted login has nowhere to return to."
  }
}

variable "logout_urls" {
  description = "URLs the hosted UI may redirect to after sign-out. Same exact-match rule as callback_urls."
  type        = list(string)
  default     = []
}

variable "cors_allowed_origins" {
  description = <<-EOT
    Origins the browser UI is served from, e.g. https://matchmaker.example.com or
    http://localhost:5173. Scheme, host and port only — no path, and no trailing slash, which the
    browser will not match.
  EOT
  type        = list(string)

  validation {
    condition     = !contains(var.cors_allowed_origins, "*")
    error_message = "Wildcard origins are not allowed: the UI sends an Authorization header, and any page could then call this API."
  }
}

variable "password_minimum_length" {
  description = "Minimum password length the pool enforces."
  type        = number
  default     = 12
}

variable "refresh_token_validity_days" {
  description = "How long a refresh token stays usable, and so how long a player stays signed in."
  type        = number
  default     = 30
}

variable "advanced_security_mode" {
  description = <<-EOT
    Cognito advanced security: "OFF", "AUDIT" (log risk findings) or "ENFORCED" (block and
    challenge). Billed per monthly active user, so it is off by default and set per environment.
  EOT
  type        = string
  default     = "OFF"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "Must be OFF, AUDIT or ENFORCED."
  }
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default     = 30
}
