#!/usr/bin/env bash
#
# Builds and deploys the mailer — the queue and the function that drains it into SES — without
# redeploying matchmaker.
#
#   ./deploy-mailer.sh dev
#   ./deploy-mailer.sh dev --yes         # skip the confirmation between plan and apply
#   ./deploy-mailer.sh dev --full        # plan the whole configuration, not just the mailer
#   ./deploy-mailer.sh dev --skip-build  # the jar must already exist
#
# Separate from deploy.sh for the same reason the engine scripts are: the mailer has its own
# lifecycle. It is not in the VPC, it has no database and no UI, and changing what a notification
# says is a jar and nothing else. Nothing here needs a route into the VPC, so this runs from
# anywhere with AWS credentials.
#
# The plan is limited to the mail module plus the API function, because the API is the other half:
# it is what enqueues, and its MAIL_QUEUE_URL and grant come from the queue this creates. Use
# --full when the whole environment should be planned together.

set -euo pipefail

cd "$(dirname "$0")"

readonly TERRAFORM_DIR="terraform"

# The mail module, and the two things outside it that deploying the mailer changes: the API
# function's environment (MAIL_QUEUE_URL) and its permission to send to the queue.
readonly TARGETS=(
  -target=module.mail
  -target=module.api.aws_lambda_function.api
  -target=module.api.aws_iam_role_policy.mail_queue
)

usage() {
  cat >&2 <<EOF
usage: $0 <dev|prod> [--yes] [--full] [--skip-build] [--skip-tests]

  --yes         apply without asking for confirmation
  --full        plan the whole configuration instead of just the mailer; needs
                matchmaker's own artifacts to have been built (see ./deploy.sh)
  --skip-build  do not compile or rebuild the jar (it must already exist)
  --skip-tests  build the jar without running the mailer's tests first
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
readonly jar="out/matchmaker/mailer/assembly.dest/out.jar"

step() {
  printf '\n\033[1m==> %s\033[0m\n' "$*"
}

# ---------------------------------------------------------------------------
# Is mail even enabled here?
# ---------------------------------------------------------------------------
#
# deploy_mail defaults to false, so without it this would plan, apply and report success having
# created nothing at all. Checked before the build, so the answer comes back in a second.

if ! grep -Eq '^[[:space:]]*deploy_mail[[:space:]]*=[[:space:]]*true' "$settings" 2>/dev/null; then
  echo "mail notifications are not enabled for '$env'" >&2
  echo >&2
  echo "add this to $settings:" >&2
  echo >&2
  echo '    // Notifications: the mail queue and the function that drains it into SES.' >&2
  echo '    deploy_mail = true' >&2
  echo >&2
  echo "and see the deploy_mail variable in $TERRAFORM_DIR/variables.tf for what has to be" >&2
  echo "in place first: SQS reachable from the API's subnets, SES out of the sandbox, and a" >&2
  echo "verified sender." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
#
# The mailer's tests need nothing but a JVM — no Postgres, no AWS — so they run on the way to
# every deploy. ProtocolSpec is the one worth having here: it fails when matchmaker and this
# function have stopped agreeing on what a queued mail looks like, which would otherwise show up
# as every message going round the queue three times and dying in the dead-letter queue.

if [ "$skip_build" = true ]; then
  step "Skipping build"
  if [ ! -f "$jar" ]; then
    echo "no jar at $jar; run without --skip-build" >&2
    exit 1
  fi
else
  if [ "$skip_tests" != true ]; then
    step "Testing the mailer"
    mill -j 4 --ticker false matchmaker.mailer.test
  fi

  step "Building the mailer jar"
  mill -j 4 --ticker false matchmaker.mailer.assembly
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

plan_file=$(mktemp "${TMPDIR:-/tmp}/mailer-$env-XXXXXX.tfplan")
trap 'rm -f "$plan_file"' EXIT

step "Planning $env${plan_args:+ (mailer only)}"
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
# Where to look when nothing arrives
# ---------------------------------------------------------------------------
#
# Mail fails quietly by design — a start succeeds whatever the queue does — so the two places that
# hold the evidence are worth printing rather than remembering.

output() {
  (cd "$TERRAFORM_DIR" && ./tf.sh "$env" output -raw "$1" 2>/dev/null || true)
}

step "Deployed"

dlq=$(output mail_dead_letter_queue_url)

cat <<EOF
    logs          /aws/lambda/matchmaker-$env-mail
    undelivered   ${dlq:-(output not available; check the apply above)}

Nothing arriving? In order of likelihood:

  - SES is still in the sandbox, so only verified addresses can be delivered to. Undeliverable
    mail lands in the dead-letter queue above.
  - the API function cannot reach SQS. It is in private subnets, so that needs a NAT gateway or a
    com.amazonaws.<region>.sqs interface endpoint. The enqueue fails quietly; its log line is in
    the API's own log group.
  - the players have no address stored. Only an account that has signed in since matchmaker began
    keeping addresses has one; see V12.
EOF
