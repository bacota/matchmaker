#!/usr/bin/env bash
#
# Builds and deploys one of the bundled game engines, without redeploying matchmaker.
#
#   ./deploy-engine.sh rps dev
#   ./deploy-engine.sh tictactoe dev --yes         # skip the confirmation between plan and apply
#   ./deploy-engine.sh boxing dev --full           # plan the whole configuration, not just the engine
#   ./deploy-engine.sh rps dev --skip-build        # the jar must already exist
#
# deploy-rps.sh, deploy-tictactoe.sh, deploy-boxing.sh and deploy-stratego.sh are this, with the
# engine named.
#
# Separate from deploy.sh because an engine has a separate lifecycle: it is rebuilt and redeployed
# far more often than matchmaker itself, and it touches neither the database nor the UI. Nothing
# here needs a route into the VPC, so unlike deploy.sh this runs from anywhere with AWS
# credentials. The engines are independent of each other too: they share nothing but the user pool
# they authenticate players against.
#
# An engine's first deployment needs matchmaker to change as well — every engine's does, since
# matchmaker must hold the engine's API key, and boxing's also needs its
# `PUT /characters/{characterId}/state` route to be an engine route, which is how a built fighter
# is kept — and the targeted plan below leaves matchmaker's function alone. Use ./deploy-all.sh
# for that, or --full here.
#
# By default the plan is limited to the engine's own module plus the one resource outside it that
# the engine changes: the Cognito app client, whose callback urls have to include the engine's
# /auth/callback for a player to be able to sign in to the play page. That targeting is what lets
# this run without matchmaker's own artifacts being built — a full plan reads the api jar and the
# UI bundle, and would fail if they were absent or deploy them if they were stale. Use --full when
# the whole environment should be planned together.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

usage() {
  cat >&2 <<EOF
usage: $0 <rps|tictactoe|boxing|stratego> <dev|prod> [--yes] [--full] [--skip-build] [--skip-tests]

  --yes         apply without asking for confirmation
  --full        plan the whole configuration instead of just the engine; needs
                matchmaker's own artifacts to have been built (see ./deploy.sh)
  --skip-build  do not compile or rebuild the jar (it must already exist)
  --skip-tests  build the jar without running the engine's tests first
EOF
  exit 2
}

[ $# -ge 2 ] || usage

engine=$1
env=$2
shift 2

# What each engine is called, in the messages below and as the name to give its game, and what this script
# says about deploying it to prod. Everything else about an engine follows from its name: its
# module, its mill module, its jar, its settings flag and its outputs.
case "$engine" in
  rps)
    title="rock-paper-scissors"
    about="a test fixture for simultaneous turns"
    prod_question="rock-paper-scissors is a test fixture; deploy it to prod anyway?"
    game_name="Rock-paper-scissors"
    ;;
  tictactoe)
    title="tic-tac-toe"
    about="a test fixture for the game interaction"
    prod_question="tic-tac-toe is a test fixture; deploy it to prod anyway?"
    game_name="Tic-tac-toe"
    ;;
  boxing)
    title="boxing"
    about="a character game played in simultaneous rounds"
    prod_question="Deploy the boxing engine to prod?"
    game_name="Boxing"
    ;;
  stratego)
    title="stratego"
    about="a game of hidden information with a simultaneous setup"
    prod_question="Deploy the stratego engine to prod?"
    game_name="Stratego"
    ;;
  *)
    echo "unknown engine '$engine'; expected rps, tictactoe, boxing or stratego" >&2
    exit 2
    ;;
esac

# The engine's module, and the one resource outside it that deploying the engine changes.
readonly TARGETS=(
  "-target=module.$engine"
  -target=module.api.aws_cognito_user_pool_client.app
)

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
readonly jar="out/engines/$engine/assembly.dest/out.jar"

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# ---------------------------------------------------------------------------
# Is the engine even enabled here?
# ---------------------------------------------------------------------------
#
# deploy_<engine> defaults to false, so without it this would plan, apply and report success
# having created nothing at all — the most confusing possible outcome. Checked before the build,
# so the answer comes back in a second rather than after a compile.

if ! grep -Eq "^[[:space:]]*deploy_${engine}[[:space:]]*=[[:space:]]*true" "$settings" 2>/dev/null; then
  echo "the $title engine is not enabled for '$env'" >&2
  echo >&2
  echo "add this to $settings:" >&2
  echo >&2
  echo "    // The bundled $title engine: $about." >&2
  echo "    deploy_${engine} = true" >&2
  exit 1
fi

if [ "$env" = "prod" ]; then
  # Not refused — an environment is whatever its owner says it is — but every engine has an
  # unauthenticated public board, and the bundled ones exist to be played with, so being asked is
  # right.
  printf '\n\033[1m%s [y/N] \033[0m' "$prod_question"
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
    # engines.common's tests too: the jar carries that code, and a dependency's tests are not
    # run by testing the module that depends on it. `+` between them, or mill hands the second
    # name to the first task as an argument and runs only the first.
    mill -j 4 --ticker false engines.common.test + "engines.$engine.test"
  fi

  step "Building the engine jar"
  mill -j 4 --ticker false "engines.$engine.assembly"
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

plan_file=$(mktemp "${TMPDIR:-/tmp}/$engine-$env-XXXXXX.tfplan")
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
# A deploy never creates a game — a game is an administrative fact, not something a deploy does —
# so the engine is deployed but unreachable until an admin adds a game pointing at it, on
# matchmaker's admin page ("Add a Game", or the game's own edit form if it is already there). The
# values that form needs are outputs of the apply above, and this prints them. Engine identity must
# be the name matchmaker files this engine's API key under, because that key is how a deployed
# matchmaker tells which engine a callback came from; the terraform files it under the engine's
# name.
#
# register-game.sql is not the way: it is for the local database the unit tests run against, and
# it refuses any other.

output() {
  (cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -raw "$1" 2>/dev/null || true)
}

create_game_url=$(output "${engine}_create_game_url")
external_id=$(output "${engine}_external_id")
# Only a character game has one: where its players build a character.
character_url=$(output "${engine}_character_url")

step "Deployed"

if [ -z "$create_game_url" ] || [ -z "$external_id" ]; then
  echo "    the engine's outputs are not available; check the apply above" >&2
else
  cat <<EOF
Add it on matchmaker's admin page ("Add a Game"), or edit the game if it is already there — a game
pointing at an old url goes on calling it:

    Name                  $game_name
    Game engine url       $create_game_url
    Engine identity       $external_id
EOF
  if [ -n "$character_url" ]; then
    cat <<EOF
    Requires characters   yes
    Character page url    $character_url
EOF
  fi
  cat <<EOF

The roles and parameters to give it are in engines/$engine/README.md.
EOF
fi
