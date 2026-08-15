#!/usr/bin/env bash
#
# Builds, migrates and deploys one environment.
#
#   ./deploy.sh dev
#   ./deploy.sh prod --yes          # skip the confirmation between plan and apply
#   ./deploy.sh dev --skip-migrate  # when the schema is already current
#
# The order matters and is not arbitrary:
#
#   1. compile everything, including tests, so a broken build stops the deploy before it
#      touches anything
#   2. build the two artifacts terraform uploads: the Lambda jar and the linked UI
#   3. run Flyway, so the schema is ready before code that expects it is live
#   4. plan, show it, and apply *that saved plan* — not a second one computed after you looked
#   5. give the admin Cognito user a player row, which can only happen after the apply that
#      created it, because the row is keyed by the `sub` that apply assigns
#
# Step 3 before step 4 is the safe order only for additive migrations. A migration that drops or
# renames something breaks the currently deployed function the moment it runs, before the new code
# is live. Expand first, contract in a later deploy.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

usage() {
  cat >&2 <<EOF
usage: $0 <dev|prod> [--yes] [--skip-build] [--skip-migrate]

  --yes           apply without asking for confirmation
  --skip-build    do not compile or rebuild artifacts (they must already exist)
  --skip-migrate  do not touch the database: no Flyway, no admin player
EOF
  exit 2
}

[ $# -ge 1 ] || usage

env=$1
shift

case "$env" in
  dev | prod) ;;
  *)
    echo "unknown environment '$env'; expected dev or prod" >&2
    exit 2
    ;;
esac

assume_yes=false
skip_build=false
skip_migrate=false

while [ $# -gt 0 ]; do
  case "$1" in
    --yes | -y) assume_yes=true ;;
    --skip-build) skip_build=true ;;
    --skip-migrate) skip_migrate=true ;;
    *) usage ;;
  esac
  shift
done

readonly vars="$TERRAFORM_DIR/environments/$env.tfvars"
readonly secrets="$TERRAFORM_DIR/environments/$env.secrets.tfvars"

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# Reads one `key = "value"` from a tfvars file.
#
# These files are hand-written HCL, but the values this script needs are always plain quoted
# strings on one line, so a line-oriented read is enough and avoids requiring AWS credentials
# just to learn the database host. Comments are skipped; a missing key yields the empty string.
tfvar() {
  local key=$1 file=$2
  [ -f "$file" ] || return 0
  sed -n \
    -e 's|//.*$||' \
    -e "s|^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\"\(.*\)\"[[:space:]]*$|\1|p" \
    "$file" | tail -n 1
}

require_var() {
  local name=$1 value=$2 file=$3
  if [ ! -f "$file" ]; then
    echo "missing $file, which should set '$name'" >&2
    [ -f "$file.example" ] && echo "copy $file.example to $file and fill it in" >&2
    exit 1
  fi
  if [ -z "$value" ]; then
    echo "'$name' is not set in $file" >&2
    echo "expected a line of the form: $name = \"...\"" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# 1 and 2. Build
# ---------------------------------------------------------------------------
#
# fullL<inkJS rather than fastLinkJS: this output is what gets uploaded, and it is several times
# smaller. Both artifacts are read by terraform through filemd5/filebase64sha256, so a plan run
# before they exist fails rather than deploying something stale.

if [ "$skip_build" = true ]; then
  step "Skipping build"
else
  step "Compiling everything"
  mill --ticker false __.compile

  step "Building the Lambda jar and the UI bundle"
  mill --ticker false matchmaker.api.assembly
  mill --ticker false matchmaker.ui.fullLinkJS
fi

# ---------------------------------------------------------------------------
# 3. Migrate
# ---------------------------------------------------------------------------

# Sets host_port, database, user and password from the tfvars files. Both database steps need
# them, and neither runs when --skip-migrate is given, so the reads stay out of the top level:
# nobody should have to hold the database password to deploy code alone.
resolve_db() {
  local endpoint
  endpoint=$(tfvar rds_endpoint "$vars")
  database=$(tfvar db_name "$vars")
  user=$(tfvar db_user "$vars")
  password=$(tfvar db_password "$secrets")

  require_var rds_endpoint "$endpoint" "$vars"
  require_var db_name "$database" "$vars"
  require_var db_user "$user" "$vars"
  require_var db_password "$password" "$secrets"

  # rds_endpoint may or may not carry a port, the same way the terraform module accepts both.
  case "$endpoint" in
    *:*) host_port=$endpoint ;;
    *) host_port="$endpoint:5432" ;;
  esac
}

if [ "$skip_migrate" = true ]; then
  step "Skipping migrations"
else
  step "Running Flyway against $env"

  resolve_db
  echo "    $user@$host_port/$database"

  # The database is not publicly reachable: it sits in the VPC the Lambda attaches to. This step
  # only works from somewhere with a route to it — a VPN, a bastion tunnel, or a CI runner inside
  # the VPC. It is the one part of this script that does not work from an arbitrary laptop.
  FLYWAY_URL="jdbc:postgresql://$host_port/$database" \
    FLYWAY_USER="$user" \
    FLYWAY_PASSWORD="$password" \
    mill --ticker false matchmaker.flyway.runMain com.vivi.matchmaker.flyway.Migrate
fi

# ---------------------------------------------------------------------------
# 4. Plan, then apply that plan
# ---------------------------------------------------------------------------
#
# The plan is saved and applied from the file, so what gets applied is exactly what was displayed.
# Re-running `apply` on its own would compute a second plan, which can differ from the one you
# reviewed — and with `-auto-approve` you would never see that it had.

plan_file=$(mktemp "${TMPDIR:-/tmp}/matchmaker-$env-XXXXXX.tfplan")
trap 'rm -f "$plan_file"' EXIT

step "Planning $env"
(cd "$TERRAFORM_DIR" && ./tf.sh "$env" plan -out="$plan_file")

if [ "$assume_yes" != true ]; then
  printf '\nApply this plan to \033[1m%s\033[0m? [y/N] ' "$env"
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "aborted; nothing applied" >&2
      exit 1
      ;;
  esac
fi

step "Applying to $env"
(cd "$TERRAFORM_DIR" && ./tf.sh "$env" apply "$plan_file")

# ---------------------------------------------------------------------------
# 5. Seed the admin player
# ---------------------------------------------------------------------------
#
# Terraform creates the admin Cognito user but cannot create its player row: the player table is
# in the VPC, and there is no terraform resource that inserts one. So the two halves are joined
# here, after the apply, once the user's `sub` exists to key the row by.
#
# After the migration step and gated on the same flag, for the same reason: it is the other thing
# in this script that needs a route into the VPC. Empty output means no admin user was configured
# — cognito_sender_email is unset — which is not an error.

if [ "$skip_migrate" = true ]; then
  step "Skipping the admin player"
else
  admin_external_id=$(cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -raw admin_external_id 2>/dev/null || true)

  if [ -z "$admin_external_id" ]; then
    step "No admin user configured; set cognito_sender_email to create one"
  else
    step "Seeding the admin player"

    resolve_db
    FLYWAY_URL="jdbc:postgresql://$host_port/$database" \
      FLYWAY_USER="$user" \
      FLYWAY_PASSWORD="$password" \
      ADMIN_EXTERNAL_ID="$admin_external_id" \
      mill --ticker false matchmaker.flyway.runMain com.vivi.matchmaker.flyway.SeedAdmin
  fi
fi

step "Done"
(cd "$TERRAFORM_DIR" && ./tf.sh "$env" output)
