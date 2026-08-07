// Prod policy. Committed on purpose — see dev.settings.tfvars.

// More memory buys proportionally more CPU, which is what shortens the JVM cold start. Logs are
// kept a quarter rather than a week.
lambda_memory_mb   = 2048
log_retention_days = 90

// Real accounts: block sign-ins using credentials known to be compromised, and challenge risky
// ones. Billed per monthly active user.
advanced_security_mode = "ENFORCED"

// Shorter than dev: a stolen refresh token is worth something here.
refresh_token_validity_days = 7
