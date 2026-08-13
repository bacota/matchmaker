# ---------------------------------------------------------------------------
# User pool
# ---------------------------------------------------------------------------
#
# One pool per environment, created here rather than referenced as an existing resource: users in
# dev are not users in prod, and a shared pool would let a dev token open a prod session. Because
# the pool is named `matchmaker-${var.environment}`, dev and prod coexist in one account.
#
# Destroying the pool destroys its users, so `prevent_destroy` is set below. That applies to every
# environment, dev included: recreating a dev pool silently invalidates every player row, since a
# player is keyed by a `sub` that no longer exists.

data "aws_region" "current" {}

# For the hosted-login domain prefix below. The account id is never used verbatim: only a hash of
# it appears anywhere, so the public domain name does not publish the AWS account number.
data "aws_caller_identity" "current" {}

resource "aws_cognito_user_pool" "users" {
  name = local.name

  # Email is the sign-in identifier: there is no separate username to remember, and the
  # application never sees it — it identifies players by the pool's `sub`.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length                   = var.password_minimum_length
    require_lowercase                = false
    require_uppercase                = false
    require_numbers                  = false
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    # Anyone can sign themselves up through hosted login; this is a game, not an internal tool.
    allow_admin_create_user_only = false
  }

  # Advanced security (compromised-credential detection, adaptive auth) costs per monthly active
  # user, so it is a per-environment decision rather than always-on.
  dynamic "user_pool_add_ons" {
    for_each = var.advanced_security_mode == "OFF" ? [] : [var.advanced_security_mode]
    content {
      advanced_security_mode = user_pool_add_ons.value
    }
  }

  lifecycle {
    # A pool cannot be recreated without losing every user, and several changes below force
    # replacement. Failing the plan is better than discovering it in the apply output.
    prevent_destroy = true
  }
}

# ---------------------------------------------------------------------------
# Hosted login
# ---------------------------------------------------------------------------

/* The hosted UI's own domain: `https://${domain_prefix}.auth.${region}.amazoncognito.com`.
 *
 * The prefix has to be unique across every AWS account, not just this one, so it cannot simply be
 * the pool name — `matchmaker-dev` is exactly the sort of name someone else has already taken.
 *
 * So it is the pool name plus eight hex characters derived from the account and region. That is
 * unique in practice, stable (the same account and environment always produce the same prefix, so
 * an apply never moves the sign-in URL), and derived rather than invented, which means adding an
 * environment does not require guessing a free name and re-running the apply until one sticks.
 *
 * The account id is hashed rather than used directly: this name is public, appearing in every
 * authorize URL, and an AWS account number is not something to publish for no reason.
 *
 * `var.hosted_login_domain_prefix` overrides it when a specific name is wanted — a pool that
 * already exists under another prefix, or a name chosen for how it reads to users.
 */
resource "aws_cognito_user_pool_domain" "hosted_login" {
  domain       = local.hosted_login_domain
  user_pool_id = aws_cognito_user_pool.users.id
}

resource "aws_cognito_user_pool_client" "app" {
  name         = "${local.name}-app"
  user_pool_id = aws_cognito_user_pool.users.id

  # No client secret, which is what makes this a *public* client: a browser or mobile app cannot
  # keep one. Cognito requires PKCE for the authorization code grant on public clients, so this
  # single line is what enables PKCE — there is no separate flag to set, and there is no way for a
  # caller to opt out of it.
  generate_secret = false

  allowed_oauth_flows_user_pool_client = true

  # Authorization code only. The implicit grant would return tokens in the redirect fragment,
  # where they land in browser history and referrer headers, and it cannot use PKCE.
  allowed_oauth_flows          = ["code"]
  allowed_oauth_scopes         = ["openid", "email", "profile"]
  supported_identity_providers = ["COGNITO"]

  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls

  # The authorization code is exchanged immediately by the browser, so it needs to live for
  # minutes, not the default hour.
  auth_session_validity = 3

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = var.refresh_token_validity_days

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  # Makes tokens revocable, so signing a user out actually ends their session rather than leaving
  # an issued refresh token usable until it expires.
  enable_token_revocation = true

  # A failed sign-in says the same thing whether or not the address has an account, so the hosted
  # UI cannot be used to enumerate who plays.

  prevent_user_existence_errors = "ENABLED"

  explicit_auth_flows = [
    "ALLOW_REFRESH_TOKEN_AUTH",
    # No USER_PASSWORD_AUTH: passwords are typed into the hosted UI, never into this application,
    # which is the reason to use hosted login at all.
    "ALLOW_USER_SRP_AUTH",
  ]

  read_attributes  = ["email", "email_verified"]
  write_attributes = ["email"]
}
