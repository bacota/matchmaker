#!/usr/bin/env bash
#
# Builds and deploys the tic-tac-toe game engine, without redeploying matchmaker.
#
#   ./deploy-tictactoe.sh dev
#   ./deploy-tictactoe.sh dev --yes         # skip the confirmation between plan and apply
#   ./deploy-tictactoe.sh dev --full        # plan the whole configuration, not just the engine
#   ./deploy-tictactoe.sh dev --skip-build  # the jar must already exist
#
# Separate from deploy.sh because the engine has a separate lifecycle: it is a test fixture, it is
# rebuilt and redeployed far more often than matchmaker itself, and it touches neither the
# database nor the UI. Nothing here needs a route into the VPC, so unlike deploy.sh this runs from
# anywhere with AWS credentials.
#
# By default the plan is limited to the engine's own module plus the one resource outside it that
# the engine changes: the Cognito app client, whose callback urls have to include the engine's
# /auth/callback for a player to be able to sign in to the board. That targeting is what lets this
# run without matchmaker's own artifacts being built — a full plan reads the api jar and the UI
# bundle, and would fail if they were absent or deploy them if they were stale. Use --full when
# the whole environment should be planned together.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

# The engine's module, and the one resource outside it that deploying the engine changes.
readonly TARGETS=(
  -target=module.tictactoe
  -target=module.api.aws_cognito_user_pool_client.app
)

usage() {
  cat >&2 <<EOF
usage: $0 <dev|prod> [--yes] [--full] [--skip-build] [--skip-tests]

  --yes         apply without asking for confirmation
  --full        plan the whole configuration instead of just the engine; needs
                matchmaker's own artifacts to have been built (see ./deploy.sh)
  --skip-build  do not compile or rebuild the jar (it must already exist)
  --skip-tests  build the jar without running the engine's tests first
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
full=false
skip_build=false
skip_tests=false

while [ $# -gt 0 ]; do
  case "$1" in
    --yes | -y) assume_yes=true ;;
    --full) full=true ;;
    --skip-build) skip_build=true ;;
    --skip-tests) skip_tests=true ;;
    *) usage ;;
  esac
  shift
done

readonly settings="$TERRAFORM_DIR/environments/$env.settings.tfvars"
readonly jar="out/engines/tictactoe/assembly.dest/out.jar"

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# ---------------------------------------------------------------------------
# Is the engine even enabled here?
# ---------------------------------------------------------------------------
#
# deploy_tictactoe defaults to false, so without it this would plan, apply and report success
# having created nothing at all — the most confusing possible outcome. Checked before the build,
# so the answer comes back in a second rather than after a compile.

if ! grep -Eq '^[[:space:]]*deploy_tictactoe[[:space:]]*=[[:space:]]*true' "$settings" 2>/dev/null; then
  echo "the tic-tac-toe engine is not enabled for '$env'" >&2
  echo >&2
  echo "add this to $settings:" >&2
  echo >&2
  echo '    // The bundled tic-tac-toe engine: a test fixture for the game interaction.' >&2
  echo '    deploy_tictactoe = true' >&2
  exit 1
fi

if [ "$env" = "prod" ]; then
  # Not refused — an environment is whatever its owner says it is — but this engine has an
  # unauthenticated public board and exists to be played with, so being asked is right.
  printf '\n\033[1mtic-tac-toe is a test fixture; deploy it to prod anyway? [y/N] \033[0m'
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "aborted; nothing built or applied" >&2
      exit 1
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
#
# The engine's tests need nothing but a JVM — no Postgres, no AWS — so unlike matchmaker's they
# can run on the way to every deploy rather than being someone's separate step. ProtocolSpec is
# the one worth having here: it fails when the engine and matchmaker have stopped agreeing on the
# wire format, which is exactly the mistake a deploy would otherwise ship.

if [ "$skip_build" = true ]; then
  step "Skipping build"
  if [ ! -f "$jar" ]; then
    echo "no jar at $jar; run without --skip-build" >&2
    exit 1
  fi
else
  if [ "$skip_tests" != true ]; then
    step "Testing the engine"
    mill -j 4 --ticker false engines.tictactoe.test
  fi

  step "Building the engine jar"
  mill -j 4 --ticker false engines.tictactoe.assembly
fi

echo "    $jar ($(du -h "$jar" | cut -f1))"

# ---------------------------------------------------------------------------
# Plan, then apply that plan
# ---------------------------------------------------------------------------
#
# As in deploy.sh: the plan is saved and applied from the file, so what is applied is exactly what
# was displayed rather than a second plan computed after you looked at the first.

plan_args=()
if [ "$full" != true ]; then
  plan_args=("${TARGETS[@]}")
fi

plan_file=$(mktemp "${TMPDIR:-/tmp}/tictactoe-$env-XXXXXX.tfplan")
trap 'rm -f "$plan_file"' EXIT

step "Planning $env${plan_args:+ (engine only)}"
(cd "$TERRAFORM_DIR" && ./tf.sh "$env" plan "${plan_args[@]}" -out="$plan_file")

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
# What still has to be done by hand
# ---------------------------------------------------------------------------
#
# Matchmaker has no route that creates a game — a game is an administrative fact, not something a
# player does — so the engine is deployed but unreachable until a `game` row points at it. The two
# values that row needs are outputs of the apply above. external_id must be the name matchmaker
# files this engine's API key under, because that key is how a deployed matchmaker tells which
# engine a callback came from; the terraform files it under "tictactoe".
#
# The database is inside the VPC, so this script cannot run the insert itself from an arbitrary
# laptop — the same limitation deploy.sh notes around Flyway. It prints the exact command instead.

output() {
  (cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -raw "$1" 2>/dev/null || true)
}

create_game_url=$(output tictactoe_create_game_url)
external_id=$(output tictactoe_external_id)

step "Deployed"

if [ -z "$create_game_url" ] || [ -z "$external_id" ]; then
  echo "    the engine's outputs are not available; check the apply above" >&2
else
  cat <<EOF
    create game   $create_game_url
    identity      $external_id

To register it as a game in matchmaker — from somewhere with a route to the database:

    psql "\$DATABASE_URL" \\
      -v url="$create_game_url" \\
      -v external_id="$external_id" \\
      -f engines/tictactoe/register-game.sql

Already registered? Update the existing row instead, or matchmaker will keep calling the old url:

    UPDATE game SET url = '$create_game_url', external_id = '$external_id'
     WHERE name = 'Tic-tac-toe';
EOF
fi
