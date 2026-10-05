variable "environment" {
  description = "Prefixes every resource name, as in the api module."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?$", var.environment))
    error_message = "Must be 1-20 lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "name" {
  description = "The engine's name — rps, tictactoe, boxing — which every resource here is named after, with the environment."
  type        = string
}

variable "handler" {
  description = "The Lambda handler: the engine's own `com.vivi.<game>.Handler::handleRequest`."
  type        = string
}

variable "extra_player_routes" {
  description = "Player routes only this game has, beside the state and moves every engine serves. Behind the same JWT authorizer."
  type        = list(string)
  default     = []
}

variable "extra_open_routes" {
  description = "Routes only this game has that anyone may reach, beside the play page and board every engine serves — a page that signs the player in itself, as the play page does."
  type        = list(string)
  default     = []
}

variable "matchmaker_url" {
  description = <<-EOT
    Matchmaker's API base url — its `api_endpoint` output — for the calls this engine makes on its
    own account rather than about one match's moves: archiving a finished match and reading it
    back once its live copy is gone, which every engine does, and for a character game, reporting
    a character a player has built here. Required, with no default: an engine left without it
    would quietly never archive anything.
  EOT
  type        = string
}

variable "lambda_jar_path" {
  description = "Path to the assembled engine jar (`mill -j 4 --ticker false engines.<name>.assembly`)."
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

    The players' routes are behind a JWT authorizer configured with this, and the play page signs
    in against the same pool, so a player is the same identity here as in matchmaker and the `sub`
    the engine sees is the `cognitoId` matchmaker sent. Empty leaves those routes off the api
    altogether rather than open.
  EOT
  type        = string
  default     = ""
}

variable "cognito_client_id" {
  description = "App client the play page signs in with — matchmaker's `user_pool_client_id` output. Also the audience the authorizer requires."
  type        = string
  default     = ""
}

variable "hosted_login_url" {
  description = "Base url of the hosted login — matchmaker's `hosted_login_url` output. Where the play page sends a player to sign in."
  type        = string
  default     = ""
}

variable "lambda_memory_mb" {
  description = "Lambda memory, which also sets its CPU share."
  type        = number
  default     = 1024
}

variable "lambda_snap_start" {
  description = "Resume a snapshot of the initialized JVM on a cold start rather than booting one. The root's lambda_snap_start, shared with matchmaker's api function."
  type        = bool
  default     = true
}

variable "lambda_timeout_s" {
  description = <<-EOT
    Lambda timeout. A move is two DynamoDB calls and up to two callbacks to matchmaker; building a
    boxer waits on matchmaker's answer. The move that ends a match also archives it -- a request to
    matchmaker, an upload to S3, a confirm, and a delete -- after the results are sent, and that
    work is what this allows a minute for. The gateway in front answers its caller after 30
    seconds whatever happens; the function carries on to finish the archive.
  EOT
  type        = number
  default     = 60
}

variable "log_retention_days" {
  description = "Retention for the Lambda and API access log groups."
  type        = number
  default     = 14
}

variable "point_in_time_recovery" {
  description = "Continuous backups for the match table. Off by default: a bundled engine's matches are short, and a character's state lives in matchmaker."
  type        = bool
  default     = false
}

variable "domain_name" {
  description = <<-EOT
    A friendly url of the engine's own, e.g. "boxing.matchmaker-dev.vivi.com": the host its play
    pages, its public board and matchmaker's calls are reached at, in place of API Gateway's
    generated execute-api host. Empty for none, which leaves the engine on that host.

    The certificate is made here and validated through `hosted_zone_id`, so the name must be in
    that zone. The execute-api host goes on answering too, so a match whose urls were handed out
    before the name was set still opens.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.domain_name == "" || can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", var.domain_name))
    error_message = "domain_name must be a lower-case host name such as boxing.example.com, or empty."
  }
}

variable "hosted_zone_id" {
  description = "Route 53 public hosted zone `domain_name` is in: where its certificate is validated and its alias records are written. Required when domain_name is set."
  type        = string
  default     = ""
}
