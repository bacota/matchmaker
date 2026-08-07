// Dev policy. Committed on purpose: unlike dev.tfvars, which names real infrastructure, these are
// decisions, and a change to one should be visible in a diff.

// Small and cheap. Everything here is disposable.
lambda_memory_mb   = 1024
log_retention_days = 7

// Off: advanced security is billed per monthly active user, and there is nothing in dev worth
// protecting. Set to AUDIT to see the risk findings without paying for enforcement decisions.
advanced_security_mode = "OFF"

// Long sessions, so testing is not interrupted by signing in again.
refresh_token_validity_days = 30
