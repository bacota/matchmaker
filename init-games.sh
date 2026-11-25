#!/usr/bin/env bash
#
# Registers every bundled game engine as a game in one matchmaker instance.
#
#   ./init-games.sh local          # the local database and the engines' LocalServers
#   ./init-games.sh dev            # dev's database and its deployed engines
#   ./init-games.sh prod --yes     # skip the confirmation
#
# Each engine's game is defined once, in engines/<engine>/game.json: its name, roles, parameters,
# how its players move. What differs between instances is supplied here, per environment:
#
#   local  url http://localhost:<port>/games, the port each engine's LocalServer listens on;
#          engine identity <engine>-dev, the GAME_EXTERNAL_ID those servers are started with; no
#          API key, since a local engine asks for none. The database is the local `matchmaker`,
#          or DB_NAME if set.
#   dev,   url, identity, API key and (for boxing) character page from terraform's outputs, for
#   prod   every engine the environment deploys; the database from
#          terraform/environments/<env>.tfvars and <env>.secrets.tfvars, as update-engine-urls.sh
#          reads it. Like Flyway, this connects from here, so it needs a route to that database.
#
# The games are saved by RegisterGames, through the same service the admin page's "Add a Game"
# uses: checked the same way, saved with their API key, and offered every tournament model they
# can play. A database with no tournament models stored has them synced first; one that has some
# is left alone. The saves are made as an admin player: terraform's admin_external_id in a deployed
# environment, and locally ADMIN_EXTERNAL_ID or else the first admin player in the database.
#
# Safe to run again. A game whose engine identity is already registered is skipped, never saved a
# second time; to point registered games at moved urls, use update-engine-urls.sh.
#
# The API keys pass from terraform to RegisterGames in a file only its owner can read, deleted on
# exit, and are never printed.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

# engine : local port, in the order their LocalServers were given ports.
readonly ENGINES=(tictactoe:8090 rps:8091 boxing:8092 stratego:8093 wavinghands:8094 hilo:8095)

usage() {
  cat >&2 <<EOF
usage: $0 <local|dev|prod> [--yes]

  --yes   register without asking for confirmation
EOF
  exit 2
}

[ $# -ge 1 ] || usage
env=$1
shift

case "$env" in
  local | dev | prod) ;;
  *)
    echo "unknown environment '$env'; expected local, dev or prod" >&2
    exit 2
    ;;
esac

assume_yes=false
while [ $# -gt 0 ]; do
  case "$1" in
    --yes | -y) assume_yes=true ;;
    *) usage ;;
  esac
  shift
done

for tool in jq psql mill; do
  command -v "$tool" >/dev/null || {
    echo "$tool is required" >&2
    exit 1
  }
done

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# Reads one `key = "value"` from a tfvars file, as deploy.sh does.
tfvar() {
  local key=$1 file=$2
  [ -f "$file" ] || return 0
  sed -n \
    -e 's|//.*$||' \
    -e "s|^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\"\(.*\)\"[[:space:]]*$|\1|p" \
    "$file" | tail -n 1
}

umask 077
manifest=$(mktemp "${TMPDIR:-/tmp}/init-games-$env-XXXXXX.json")
trap 'rm -f "$manifest"' EXIT

# One manifest entry: definition, url, identity, then character url and API key, either of which
# may be empty. jq quotes every value, the key included, without it passing through the shell's
# output. The definition's path is absolute, since runMain runs in mill's directory, not this one.
entry() {
  jq -n --arg definition "$PWD/engines/$1/game.json" --arg url "$2" --arg externalId "$3" \
    --arg characterUrl "$4" --arg apiKey "$5" \
    '{definition: $definition, url: $url, externalId: $externalId}
     + (if $characterUrl == "" then {} else {characterUrl: $characterUrl} end)
     + (if $apiKey == "" then {} else {apiKey: $apiKey} end)'
}

entries=()
summary=()

