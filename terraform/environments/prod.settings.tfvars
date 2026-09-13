// Prod policy. Committed on purpose — see dev.settings.tfvars.

// More memory buys proportionally more CPU, which is what shortens the JVM cold start. Logs are
// kept a quarter rather than a week.
lambda_memory_mb   = 2048
log_retention_days = 90

// On.
//
// It was once off, and the history is worth keeping. SnapStart and SigV4 signing could not both
// work: the snapshot is taken during init at *publish* time, ahead of any invocation, and Java
// fixes System.getenv at JVM start — so a restored process saw an environment with no
// execution-role credentials (those are injected per execution environment) and signed nothing,
// which the game engine answered with a 403.
//
// Both signed calls matchmaker used to make are gone. Engine calls carry a shared API key, an
// ordinary variable set on the function itself and therefore present in the snapshot. The one that
// came back — putting a notification on the mail queue — now goes through the AWS SDK's client,
// whose credential provider is built to survive a restore, rather than through a hand-signed POST
// reading a frozen environment. That is the only reason there is an SDK client in this codebase;
// see com.vivi.matchmaker.notify.SqsNotifier.
//
// Worth verifying rather than assuming, the first time an environment runs with deploy_mail and
// this both on: start a match and check that something reaches the queue. A failed enqueue does not
// fail a start — it only logs, from GameEngineService.
lambda_snap_start = true

// Real accounts: block sign-ins using credentials known to be compromised, and challenge risky
// ones. Billed per monthly active user.
advanced_security_mode = "ENFORCED"

// Shorter than dev: a stolen refresh token is worth something here.
refresh_token_validity_days = 7
