// Prod policy. Committed on purpose — see dev.settings.tfvars.

// More memory buys proportionally more CPU, which is what shortens the JVM cold start. Logs are
// kept a quarter rather than a week.
lambda_memory_mb   = 2048
log_retention_days = 90

// Off, though the reason it was turned off has since gone away.
//
// It was turned off because SnapStart and SigV4 signing could not both work: the snapshot is taken
// during init, ahead of any invocation, and Java fixes System.getenv at JVM start, so a restored
// process saw an environment with no execution-role credentials — those are injected per execution
// environment — and the create-game call went out unsigned into a 403.
//
// Matchmaker no longer signs anything: the game engine is authenticated with a shared API key,
// which arrives in an ordinary environment variable set on the function itself. That kind of
// variable *is* in the snapshot, so the incompatibility is gone.
//
// Turning it back on is therefore a decision that can be made again, on its merits — cold-start
// latency against the other things a snapshot fixes in place — rather than one that is ruled out.
// It is left off here because nothing has re-tested a restore against this function.
lambda_snap_start = false

// Real accounts: block sign-ins using credentials known to be compromised, and challenge risky
// ones. Billed per monthly active user.
advanced_security_mode = "ENFORCED"

// Shorter than dev: a stolen refresh token is worth something here.
refresh_token_validity_days = 7
