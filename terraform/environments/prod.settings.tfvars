// Prod policy. Committed on purpose — see dev.settings.tfvars.

// More memory buys proportionally more CPU, which is what shortens the JVM cold start. Logs are
// kept a quarter rather than a week.
lambda_memory_mb   = 2048
log_retention_days = 90

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

// Real accounts: block sign-ins using credentials known to be compromised, and challenge risky
// ones. Billed per monthly active user.
advanced_security_mode = "ENFORCED"

// Shorter than dev: a stolen refresh token is worth something here.
refresh_token_validity_days = 7
