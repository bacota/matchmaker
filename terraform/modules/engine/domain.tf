# ---------------------------------------------------------------------------
# The engine's friendly url
# ---------------------------------------------------------------------------

/* A name of the engine's own -- boxing.matchmaker-dev.vivi.com rather than a generated execute-api
 * host -- when `domain_name` is set, and nothing at all when it is not.
 *
 * Its certificate is made here rather than handed in, as the ui's is: one per engine, regional
 * (an HTTP API's custom domain reads its certificate from its own region, not us-east-1 as
 * CloudFront does), and validated through the hosted zone the name is in, so a new engine needs no
 * certificate requested by hand. The first apply waits for validation, which is usually a minute or
 * two.
 *
 * The execute-api host is left enabled: every play url matchmaker handed out before this was set
 * names it, and turning it off would break each of those matches. */

locals {
  custom_domain = var.domain_name != ""
}

resource "aws_acm_certificate" "engine" {
  count = local.custom_domain ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true

    precondition {
      condition     = var.hosted_zone_id != ""
      error_message = "hosted_zone_id is required when domain_name is set: it is where the certificate is validated and the name's records are written."
    }
  }
}

resource "aws_route53_record" "validation" {
  for_each = local.custom_domain ? {
    for option in aws_acm_certificate.engine[0].domain_validation_options : option.domain_name => option
  } : {}

  zone_id         = var.hosted_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "engine" {
  count = local.custom_domain ? 1 : 0

  certificate_arn         = aws_acm_certificate.engine[0].arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

resource "aws_apigatewayv2_domain_name" "engine" {
  count = local.custom_domain ? 1 : 0

  domain_name = var.domain_name

  domain_name_configuration {
    certificate_arn = aws_acm_certificate_validation.engine[0].certificate_arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
}

# The whole API at the root of the name: no base path, so every route is where it was on the
# execute-api host, and the urls the engine builds from BASE_URL are the same paths on either.
resource "aws_apigatewayv2_api_mapping" "engine" {
  count = local.custom_domain ? 1 : 0

  api_id      = aws_apigatewayv2_api.engine.id
  domain_name = aws_apigatewayv2_domain_name.engine[0].id
  stage       = aws_apigatewayv2_stage.default.id
}

resource "aws_route53_record" "ipv4" {
  count = local.custom_domain ? 1 : 0

  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_apigatewayv2_domain_name.engine[0].domain_name_configuration[0].target_domain_name
    zone_id                = aws_apigatewayv2_domain_name.engine[0].domain_name_configuration[0].hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "ipv6" {
  count = local.custom_domain ? 1 : 0

  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "AAAA"

  alias {
    name                   = aws_apigatewayv2_domain_name.engine[0].domain_name_configuration[0].target_domain_name
    zone_id                = aws_apigatewayv2_domain_name.engine[0].domain_name_configuration[0].hosted_zone_id
    evaluate_target_health = false
  }
}
