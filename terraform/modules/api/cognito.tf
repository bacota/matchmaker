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

# For the SES identity ARN below, so this still builds a valid ARN in GovCloud and China.
data "aws_partition" "current" {}

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

  /* Sign-in factors the pool accepts *first*, before any MFA step.
   *
   * EMAIL_OTP is passwordless: Cognito mails a one-time code and that code alone signs the player
   * in. PASSWORD is kept alongside it, so this adds a way in rather than replacing one — existing
   * players keep their passwords, and a player who never sets one never needs to invent it.
   *
   * Suits this application specifically: email is already the sign-in identifier and is already
   * verified (`auto_verified_attributes` below), so the OTP is delivered to an address Cognito has
   * confirmed the player controls.
   */
  sign_in_policy {
    allowed_first_auth_factors = ["PASSWORD", "EMAIL_OTP"]
  }

  /* Who the pool's mail comes from.
   *
   * Without this Cognito uses its own sender, which is capped at 50 emails a day across the pool
   * and comes from an address nobody recognizes. Sign-in now depends on those emails, so an
   * environment that real players use wants SES here.
   *
   * source_arn defaults to the address's own identity — an SES email identity is always
   * arn:aws:ses:<region>:<account>:identity/<address>, both of which this module already knows, so
   * the common case needs no second variable. When the verified identity is a domain rather than
   * the address, that derived ARN does not exist; cognito_sender_identity_arn overrides it.
   */
  dynamic "email_configuration" {
    for_each = var.cognito_sender_email == "" ? [] : [var.cognito_sender_email]
    content {
      email_sending_account = "DEVELOPER"
      from_email_address    = email_configuration.value
      source_arn = (
        var.cognito_sender_identity_arn != ""
        ? var.cognito_sender_identity_arn
        : "arn:${data.aws_partition.current.partition}:ses:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:identity/${email_configuration.value}"
      )
    }
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

  # Managed login (branding version 2) rather than the classic hosted UI. Not a cosmetic choice:
  # the classic pages have no passwordless support at all, so EMAIL_OTP would be enabled on the
  # pool and unreachable from the browser. This does change how the sign-in pages look.
  managed_login_version = 2
}

# Managed login refuses to render without a branding record. Cognito's own defaults are used rather
# than a style defined here: it is a sign-in page for a game, and a hand-built theme is a thing to
# maintain for no benefit. Replace `use_cognito_provided_values` with a `settings` document to
# theme it later.
resource "aws_cognito_managed_login_branding" "hosted_login" {
  user_pool_id                = aws_cognito_user_pool.users.id
  client_id                   = aws_cognito_user_pool_client.app.id
  use_cognito_provided_values = true
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
    # The choice-based flow: the client asks which factors are available and the player picks one.
    # This is what surfaces "email me a code" as an option — without it the pool would accept
    # EMAIL_OTP and nothing would ever offer it.
    "ALLOW_USER_AUTH",
  ]

  read_attributes  = ["email", "email_verified"]
  write_attributes = ["email"]
}

# ---------------------------------------------------------------------------
# Admin user
# ---------------------------------------------------------------------------

/* One administrator, created with the pool.
 *
 * A pool with no users is not a working environment: every route requires a signed-in caller, and
 * the routes that create games require an *admin* player, so without this someone has to sign up
 * through hosted login and then hand-edit a row in the database to grant themselves admin. This
 * makes the first administrator part of the environment instead of a manual step.
 *
 * The address is cognito_sender_email — the same address the pool sends its mail from. That is
 * deliberate: it is already a verified SES identity in this account, so the temporary password
 * below reaches it even while the account is in the SES sandbox, which no other address is
 * guaranteed to do. It also means the user only exists when the pool has a real sender; with the
 * built-in Cognito sender there is nowhere reliable to deliver the invitation, so `count` is zero.
 *
 * Two ways in, chosen by admin_initial_password:
 *
 * - Unset (the default): no password is given, so Cognito generates a temporary one and mails it.
 *   Nothing secret enters the configuration or the state. The first sign-in is the new-password
 *   challenge, which managed login renders; after that the account is CONFIRMED.
 * - Set: that password is installed as a *permanent* one and the account is CONFIRMED
 *   immediately, so the administrator can sign in without waiting on an email — which is what
 *   makes a brand-new environment usable before SES is out of the sandbox. The invitation is
 *   suppressed, since it would carry a temporary password that is not the one to use.
 *
 * Either way, once the account is CONFIRMED, EMAIL_OTP works for it like any other player's.
 */
resource "aws_cognito_user" "admin" {
  count = var.cognito_sender_email == "" ? 0 : 1

  user_pool_id = aws_cognito_user_pool.users.id
  username     = var.cognito_sender_email

  # Permanent, not temporary: a temporary password expires (7 days, per the pool's policy above)
  # and forces a change on first use, which would make the variable's value wrong as soon as it
  # was used. null when unset, which is how terraform omits an argument entirely.
  password = var.admin_initial_password != "" ? var.admin_initial_password : null

  # Nothing to deliver when the password is already known; without this Cognito still mails an
  # invitation quoting a temporary password that no longer signs anyone in.
  message_action           = var.admin_initial_password != "" ? "SUPPRESS" : null
  desired_delivery_mediums = var.admin_initial_password != "" ? null : ["EMAIL"]

  attributes = {
    email = var.cognito_sender_email

    # Pre-verified: the address is a verified SES identity, and an unverified one cannot use
    # account recovery or EMAIL_OTP — which would leave the administrator locked out of the two
    # ways back in.
    email_verified = true
  }

  lifecycle {
    # Recreating this user issues a new `sub`, and the admin's player row is keyed by the old one:
    # the account would come back with no player attached and no way to grant itself admin again.
    prevent_destroy = true

    /* Cognito owns temporary_password after the first sign-in, and the other two only ever mean
     * anything while the user is being created — an invitation cannot be un-sent. Ignoring them
     * keeps a later change to admin_initial_password from proposing to alter arguments that
     * cannot be altered on an existing user.
     *
     * `password` is deliberately *not* ignored, so that changing admin_initial_password actually
     * resets the password. An administrator who has since changed it in managed login is
     * unaffected: terraform compares against the configuration, which has not moved.
     */
    ignore_changes = [temporary_password, message_action, desired_delivery_mediums]
  }
}
