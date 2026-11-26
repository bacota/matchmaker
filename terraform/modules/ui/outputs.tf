output "url" {
  description = "Where the UI is served. This must be a callback URL on the user pool, trailing slash included."
  value       = "https://${local.host}/"
}

output "origin" {
  description = "The same, as an origin: no path and no trailing slash. This is what belongs in cors_allowed_origins."
  value       = "https://${local.host}"
}

output "site_urls" {
  description = "Where each game site is served, as callback URLs on the user pool: trailing slash included."
  value       = [for d in keys(var.sites) : "https://${d}/"]
}

output "site_origins" {
  description = "Each game site as an origin, for cors_allowed_origins."
  value       = [for d in keys(var.sites) : "https://${d}"]
}

output "bucket" {
  description = "Bucket holding the built UI."
  value       = aws_s3_bucket.ui.id
}

output "distribution_id" {
  description = "For `aws cloudfront create-invalidation` after a deploy."
  value       = aws_cloudfront_distribution.ui.id
}

output "distribution_domain_name" {
  description = <<-EOT
    The generated *.cloudfront.net name, which stays reachable even with a custom domain in front.
    Useful for checking whether a problem is DNS or the distribution itself.
  EOT
  value       = aws_cloudfront_distribution.ui.domain_name
}
