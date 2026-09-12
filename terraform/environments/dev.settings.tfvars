// Dev policy. Committed on purpose: unlike dev.tfvars, which names real infrastructure, these are
// decisions, and a change to one should be visible in a diff.

// Small and cheap. Everything here is disposable.
lambda_memory_mb   = 2048
log_retention_days = 7

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

// Off: advanced security is billed per monthly active user, and there is nothing in dev worth
// protecting. Set to AUDIT to see the risk findings without paying for enforcement decisions.
advanced_security_mode = "OFF"

// Long sessions, so testing is not interrupted by signing in again.
refresh_token_validity_days = 30

// The two bundled engines: test fixtures for the game interaction. Independent of each other —
// tic-tac-toe is a game of alternating turns, rps one where both players move at once.
deploy_tictactoe = true
deploy_rps       = true
