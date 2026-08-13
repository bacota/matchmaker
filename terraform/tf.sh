#!/usr/bin/env bash -x
#
# Runs terraform against one environment, from the single root configuration.
#
#   ./tf.sh dev plan
#   ./tf.sh prod apply
#   ./tf.sh dev output -raw api_endpoint
#
# One root serving several environments has exactly one sharp edge: the chosen backend is
# remembered in .terraform, so a plain `terraform apply` after working on another environment
# would target the wrong state, with no warning and a plan that looks plausible. This script
# removes that edge by re-initialising the backend on every run and always passing the matching
# var file. Use it rather than terraform directly.

set -euo pipefail

cd "$(dirname "$0")"

if [ $# -lt 2 ]; then
  echo "usage: $0 <dev|prod> <terraform command> [args...]" >&2
  exit 2
fi

env=$1
shift

case "$env" in
  dev | prod) ;;
  *)
    echo "unknown environment '$env'; expected dev or prod" >&2
    exit 2
    ;;
esac

backend="environments/$env.backend.hcl"
vars="environments/$env.tfvars"          # account facts, gitignored
settings="environments/$env.settings.tfvars" # policy, committed

for required in "$backend" "$vars" "$settings"; do
  if [ ! -f "$required" ]; then
    echo "missing $required" >&2
    [ "$required" = "$vars" ] && echo "copy $vars.example to $vars and fill it in" >&2
    exit 1
  fi
done

# -reconfigure rather than -migrate-state: the intent is always "point at this environment", never
# "move what is in the other environment's state into this one".
terraform init -reconfigure -backend-config="$backend" -input=false >/dev/null

command=$1
shift

# Commands that read or change infrastructure need the variables; the rest (fmt, validate,
# providers) either reject -var-file or do not need it.
# Applying a saved plan is the one case that must not pass -var-file: the plan already fixes every
# value, and terraform rejects the combination rather than ignoring it. Detected by looking for an
# existing file among the arguments, which is what a saved plan is.
if [ "$command" = apply ]; then
  for arg in "$@"; do
    if [ -f "$arg" ]; then
      exec terraform apply "$@"
    fi
  done
fi

case "$command" in
  plan | apply | destroy | refresh | import | console)
    # Settings first, so a value in the environment's own tfvars can override the committed policy
    # for a one-off — later -var-file wins.
    exec terraform "$command" -var-file="$settings" -var-file="$vars" "$@"
    ;;
  *)
    exec terraform "$command" "$@"
    ;;
esac
