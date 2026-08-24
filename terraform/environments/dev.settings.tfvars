// Dev policy. Committed on purpose: unlike dev.tfvars, which names real infrastructure, these are
// decisions, and a change to one should be visible in a diff.

// Small and cheap. Everything here is disposable.
lambda_memory_mb   = 2048
log_retention_days = 7

// Off: SnapStart and matchmaker's SigV4 signing cannot both work.
//
// The snapshot is taken during init, ahead of any invocation, and Java fixes System.getenv at JVM
// start — so after a restore the process still sees the environment the snapshot was taken in,
// which has no execution-role credentials. AwsCredentials.fromEnvironment then finds nothing, the
// create-game call goes out unsigned, and the engine's AWS_IAM route answers 403 Forbidden. There
// is no re-reading around it: a running JVM cannot see an environment change.
//
// This hid for a while because a version is not optimized the moment an apply finishes. Requests
// landing in that window run as ordinary cold starts, credentials and all, and work — which is
// why calls succeeded minutes after a deploy and failed from then on.
//
// Do not set this back to true while HttpGameEngineClient signs from environment credentials.
lambda_snap_start = false

// Off: advanced security is billed per monthly active user, and there is nothing in dev worth
// protecting. Set to AUDIT to see the risk findings without paying for enforcement decisions.
advanced_security_mode = "OFF"

// Long sessions, so testing is not interrupted by signing in again.
refresh_token_validity_days = 30

deploy_tictactoe = true
