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

variable "matchmaker_api_key" {
  description = <<-EOT
    The secret this engine and matchmaker authenticate each other with, in both directions:
    matchmaker presents it when it creates a game or asks for a match's status, and this engine
    presents it on its move and result callbacks.

    Required. A deployed engine with no key would serve game creation to anyone who found the
    url, and the function refuses to start rather than do that — this variable has no default so
    that the refusal happens at plan time instead.
  EOT
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.matchmaker_api_key) >= 24
    error_message = "The API key must be at least 24 characters; it is a bearer token and the only thing protecting these routes."
  }
}

variable "game_external_id" {
  description = <<-EOT
    Sent as `X-External-Id` on the callbacks, which only a matchmaker running in header-auth mode
    reads — a deployed one identifies this engine by which API key the callback carried, and the
    name it files that key under is the game's `external_id`.

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