if [ "$env" = local ]; then
  # -------------------------------------------------------------------------
  # Local: the engines' LocalServers, and the local database
  # -------------------------------------------------------------------------
  host=localhost port=5432 database=${DB_NAME:-matchmaker} user=matchmaker password=matchmaker

  for engine_port in "${ENGINES[@]}"; do
    engine=${engine_port%%:*} engine_port=${engine_port##*:}
    url="http://localhost:$engine_port/games"
    character_url=""
    [ "$engine" = boxing ] && character_url="http://localhost:$engine_port/fighters"
    entries+=("$(entry "$engine" "$url" "$engine-dev" "$character_url" "")")
    summary+=("$engine|$url|$engine-dev|${character_url:--}|none")
  done

  admin=${ADMIN_EXTERNAL_ID:-$(
    PGPASSWORD="$password" psql -X -At -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$user" -d "$database" \
      -c "SELECT external_id FROM player WHERE is_admin ORDER BY player_id LIMIT 1"
  )}
  if [ -z "$admin" ]; then
    echo "no admin player in $database; set ADMIN_EXTERNAL_ID, or make a player admin first" >&2
    exit 1
  fi
else
  # -------------------------------------------------------------------------
  # Deployed: terraform's outputs, and the environment's database
  # -------------------------------------------------------------------------
  readonly vars="$TERRAFORM_DIR/environments/$env.tfvars"
  readonly secrets="$TERRAFORM_DIR/environments/$env.secrets.tfvars"

  endpoint=$(tfvar rds_endpoint "$vars")
  database=$(tfvar db_name "$vars")
  user=$(tfvar db_user "$vars")
  password=$(tfvar db_password "$secrets")
  for setting in endpoint database user password; do
    if [ -z "${!setting}" ]; then
      echo "could not read the database $setting from $vars / $secrets" >&2
      exit 1
    fi
  done
  # rds_endpoint may or may not carry a port, as deploy.sh allows.
  case "$endpoint" in
    *:*) host=${endpoint%:*} port=${endpoint##*:} ;;
    *) host=$endpoint port=5432 ;;
  esac

  # One `output -json` rather than a call per value: each tf.sh run re-initialises the backend.
  # It holds the API keys, so it stays in this variable and is never printed.
  step "Reading $env's engines from terraform"
  outputs=$(cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -json 2>/dev/null) || {
    echo "could not read terraform's outputs for $env; has it been applied?" >&2
    exit 1
  }
  output() {
    jq -r --arg name "$1" '.[$name].value // empty' <<<"$outputs"
  }

  admin=$(output admin_external_id)
  if [ -z "$admin" ]; then
    echo "terraform has no admin_external_id for $env; deploy it with ./deploy.sh $env first" >&2
    exit 1
  fi

  for engine_port in "${ENGINES[@]}"; do
    engine=${engine_port%%:*}
    url=$(output "${engine}_create_game_url")
    external_id=$(output "${engine}_external_id")
    if [ -z "$url" ] || [ -z "$external_id" ]; then
      echo "    $engine: not deployed in $env; skipped"
      continue
    fi
    api_key=$(output "${engine}_api_key")
    character_url=$(output "${engine}_character_url")
    entries+=("$(entry "$engine" "$url" "$external_id" "$character_url" "$api_key")")
    summary+=("$engine|$url|$external_id|${character_url:--}|$([ -n "$api_key" ] && echo set || echo none)")
  done
fi

if [ ${#entries[@]} -eq 0 ]; then
  echo "no engine is deployed in $env; nothing to register"
  exit 0
fi

printf '%s\n' "${entries[@]}" | jq -s . >"$manifest"

# ---------------------------------------------------------------------------
# What would be registered
# ---------------------------------------------------------------------------

step "Engines to register in $env ($user@$host:$port/$database)"
{
  echo "engine|url|identity|character page|api key"
  printf '%s\n' "${summary[@]}"
} | column -t -s '|' | sed 's/^/    /'
echo "    Games already registered under their identity are skipped."

if [ "$env" != local ] && [ "$assume_yes" != true ]; then
  printf '\nRegister these in \033[1m%s\033[0m? [y/N] ' "$env"
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "aborted; nothing registered" >&2
      exit 1
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# Registering
# ---------------------------------------------------------------------------

step "Registering"
DB_HOST="$host" DB_PORT="$port" DB_NAME="$database" DB_USER="$user" DB_PASSWORD="$password" \
  ADMIN_EXTERNAL_ID="$admin" \
  mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.RegisterGames "$manifest"

step "Done"
if [ "$env" = local ]; then
  cat <<'EOF'
Start each engine with its identity, e.g.
  GAME_EXTERNAL_ID=boxing-dev mill -j 4 --ticker false engines.boxing.runMain com.vivi.boxing.LocalServer
EOF
fi
