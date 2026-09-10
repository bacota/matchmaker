#!/usr/bin/env bash
#
# Deploys matchmaker and both bundled game engines in one apply.
#
#   ./deploy-all.sh dev
#   ./deploy-all.sh dev --yes           # skip the confirmation between plan and apply
#   ./deploy-all.sh dev --skip-tests    # build the engine jars without testing them first
#   ./deploy-all.sh prod --skip-migrate # no Flyway, no admin player
#
# Not a third copy of deploy.sh: the engines live in the same terraform root as matchmaker, so a
# plain `./deploy.sh` already plans and applies whichever of them the environment enables. The one
# thing it does not do is *build* their jars — and terraform reads those through
# filebase64sha256, so a plan finds a stale jar or no jar at all. That gap is what this script
# closes. It builds the artifacts terraform is about to read, then hands the whole deployment to
# deploy.sh and reports what still has to be done by hand.
#
# The result is one plan and one apply covering all three, which is the reason to use this rather
# than deploy.sh followed by the two per-engine scripts: those target their own module, so each
# is a separate apply and the shared Cognito app client is written three times. Use the per-engine
# scripts when only an engine changed — they are much faster and touch far less.
#
# Which engines are deployed is not this script's decision. It is deploy_tictactoe and deploy_rps
# in environments/<env>.settings.tfvars, the same flags terraform reads; an engine that is off
# there is skipped here, and said so.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

# module name : mill module : jar path : settings flag, for each bundled engine.
readonly ENGINES=(
  "tictactoe:engines.tictactoe:out/engines/tictactoe/assembly.dest/out.jar:deploy_tictactoe"
  "rps:engines.rps:out/engines/rps/assembly.dest/out.jar:deploy_rps"
)

usage() {
  cat >&2 <<EOF
usage: $0 <dev|prod> [--yes] [--skip-build] [--skip-migrate] [--skip-tests]

  --yes           apply without asking for confirmation
  --skip-build    do not compile or rebuild anything (every artifact must already exist)
  --skip-migrate  do not touch the database: no Flyway, no admin player
  --skip-tests    build the engine jars without running the engines' tests first
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
skip_tests=false
passthrough=()

while [ $# -gt 0 ]; do
  case "$1" in
    --yes | -y)
      assume_yes=true
      passthrough+=("--yes")
      ;;
    --skip-build)
      skip_build=true
      passthrough+=("--skip-build")
      ;;
    --skip-migrate) passthrough+=("--skip-migrate") ;;
    # This one is ours: deploy.sh has no tests to skip.
    --skip-tests) skip_tests=true ;;
    *) usage ;;
  esac
  shift
done

readonly settings="$TERRAFORM_DIR/environments/$env.settings.tfvars"

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# ---------------------------------------------------------------------------
# Which engines is this environment configured for?
# ---------------------------------------------------------------------------
#
# Read from the settings file rather than from terraform, so the answer comes back before any
# build and without needing AWS credentials. It is the same file terraform is given, so the two
# cannot disagree.

enabled_engines=()
disabled_engines=()

for engine in "${ENGINES[@]}"; do
  IFS=: read -r name _ _ flag <<<"$engine"
  if grep -Eq "^[[:space:]]*${flag}[[:space:]]*=[[:space:]]*true" "$settings" 2>/dev/null; then
    enabled_engines+=("$engine")
  else
    disabled_engines+=("$name:$flag")
  fi
done

step "Deploying to $env"
echo "    matchmaker    api, ui, database"

