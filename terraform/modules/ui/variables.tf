variable "environment" {
  description = "Environment name, used to prefix the bucket and distribution."
  type        = string
}

variable "bucket_name" {
  description = <<-EOT
    Name of the bucket holding the built UI. S3 bucket names are global, so an environment name is
    not enough to guarantee one is free.

    Empty means "matchmaker-<environment>-ui". Changing this on an existing deployment replaces the
    bucket: terraform creates the new one, uploads the four objects, repoints the distribution and
    destroys the old one.
  EOT
  type        = string
  default     = ""

  validation {
    # Checked here rather than at apply time, where the failure is a generic InvalidBucketName.
    # Only the subset that is also a valid virtual-host name is allowed: dots would break the
    # regional domain name the distribution uses as its origin.
    condition     = var.bucket_name == "" || can(regex("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$", var.bucket_name))
    error_message = "Must be 3-63 lowercase letters, digits and hyphens, starting and ending with a letter or digit, and without dots."
  }
}

variable "ui_dir" {
  description = "Directory holding index.html and app.css, i.e. matchmaker/ui."
  type        = string
}

variable "main_js_path" {
  description = "Linked JavaScript. `mill matchmaker.ui.fullLinkJS` for a deployment; fastLinkJS output is far larger and unminified."
  type        = string
}

variable "api_endpoint" {
  description = "Base URL of the HTTP API, written into the generated config.js."
  type        = string
}

variable "hosted_login_url" {
  description = "Base URL of the Cognito hosted UI, written into the generated config.js."
  type        = string
}

variable "user_pool_client_id" {
  description = "App client id, written into the generated config.js. Public: it appears in every authorize URL."
  type        = string
}

variable "price_class" {
  description = <<-EOT
    CloudFront price class, which decides which edge locations serve the site.

    PriceClass_All uses every region, so players outside North America and Europe are not served
    from another continent. PriceClass_200 drops the most expensive regions and PriceClass_100 is
    North America and Europe only — cheaper, and slower for everyone else.
  EOT
  type        = string
  default     = "PriceClass_All"

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.price_class)
    error_message = "Must be PriceClass_100, PriceClass_200 or PriceClass_All."
  }
}

# ---------------------------------------------------------------------------
# Custom domain
# ---------------------------------------------------------------------------
#
# All optional, and all three move together: without a domain the site keeps its generated
# *.cloudfront.net name and nothing is written to Route 53.

variable "domain_name" {
  description = <<-EOT
    Domain the UI is served from, e.g. "matchmaker.example.com". Empty means no custom domain: the
    distribution keeps its generated *.cloudfront.net name, and neither certificate_arn nor
    hosted_zone_id is consulted.

    Setting this changes the module's `url` and `origin` outputs, which are what the user pool's
    callback URLs and the API's CORS origins are built from — so the first apply after adding a
    domain also updates Cognito and the gateway.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.domain_name == "" || can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", var.domain_name))
    error_message = "Must be a lowercase fully-qualified domain name with at least two labels, and no trailing dot."
  }
}

variable "hosted_zone_id" {
  description = <<-EOT
    Zone id of the existing Route 53 public hosted zone that `domain_name` belongs to, e.g.
    "Z1234567890ABC". Required when domain_name is set.

    The zone is referenced, never created: it usually holds records this configuration knows
    nothing about, and destroying it would take them with it.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.hosted_zone_id == "" || can(regex("^Z[A-Z0-9]+$", var.hosted_zone_id))
    error_message = "Must be a Route 53 hosted zone id: a Z followed by uppercase letters and digits."
  }
}

variable "certificate_arn" {
  description = <<-EOT
    ARN of an existing ACM certificate covering `domain_name`. Required when domain_name is set.

    Managed outside this configuration, like the hosted zone and the database secret: a certificate
    is usually shared by more than one distribution, and issuing one here would mean this
    configuration could also destroy it.

    It must be in us-east-1 and already ISSUED. Both are enforced by CloudFront rather than
    negotiable — the region because that is the only one it reads certificates from, whatever
    region the rest of the stack runs in.
  EOT
  type        = string
  default     = ""

  validation {
    # The region is checked here because it is readable straight off the arn, and because getting
    # it wrong otherwise surfaces at apply time as InvalidViewerCertificate, which does not say
    # that the region is the problem.
    condition     = var.certificate_arn == "" || can(regex("^arn:aws[a-z-]*:acm:us-east-1:[0-9]{12}:certificate/", var.certificate_arn))
    error_message = "Must be an ACM certificate ARN in us-east-1 — the only region CloudFront reads certificates from."
  }
}
