// Dev policy. Committed on purpose: unlike dev.tfvars, which names real infrastructure, these are
// decisions, and a change to one should be visible in a diff.

// Small and cheap. Everything here is disposable.
lambda_memory_mb   = 2048
log_retention_days = 7

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
lambda_snap_start = true

// Off: advanced security is billed per monthly active user, and there is nothing in dev worth
// protecting. Set to AUDIT to see the risk findings without paying for enforcement decisions.
advanced_security_mode = "OFF"

// Long sessions, so testing is not interrupted by signing in again.
refresh_token_validity_days = 30

deploy_tictactoe = true
