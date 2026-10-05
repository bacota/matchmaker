#!/usr/bin/env bash
#
# Deploys matchmaker and the bundled game engines in one apply.
#
# The mailer, when the environment enables it, is built and applied by deploy.sh as part of
# matchmaker itself — it is matchmaker's second function, not a third system — so there is
# nothing about it here.
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
# The result is one plan and one apply covering all of them, which is the reason to use this
# rather than deploy.sh followed by the per-engine scripts: those target their own module, so each
# is a separate apply and the shared Cognito app client is written once per engine. Use the
# per-engine scripts when only an engine changed — they are much faster and touch far less.
#
# Which engines are deployed is not this script's decision. It is deploy_tictactoe, deploy_rps,
# deploy_boxing and deploy_stratego in environments/<env>.settings.tfvars, the same flags terraform reads; an engine
# that is off there is skipped here, and said so.
#
# Which of them are *rebuilt* is, and it is the last field of ENGINES below. An engine marked `keep`
# stays deployed but is not tested or rebuilt: terraform is handed the jar already in out/, whose
# hash is the deployed function's, so the apply leaves it as it is. Tic-tac-toe and rock-paper-
# scissors are kept for now. To change one of them deliberately, run its own deploy script
# (deploy-tictactoe.sh, deploy-rps.sh), which builds and applies it on purpose.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

# module name : mill module : jar path : settings flag : build or keep, for each bundled engine.
readonly ENGINES=(
  "tictactoe:engines.tictactoe:out/engines/tictactoe/assembly.dest/out.jar:deploy_tictactoe:keep"
  "rps:engines.rps:out/engines/rps/assembly.dest/out.jar:deploy_rps:keep"
  "boxing:engines.boxing:out/engines/boxing/assembly.dest/out.jar:deploy_boxing:build"
  "stratego:engines.stratego:out/engines/stratego/assembly.dest/out.jar:deploy_stratego:build"
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

enabled_engines=()  # deployed: built, or kept
built_engines=()    # deployed, and tested and rebuilt on the way
kept_engines=()     # deployed from the jar already there
disabled_engines=()

for engine in "${ENGINES[@]}"; do
  IFS=: read -r name _ _ flag rebuild <<<"$engine"
  if grep -Eq "^[[:space:]]*${flag}[[:space:]]*=[[:space:]]*true" "$settings" 2>/dev/null; then
    enabled_engines+=("$engine")
    if [ "$rebuild" = keep ]; then
      kept_engines+=("$engine")
    else
      built_engines+=("$engine")
    fi
  else
    disabled_engines+=("$name:$flag")
  fi
done

step "Deploying to $env"
echo "    matchmaker    api, ui, database"

if [ ${#enabled_engines[@]} -eq 0 ]; then
  echo "    engines       none enabled in $settings"
else
  for engine in "${built_engines[@]+"${built_engines[@]}"}"; do
    IFS=: read -r name _ _ _ _ <<<"$engine"
    echo "    engine        $name"
  done
  for engine in "${kept_engines[@]+"${kept_engines[@]}"}"; do
    IFS=: read -r name _ jar _ _ <<<"$engine"
    echo "    keeping       $name (not rebuilt; deployed as it is, from $jar)"
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

# A kept engine's jar has to be there whatever else happens: terraform reads it, and it is what
# says the deployed function is unchanged. Checked before anything is built, so that a missing one
# stops the deploy at once rather than halfway through a plan. Building it again would deploy the
# engine's current code, which is what keeping it is to avoid -- so that is left to its own script.
for engine in "${kept_engines[@]+"${kept_engines[@]}"}"; do
  IFS=: read -r name module jar _ _ <<<"$engine"
  if [ ! -f "$jar" ]; then
    echo "no $name jar at $jar; it is kept rather than rebuilt, so this deploy has nothing to give terraform" >&2
    echo "deploy it on purpose with ./deploy-$name.sh $env (which builds its current code), or turn it off in $settings" >&2
    exit 1
  fi
done

if [ "$skip_build" = true ]; then
  step "Skipping the engine builds"
  for engine in "${built_engines[@]+"${built_engines[@]}"}"; do
    IFS=: read -r name _ jar _ _ <<<"$engine"
    if [ ! -f "$jar" ]; then
      echo "no $name jar at $jar; run without --skip-build" >&2
      exit 1
    fi
  done
elif [ ${#built_engines[@]} -gt 0 ]; then
  if [ "$skip_tests" != true ]; then
    step "Testing the engines"
    # engines.common first, since every jar carries it and testing an engine does not run a
    # dependency's tests. Then each engine, joined by `+`: without it mill passes every name after
    # the first to the first task as an argument, and only that one suite runs.
    mill_tasks=(engines.common.test)
    for engine in "${built_engines[@]}"; do
      IFS=: read -r _ module _ _ _ <<<"$engine"
      mill_tasks+=(+ "$module.test")
    done
    mill -j 4 --ticker false "${mill_tasks[@]}"
  fi

  step "Building the engine jars"
  for engine in "${built_engines[@]}"; do
    IFS=: read -r _ module _ _ _ <<<"$engine"
    mill -j 4 --ticker false "$module.assembly"
  done

  for engine in "${built_engines[@]}"; do
    IFS=: read -r name _ jar _ _ <<<"$engine"
    echo "    $name $jar ($(du -h "$jar" | cut -f1))"
  done
fi

# ---------------------------------------------------------------------------
# The deployment itself
# ---------------------------------------------------------------------------
#
# deploy.sh does the rest, and does it for all of them at once: it compiles, builds matchmaker's own
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

# Every output in one call, read from that rather than asked for one at a time: each tf.sh run
# re-initialises the backend and downloads the state, several seconds apiece, and there are three
# outputs per engine. The JSON holds the API keys too, so it stays in this variable and is never
# printed. An apply that left no outputs gives "{}", and every value read from it is then empty.
outputs=$(cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -json 2>/dev/null || true)
[ -n "$outputs" ] || outputs="{}"

output() {
  jq -r --arg name "$1" '.[$name].value // empty' <<<"$outputs" 2>/dev/null || true
}

step "Games to add on matchmaker's admin page"

# A deploy never creates a game: an admin adds each one on matchmaker's admin page ("Add a Game"),
# or edits the game if it is already there, with these values. register-game.sql is for the local
# database the unit tests run against, and refuses any other. Each API key is printed as the command
# that reads it, not as the key, so that it is not left in a terminal's scrollback.
for engine in "${enabled_engines[@]}"; do
  IFS=: read -r name _ _ _ _ <<<"$engine"

  create_game_url=$(output "${name}_create_game_url")
  external_id=$(output "${name}_external_id")
  character_url=$(output "${name}_character_url")

  if [ -z "$create_game_url" ] || [ -z "$external_id" ]; then
    echo "    $name: outputs not available; check the apply above" >&2
    continue
  fi

  cat <<EOF

    $name
      Game engine url       $create_game_url
      Engine identity       $external_id
      API key               ./terraform/tf.sh $env output -raw ${name}_api_key
EOF
  if [ -n "$character_url" ]; then
    cat <<EOF
      Requires characters   yes
      Character page url    $character_url
EOF
  fi
done

cat <<'EOF'

A game already there that points at an old url goes on calling it, so edit it rather than adding
a second. The roles and parameters each game needs are in its engine's README.
EOF
