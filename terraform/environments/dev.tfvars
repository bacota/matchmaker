// Dev account facts: what already exists in AWS that this stack attaches to.
//
// Committed, so a change to what gets deployed is reviewable. No credentials here — the database
// password lives in dev.secrets.tfvars, which is gitignored. Policy that differs between
// environments (memory, retention, session length) lives in dev.settings.tfvars.

environment = "dev"
region      = "us-east-1"

rds_endpoint = "webl-without-reports.cgw5pxfqg65k.us-east-1.rds.amazonaws.com"
db_name      = "matchmaker"

// The user only; the password is in dev.secrets.tfvars.
db_user = "matchmaker"

subnet_ids         = ["subnet-0f0fe68b2e7e3b126"]
security_group_ids = ["sg-ee324e96"]

// The local UI runs against the dev pool, so hosted login and PKCE are exercised for real before
// anything is deployed. Cognito allows http only for localhost. Matched literally, trailing slash
// included, and must equal the URL the browser reports for the page.
callback_urls = ["http://localhost:5173/", "http://localhost:8080/"]
logout_urls   = ["http://localhost:5173/", "http://localhost:8080/"]

// Origins only: no path, no trailing slash.
cors_allowed_origins = ["http://localhost:5173", "http://localhost:8080"]

ui_bucket_name = "matchmaker-vivi-com"

// Dev has no custom domain: leave these empty and the UI keeps its *.cloudfront.net name, with no
// Route 53 record and no certificate needed. See prod.tfvars.example for the other case.
ui_domain_name       = "matchmaker-dev.vivi.com"
hosted_zone_id       = "Z3QWMCZNYC6R5N"
ui_certificate_arn   = "arn:aws:acm:us-east-1:181246307823:certificate/1c7403a4-0f2e-4a1a-a573-be110c9642b8"
cognito_sender_email = "matchmaker@vivi.com"
