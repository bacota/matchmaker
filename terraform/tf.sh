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
# var files. Use it rather than terraform directly.
#
# Three var files per environment, layered in this order:
#
#   <env>.settings.tfvars   policy: memory, retention, session length            committed
#   <env>.tfvars            account facts: endpoints, subnets, user names        committed
#   <env>.secrets.tfvars    credentials                                          gitignored
#
# Later files win, so a secret can override anything, and the first two can be read in a diff.

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
settings="environments/$env.settings.tfvars"  # policy, committed
vars="environments/$env.tfvars"               # account facts, committed
secrets="environments/$env.secrets.tfvars"    # credentials, gitignored

for required in "$backend" "$settings" "$vars" "$secrets"; do
  if [ ! -f "$required" ]; then
    echo "missing $required" >&2
    if [ "$required" = "$secrets" ]; then
      # The one file that is not in the repository, so it is the one a fresh clone is missing.
      echo "copy $secrets.example to $secrets and fill in the database password" >&2
    fi
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
    # Later -var-file wins, so the order is least to most specific: policy, then account facts,
    # then credentials.
    exec terraform "$command" \
      -var-file="$settings" \
      -var-file="$vars" \
      -var-file="$secrets" \
      "$@"
    ;;
  *)
    exec terraform "$command" "$@"
    ;;
esac
