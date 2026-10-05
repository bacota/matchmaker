#!/usr/bin/env bash
#
# Points the dev environment's games at their engines' current urls.
#
#   ./update-engine-urls.sh dev
#   ./update-engine-urls.sh dev --yes   # skip the confirmation
#
# An engine's url changes when it is given a friendly name (terraform/README.md, "Engine urls"),
# and a `game` row goes on naming the old one: still answering, but not the one to hand out. This
# rewrites each deployed engine's game row to what terraform now says -- its `url`, the
# create-game endpoint, and for boxing its `character_url`, the fighters page -- found by the
# game's external_id, the identity its API key is filed under.
#
# What is written comes from terraform's outputs, never typed, and is shown beside what is there
# now before anything changes. One transaction: either every row moves or none does. A game that
# is not registered is said so and skipped; add it on matchmaker's admin page.
#
# Dev only. A deployed game is otherwise an admin's to edit on matchmaker's admin page, and prod's
# are left to that. Like flyway-dev.sh, it connects to the database from here, with the endpoint
# and credentials in terraform/environments/dev.tfvars and dev.secrets.tfvars.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"
readonly ENGINES=(tictactoe rps boxing stratego)

usage() {
  cat >&2 <<EOF
usage: $0 dev [--yes]

  --yes   update without asking for confirmation
EOF
  exit 2
}

[ $# -ge 1 ] || usage
env=$1
shift
[ "$env" = dev ] || {
  echo "this script updates dev only; a deployed game elsewhere is edited on matchmaker's admin page" >&2
  exit 2
}

assume_yes=false
while [ $# -gt 0 ]; do
  case "$1" in
    --yes | -y) assume_yes=true ;;
    *) usage ;;
  esac
  shift
done

for tool in jq psql; do
  command -v "$tool" >/dev/null || {
    echo "$tool is required" >&2
    exit 1
  }
done

readonly vars="$TERRAFORM_DIR/environments/$env.tfvars"
readonly secrets="$TERRAFORM_DIR/environments/$env.secrets.tfvars"

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

# ---------------------------------------------------------------------------
# The database
# ---------------------------------------------------------------------------

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

run_psql() {
  PGPASSWORD="$password" psql -X -q -v ON_ERROR_STOP=1 \
    -h "$host" -p "$port" -U "$user" -d "$database" "$@"
}

# ---------------------------------------------------------------------------
# What terraform says each engine's urls are
# ---------------------------------------------------------------------------
#
# One `output -json` rather than a call per value: each tf.sh run re-initialises the backend. It
# holds the API keys too, so it stays in this variable and is never printed.

step "Reading $env's engine urls from terraform"
outputs=$(cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -json 2>/dev/null) || {
  echo "could not read terraform's outputs for $env; has it been applied?" >&2
  exit 1
}

output() {
  jq -r --arg name "$1" '.[$name].value // empty' <<<"$outputs"
}

# psql variables, one set per engine, so that every value reaches SQL quoted by psql itself.
psql_vars=()
engines=()
for engine in "${ENGINES[@]}"; do
  external_id=$(output "${engine}_external_id")
  url=$(output "${engine}_create_game_url")
  if [ -z "$external_id" ] || [ -z "$url" ]; then
    echo "    $engine: not deployed in $env; skipped"
    continue
  fi
  engines+=("$engine")
  psql_vars+=(
    -v "ext_$engine=$external_id"
    -v "url_$engine=$url"
    -v "char_$engine=$(output "${engine}_character_url")"
  )
done

if [ ${#engines[@]} -eq 0 ]; then
  echo "no engine is deployed in $env; nothing to update"
  exit 0
fi

# The SQL for every engine at once: `$1` is the statement per engine, with ENGINE standing for its
# name in the psql variables.
per_engine() {
  local template=$1 engine
  for engine in "${engines[@]}"; do
    printf '%s\n' "${template//ENGINE/$engine}"
  done
}

# ---------------------------------------------------------------------------
# What would change
# ---------------------------------------------------------------------------

step "Games in $env, as they are and as they would be"

preview=$(
  per_engine "SELECT 'ENGINE' AS engine, :'ext_ENGINE' AS external_id,
         coalesce((SELECT display_name FROM game WHERE external_id = :'ext_ENGINE'), '(not registered)') AS game,
         (SELECT url FROM game WHERE external_id = :'ext_ENGINE') AS url_now,
         :'url_ENGINE' AS url_new,
         (SELECT character_url FROM game WHERE external_id = :'ext_ENGINE') AS character_url_now,
         nullif(:'char_ENGINE', '') AS character_url_new
  UNION ALL" | sed '$ s/UNION ALL$/;/'
)
run_psql "${psql_vars[@]}" -x <<<"$preview"

changes=$(
  run_psql "${psql_vars[@]}" -At <<<"SELECT count(*) FROM game g WHERE $(
    per_engine "(g.external_id = :'ext_ENGINE' AND (g.url IS DISTINCT FROM :'url_ENGINE'
       OR (:'char_ENGINE' <> '' AND g.character_url IS DISTINCT FROM :'char_ENGINE'))) OR" | sed '$ s/ OR$//'
  );"
)

if [ "$changes" = 0 ]; then
  step "Every registered game already names its engine's current urls; nothing to do"
  exit 0
fi

if [ "$assume_yes" != true ]; then
  printf '\nUpdate \033[1m%s\033[0m game row(s) in %s? [y/N] ' "$changes" "$env"
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "aborted; nothing changed" >&2
      exit 1
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# The update
# ---------------------------------------------------------------------------
#
# Only the urls. A character url is written only for an engine that has a character page (boxing);
# the others' are left as they are.

step "Updating"
run_psql "${psql_vars[@]}" <<SQL
BEGIN;
$(per_engine "UPDATE game
   SET url = :'url_ENGINE',
       character_url = coalesce(nullif(:'char_ENGINE', ''), character_url)
 WHERE external_id = :'ext_ENGINE';")
COMMIT;
SQL

step "Done"
run_psql "${psql_vars[@]}" -x <<<"$(
  per_engine "SELECT display_name AS game, external_id, url, character_url FROM game WHERE external_id = :'ext_ENGINE'
  UNION ALL" | sed '$ s/UNION ALL$/;/'
)"