if [ ${#enabled_engines[@]} -eq 0 ]; then
  echo "    engines       none enabled in $settings"
else
  for engine in "${enabled_engines[@]}"; do
    IFS=: read -r name _ _ _ <<<"$engine"
    echo "    engine        $name"
  done
fi

for engine in "${disabled_engines[@]}"; do
  IFS=: read -r name flag <<<"$engine"
  echo "    skipping      $name ($flag is not true in $settings)"
done

# The engines are test fixtures with an unauthenticated public board, so deploying them to prod is
# asked about rather than refused — the same question deploy-tictactoe.sh and deploy-rps.sh ask,
# and it is asked here for the same reason. Matchmaker itself is deployed either way.
if [ "$env" = "prod" ] && [ ${#enabled_engines[@]} -gt 0 ] && [ "$assume_yes" != true ]; then
  printf '\n\033[1mThe bundled engines are test fixtures; deploy them to prod anyway? [y/N] \033[0m'
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "aborted; nothing built or applied" >&2
      echo "turn the engines off in $settings, or deploy matchmaker alone with ./deploy.sh $env" >&2
      exit 1
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# Build the engine jars
# ---------------------------------------------------------------------------
#
# Before deploy.sh rather than inside it, because terraform reads every enabled engine's jar while
# planning: a jar built afterwards would be a jar the plan never saw.
#
# The engines' tests need nothing but a JVM — no Postgres, no AWS — so they run on the way to
# every deploy rather than being someone's separate step. ProtocolSpec is the one worth having:
# it fails when an engine and matchmaker have stopped agreeing on the wire format, which is
# exactly the mistake a deploy would otherwise ship.

if [ "$skip_build" = true ]; then
  step "Skipping the engine builds"
  for engine in "${enabled_engines[@]}"; do
    IFS=: read -r name _ jar _ <<<"$engine"
    if [ ! -f "$jar" ]; then
      echo "no $name jar at $jar; run without --skip-build" >&2
      exit 1
    fi
  done
elif [ ${#enabled_engines[@]} -gt 0 ]; then
  if [ "$skip_tests" != true ]; then
    step "Testing the engines"
    mill_modules=()
    for engine in "${enabled_engines[@]}"; do
      IFS=: read -r _ module _ _ <<<"$engine"
      mill_modules+=("$module.test")
    done
    mill -j 4 --ticker false "${mill_modules[@]}"
  fi

  step "Building the engine jars"
  for engine in "${enabled_engines[@]}"; do
    IFS=: read -r _ module _ _ <<<"$engine"
    mill -j 4 --ticker false "$module.assembly"
  done

  for engine in "${enabled_engines[@]}"; do
    IFS=: read -r name _ jar _ <<<"$engine"
    echo "    $name $jar ($(du -h "$jar" | cut -f1))"
  done
fi

# ---------------------------------------------------------------------------
# The deployment itself
# ---------------------------------------------------------------------------
#
# deploy.sh does the rest, and does it for all three at once: it compiles, builds matchmaker's own
# two artifacts, migrates, then plans and applies the whole configuration — which now includes the
# engine modules, since their jars are in place. One plan, one apply, one confirmation.

step "Handing over to deploy.sh"
./deploy.sh "$env" ${passthrough[@]+"${passthrough[@]}"}

# ---------------------------------------------------------------------------
# What still has to be done by hand
# ---------------------------------------------------------------------------
#
# Matchmaker has no route that creates a game — a game is an administrative fact, not something a
# player does — so an engine is deployed but unreachable until a `game` row points at it. The two
# values that row needs are outputs of the apply above, and external_id must be the name
# matchmaker files that engine's API key under, since the key is how a deployed matchmaker tells
# which engine a callback came from.
#
# The database is inside the VPC, so this cannot run the inserts itself from an arbitrary laptop —
# the same limitation deploy.sh notes around Flyway. It prints the exact commands instead.

if [ ${#enabled_engines[@]} -eq 0 ]; then
  exit 0
fi

output() {
  (cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -raw "$1" 2>/dev/null || true)
}

step "Registering the engines as games"

for engine in "${enabled_engines[@]}"; do
  IFS=: read -r name _ _ _ <<<"$engine"

  create_game_url=$(output "${name}_create_game_url")
  external_id=$(output "${name}_external_id")

  if [ -z "$create_game_url" ] || [ -z "$external_id" ]; then
    echo "    $name: outputs not available; check the apply above" >&2
    continue
  fi

  cat <<EOF

    $name
      create game   $create_game_url
      identity      $external_id

    psql "\$DATABASE_URL" \\
      -v url="$create_game_url" \\
      -v external_id="$external_id" \\
      -f engines/$name/register-game.sql
EOF
done

cat <<'EOF'

Already registered? Update the existing rows instead, or matchmaker will keep calling the old
urls — each engine's README names the row its script inserts.
EOF
