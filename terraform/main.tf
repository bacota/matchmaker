terraform {
  # 1.10 rather than 1.5 for the backend: S3-native locking (`use_lockfile` in
  # environments/<env>.backend.hcl) was added there. The modules stay at 1.5 — nothing in them
  # needs anything newer.
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.50"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6"
    }
  }
}

provider "aws" {
  region = var.region
}

/* The secret matchmaker and the bundled engine authenticate each other with.
 *
 * Generated rather than written into a tfvars, so that nobody has to copy the same string into
 * two places and keep them in step — the pair is only ever configured together, and a key that
 * differs between the two sides is an outage. It lands in the state file, as `db_password`
 * already does; both are also readable from the functions' configuration by anyone holding
 * lambda:GetFunction, which is the same trade this deployment already makes for the database.
 *
 * Rotating it is `terraform apply -replace='random_password.tictactoe_api_key[0]'`. Both functions are
 * updated in the same apply, so there is a window of a few seconds in which one has the new key
 * and the other the old; a create-game call in that window fails and the player retries.
 *
 * Only for the engine deployed from this repository. An engine someone else runs has its key
 * agreed out of band and passed in through `engine_api_keys` / `game_engine_api_keys`.
 */
resource "random_password" "tictactoe_api_key" {
  count = var.deploy_tictactoe ? 1 : 0

  length = 48
  # Alphanumeric only: the key travels in an HTTP header and is written into a `name=key` list,
  # so a comma or an equals sign in it would be a parsing problem rather than extra entropy. 48
  # characters of base62 is about 285 bits, which is plenty without them.
  special = false
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

  hosted_login_domain_prefix  = var.hosted_login_domain_prefix
  cognito_sender_email        = var.cognito_sender_email
  cognito_sender_identity_arn = var.cognito_sender_identity_arn

  # A credential, from environments/<env>.secrets.tfvars alongside db_password.
  admin_initial_password = var.admin_initial_password

  # The deployed UI's own URL is always allowed, without anyone having to copy it into a tfvars
  # after the first apply — that copy is exactly the kind of thing that goes stale and produces a
  # sign-in that fails with an opaque error. The variables add to it: localhost in dev, a custom
  # domain in prod.
  # The engine's sign-in redirect joins the UI's: its board page runs the same hosted-login flow,
  # and Cognito will only redirect back to a url registered here.
  callback_urls        = concat([module.ui.url], var.deploy_tictactoe ? [module.tictactoe[0].auth_callback_url] : [], var.callback_urls)
  logout_urls          = concat([module.ui.url], var.logout_urls)
  cors_allowed_origins = concat([module.ui.origin], var.cors_allowed_origins)

  # The engines this matchmaker may call, and which may call it back — one shared key per engine,
  # wired here rather than inside either module because it is the one fact both halves need.
  #
  # Inbound entries are keyed by the engine's external_id and outbound by its host, which is what
  # each side has in hand at the point it needs the key; the bundled engine contributes the same
  # secret to both. The engine's external_id is its module name, and that string has to match the
  # `external_id` column of its row in the `game` table — see engines/tictactoe/README.md.
  engine_api_keys = merge(
    var.engine_api_keys,
    var.deploy_tictactoe ? { tictactoe = random_password.tictactoe_api_key[0].result } : {}
  )
  game_engine_api_keys = merge(
    var.game_engine_api_keys,
    var.deploy_tictactoe ? { (module.tictactoe[0].api_host) = random_password.tictactoe_api_key[0].result } : {}
  )

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

/* A game engine to develop and test the engine interaction against: two-player tic-tac-toe.
 *
 * Off by default, and never something a production environment needs — it exists so that all four
 * exchanges of `interaction-design.txt` can be driven against a real API Gateway and a real
 * callback rather than against a stub. The grants that connect it to matchmaker are in
 * the api module block above; nothing here depends on that module, so the pair applies in one go.
 *
 * A `game` row still has to be created by hand, since matchmaker has no route that registers a
 * game: its `url` is this module's create_game_url and its `external_id` is the name its API key
 * is filed under above, i.e. "tictactoe". See engines/tictactoe/README.md.
 */
module "tictactoe" {
  count  = var.deploy_tictactoe ? 1 : 0
  source = "./modules/tictactoe"

  environment     = var.environment
  lambda_jar_path = var.tictactoe_jar_path

  # The same secret the api module above is given, which is what makes the pair a pair.
  matchmaker_api_key = random_password.tictactoe_api_key[0].result

  # The players sign in to matchmaker's user pool, so that a seat can be recognised by the same
  # `sub` matchmaker sent the engine as the player's cognitoId. Referencing the api module here
  # and the engine's callback url there is not a cycle: the callback url comes from the engine's
  # api id, which settles before either authorizer.
  cognito_issuer    = module.api.jwt_issuer
  cognito_client_id = module.api.user_pool_client_id
  hosted_login_url  = module.api.hosted_login_url

  log_retention_days = var.log_retention_days
}
