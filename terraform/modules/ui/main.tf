terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.50"
    }
  }
}

locals {
  name = "matchmaker-${var.environment}-ui"

  bucket = var.bucket_name != "" ? var.bucket_name : local.name

  custom_domain = var.domain_name != ""

  # Everything downstream keys off this rather than off the distribution's generated name, so
  # adding a domain moves the callback URLs, the CORS origins and config.js in one apply.
  host = local.custom_domain ? var.domain_name : aws_cloudfront_distribution.ui.domain_name
}

# ---------------------------------------------------------------------------
# Bucket
# ---------------------------------------------------------------------------
#
# Private, and reachable only through CloudFront. Not an S3 website endpoint: those serve plain
# http, and `crypto.subtle` — which PKCE needs to hash the code verifier — is unavailable outside
# a secure context. Hosted login would fail on the deployed site while working locally, since
# localhost counts as secure.

resource "aws_s3_bucket" "ui" {
  bucket = local.bucket
}

resource "aws_s3_bucket_public_access_block" "ui" {
  bucket = aws_s3_bucket.ui.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "ui" {
  bucket = aws_s3_bucket.ui.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ui" {
  bucket = aws_s3_bucket.ui.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ---------------------------------------------------------------------------
# Origin access
# ---------------------------------------------------------------------------

resource "aws_cloudfront_origin_access_control" "ui" {
  name                              = local.name
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ---------------------------------------------------------------------------
# Distribution
# ---------------------------------------------------------------------------

resource "aws_cloudfront_distribution" "ui" {
  enabled             = true
  comment             = local.name
  default_root_object = "index.html"
  price_class         = var.price_class

  # CloudFront rejects a request whose Host header is not one of these, so the alias has to exist
  # before the Route 53 record starts sending traffic to it.
  aliases = local.custom_domain ? [var.domain_name] : []

  lifecycle {
    # The three custom-domain variables are only meaningful together, and terraform 1.5 has no
    # cross-variable validation — so they are checked here, where a missing one fails the plan
    # instead of half-configuring the distribution.
    precondition {
      condition     = var.domain_name == "" || var.hosted_zone_id != ""
      error_message = "hosted_zone_id is required when domain_name is set: it is the zone the alias records are written into."
    }

    precondition {
      # Without this the distribution would come up on the default certificate while Route 53 sends
      # the custom name at it, and every visitor would get a certificate mismatch.
      condition     = var.domain_name == "" || var.certificate_arn != ""
      error_message = "certificate_arn is required when domain_name is set: CloudFront cannot serve a custom domain on the default *.cloudfront.net certificate."
    }
  }

  origin {
    domain_name              = aws_s3_bucket.ui.bucket_regional_domain_name
    origin_id                = "s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.ui.id
  }

  default_cache_behavior {
    target_origin_id = "s3"
    # The whole point of serving this over CloudFront rather than S3 directly is the certificate;
    # an http visitor is upgraded rather than served a page where PKCE cannot work.
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # CachingOptimized, AWS-managed. The API is called directly from the browser and never through
    # this distribution, so nothing dynamic is being cached here — only three static files.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    # Without a custom domain, the default *.cloudfront.net certificate. With one, the existing
    # certificate named by var.certificate_arn, which must already be ISSUED — CloudFront rejects
    # one that is still pending validation, and the failure names the arn rather than the reason.
    cloudfront_default_certificate = local.custom_domain ? null : true
    acm_certificate_arn            = local.custom_domain ? var.certificate_arn : null

    # sni-only: the alternative is a dedicated IP at a few hundred dollars a month, for the benefit
    # of clients too old to send SNI.
    ssl_support_method = local.custom_domain ? "sni-only" : null

    # Only relevant with a custom certificate; the default one pins its own policy.
    minimum_protocol_version = local.custom_domain ? "TLSv1.2_2021" : null
  }
}

# ---------------------------------------------------------------------------
# Route 53
# ---------------------------------------------------------------------------
#
# Alias records rather than CNAMEs: a CNAME cannot live at a zone apex, and an alias resolves to
# CloudFront's addresses without a second lookup and is not billed per query.
#
# Both families, because the distribution answers on IPv6 by default. An A record alone leaves
# IPv6-only clients unable to resolve the site at all.

resource "aws_route53_record" "ipv4" {
  count = local.custom_domain ? 1 : 0

  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name    = aws_cloudfront_distribution.ui.domain_name
    zone_id = aws_cloudfront_distribution.ui.hosted_zone_id

    # CloudFront has no health check to evaluate, and turning this on would make the record
    # disappear rather than fail over.
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "ipv6" {
  count = local.custom_domain ? 1 : 0

  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.ui.domain_name
    zone_id                = aws_cloudfront_distribution.ui.hosted_zone_id
    evaluate_target_health = false
  }
}

# Only this distribution may read the bucket. `AWS:SourceArn` is what enforces that: without it
# the policy would admit any CloudFront distribution in any account.
data "aws_iam_policy_document" "bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.ui.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.ui.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "ui" {
  bucket = aws_s3_bucket.ui.id
  policy = data.aws_iam_policy_document.bucket.json

  depends_on = [aws_s3_bucket_public_access_block.ui]
}

# ---------------------------------------------------------------------------
# Content
# ---------------------------------------------------------------------------
#
# `source_hash` on each object is what makes a rebuilt file show up as a change, the same way
# `source_code_hash` does for the Lambda jar. Without it terraform compares only the object's
# metadata and a changed main.js would not be uploaded.

resource "aws_s3_object" "index" {
  bucket       = aws_s3_bucket.ui.id
  key          = "index.html"
  source       = "${var.ui_dir}/index.html"
  source_hash  = filemd5("${var.ui_dir}/index.html")
  content_type = "text/html"

  # Short, because this is what points at everything else: a stale index.html would keep serving
  # a main.js that may no longer exist.
  cache_control = "public, max-age=60"
}

resource "aws_s3_object" "stylesheet" {
  bucket        = aws_s3_bucket.ui.id
  key           = "app.css"
  source        = "${var.ui_dir}/app.css"
  source_hash   = filemd5("${var.ui_dir}/app.css")
  content_type  = "text/css"
  cache_control = "public, max-age=60"
}

resource "aws_s3_object" "script" {
  bucket        = aws_s3_bucket.ui.id
  key           = "main.js"
  source        = var.main_js_path
  source_hash   = filemd5(var.main_js_path)
  content_type  = "text/javascript"
  cache_control = "public, max-age=60"
}

/* The one file that differs between environments, generated rather than edited.
 *
 * This is what removes the "paste three values into index.html" step: the values come from the
 * API module's own outputs, so they cannot drift from the pool and gateway actually deployed.
 * None of them are secret — the client id is a query parameter of every authorize URL.
 */
resource "aws_s3_object" "config" {
  bucket = aws_s3_bucket.ui.id
  key    = "config.js"

  content = <<-EOT
    // Generated by terraform. Do not edit: `${local.name}` overwrites it on every apply.
    window.matchmakerConfig = ${jsonencode({
  authMode       = "cognito"
  apiEndpoint    = var.api_endpoint
  hostedLoginUrl = var.hosted_login_url
  clientId       = var.user_pool_client_id
})};
  EOT

content_type = "text/javascript"

# Never cached. It is tiny, and a browser holding an old client id after a pool is recreated
# fails at sign-in with an error that says nothing useful.
cache_control = "no-store"
}
