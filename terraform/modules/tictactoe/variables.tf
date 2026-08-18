variable "environment" {
  description = "Prefixes every resource name, as in the api module."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?$", var.environment))
    error_message = "Must be 1-20 lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "lambda_jar_path" {
  description = "Path to the assembled engine jar (`mill -j 4 engines.tictactoe.assembly`)."
  type        = string
}

variable "matchmaker_role_arns" {
  description = <<-EOT
    ARNs of the roles allowed to create games in this engine and check their status — in practice
    matchmaker's Lambda execution role (its `lambda_role_arn` output).

    Empty means nobody may: the routes are AWS_IAM-authorized and an HTTP API has no resource
    policy, so the grant is identity-based and has to be attached to the caller's role. A role in
    another account cannot be attached to from here; use the `invoke_policy_arn` output instead.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for arn in var.matchmaker_role_arns : can(regex("^arn:[^:]+:iam::[0-9]{12}:role/.+$", arn))])
    error_message = "Each entry must be an IAM role ARN, e.g. arn:aws:iam::123456789012:role/matchmaker-dev-lambda."
  }
}

variable "matchmaker_callback_policy_arn" {
  description = <<-EOT
    Matchmaker's `engine_callback_policy_arn`, attached to this engine's role so it may post the
    move and result callbacks.

    Optional because the pair can be wired from either side: naming this engine's role in
    matchmaker's `game_engine_role_arns` attaches the same policy from there. Leave empty when
    matchmaker does that, and set it when the engine is applied against a matchmaker it does not
    control.
  EOT
  type        = string
  default     = ""
}

variable "game_external_id" {
  description = <<-EOT
    Sent as `X-External-Id` on the callbacks, which only a matchmaker running in header-auth mode
    reads — a deployed one identifies this engine by its signature instead, and the value it
    compares against is this module's `lambda_role_arn` output.

    Worth setting only for an engine pointed at a local matchmaker.
  EOT
  type        = string
  default     = ""
}

variable "cognito_issuer" {
  description = <<-EOT
    Token issuer of the user pool the players sign in to — matchmaker's `jwt_issuer` output.

    The players' routes are behind a JWT authorizer configured with this, and the board page signs
    in against the same pool, so a player is the same identity here as in matchmaker and the `sub`
    the engine sees is the `cognitoId` matchmaker sent. Empty leaves those routes off the api
    altogether rather than open.
  EOT
  type        = string
  default     = ""
}

variable "cognito_client_id" {
  description = "App client the board page signs in with — matchmaker's `user_pool_client_id` output. Also the audience the authorizer requires."
  type        = string
  default     = ""
}

variable "hosted_login_url" {
  description = "Base url of the hosted login — matchmaker's `hosted_login_url` output. Where the board page sends a player to sign in."
  type        = string
  default     = ""
}

variable "lambda_memory_mb" {
  description = "Lambda memory, which also sets its CPU share."
  type        = number
  default     = 1024
}

variable "lambda_timeout_s" {
  description = "Lambda timeout. A move is two DynamoDB calls and up to two callbacks to matchmaker."
  type        = number
  default     = 15
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default     = 14
}

variable "point_in_time_recovery" {
  description = "Continuous backups for the match table. Off by default: a tic-tac-toe board is not worth restoring."
  type        = bool
  default     = false
}
