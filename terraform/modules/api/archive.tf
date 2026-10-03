# ---------------------------------------------------------------------------
# Archives of completed matches (archiving-matches-plan.md)
# ---------------------------------------------------------------------------

/* Two buckets, by how long an archive is kept: a friendly match's for 30 days, every other match's
 * permanently. Matchmaker chooses the bucket from the match's `friendly` flag, which a completed
 * match can no longer change, and signs the urls the engine uploads and reads with. Nothing else
 * touches them: engines hold no AWS credentials, and no url to an archive is ever given to a
 * browser, because an archive holds what the engine hides from players.
 *
 * Named under .vivi.com to keep clear of everybody else's names in S3's global namespace. The dots
 * have a cost: a virtual-hosted url (<bucket>.s3.<region>.amazonaws.com) is not covered by S3's
 * wildcard certificate, so matchmaker signs path-style urls (S3ArchiveStore). */
locals {
  archive_buckets = {
    permanent = "${local.name}-archive.vivi.com"
    friendly  = "${local.name}-friendly-archive.vivi.com"
  }

  # How long a deleted or overwritten version is kept, in both buckets, and how long a friendly
  # archive is kept at all. The second must agree with ArchiveService.FriendlyRetention.
  archive_retention_days = 30
}

resource "aws_s3_bucket" "archive" {
  for_each = local.archive_buckets
  bucket   = each.value
}

resource "aws_s3_bucket_public_access_block" "archive" {
  for_each = aws_s3_bucket.archive
  bucket   = each.value.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "archive" {
  for_each = aws_s3_bucket.archive
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "archive" {
  for_each = aws_s3_bucket.archive
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versioned, so that an archive overwritten or deleted by mistake can be restored for 30 days. No
# Object Lock: write-once is matchmaker's rule (it signs no further upload once an archive is
# confirmed), and versioning is the safety net under it.
resource "aws_s3_bucket_versioning" "archive" {
  for_each = aws_s3_bucket.archive
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }
}

/* The permanent bucket's rule never touches a current object: it only clears up what was already
 * deleted or overwritten, 30 days later, and the delete markers left once nothing is behind them. */
resource "aws_s3_bucket_lifecycle_configuration" "permanent_archive" {
  bucket = aws_s3_bucket.archive["permanent"].id

  rule {
    id     = "clean-up-deleted-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = local.archive_retention_days
    }

    expiration {
      expired_object_delete_marker = true
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.archive]
}

/* The friendly bucket's rule also expires every current object 30 days after it was written. In a
 * versioned bucket that only lays a delete marker over it: the archive stops being readable at 30
 * days, and its data goes with the noncurrent versions 30 days after that. A rule with `days` set
 * removes the delete markers it leaves behind on its own -- S3 does not allow
 * expired_object_delete_marker beside it. */
resource "aws_s3_bucket_lifecycle_configuration" "friendly_archive" {
  bucket = aws_s3_bucket.archive["friendly"].id

  rule {
    id     = "expire-friendly-archives"
    status = "Enabled"
    filter {}

    expiration {
      days = local.archive_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = local.archive_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.archive]
}

/* What matchmaker's role may do with the archives: write and read objects, and check one. A signed
 * url acts with its signer's permissions, so the upload and the download the engine makes are
 * this role's, and this is the only grant either needs. Engines get nothing.
 *
 * ListBucket is there for HeadObject's sake. Without it S3 answers a missing key with 403 rather
 * than 404, and matchmaker could not tell an expired friendly archive from a check that failed --
 * the Review and Watch links of an expired match would never be withdrawn. */
data "aws_iam_policy_document" "archive" {
  statement {
    actions   = ["s3:PutObject", "s3:GetObject", "s3:GetObjectAttributes"]
    resources = [for bucket in aws_s3_bucket.archive : "${bucket.arn}/*"]
  }

  statement {
    actions   = ["s3:ListBucket"]
    resources = [for bucket in aws_s3_bucket.archive : bucket.arn]
  }
}

resource "aws_iam_role_policy" "archive" {
  name   = "${local.name}-archive"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.archive.json
}

# ---------------------------------------------------------------------------
# The archive sweep
# ---------------------------------------------------------------------------

/* Asks again about what archiving and cancelling left owed when something did not answer at the
 * time: completed matches never archived (prompted through the engine's status call) and cancels
 * the engine never acknowledged. See com.vivi.matchmaker.service.SweepService. Each match is asked
 * about once per run, and a run is once a day: nothing it catches is urgent, since a match that
 * is not archived yet is still served from its live copy.
 *
 * A third function from the api's jar, inside the VPC beside it, with its role: it reads and writes
 * the same tables, calls the same engines with the same keys, and checks the same buckets. */
resource "aws_cloudwatch_log_group" "sweep" {
  name              = "/aws/lambda/${local.name}-sweep"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "sweep" {
  function_name = "${local.name}-sweep"
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = "com.vivi.matchmaker.sweep.Handler::handleRequest"

  # The same jar as the api function, for the reason the bounce function gives.
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  # Lambda's maximum. A run takes on every match owed, one after another, and stops starting on
  # them half a minute before this (sweep.Handler); the next day's run carries on from there.
  timeout = 900

  # No SnapStart, alias or publish: nothing waits on a scheduled run's cold start.

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = {
      DB_HOST      = local.db_host
      DB_PORT      = local.db_port
      DB_NAME      = var.db_name
      DB_USER      = var.db_user
      DB_PASSWORD  = var.db_password
      DB_POOL_SIZE = "2"

      ARCHIVE_BUCKET          = aws_s3_bucket.archive["permanent"].bucket
      FRIENDLY_ARCHIVE_BUCKET = aws_s3_bucket.archive["friendly"].bucket
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_iam_role_policy_attachment.vpc_access,
    aws_cloudwatch_log_group.sweep,
  ]
}

resource "aws_cloudwatch_event_rule" "sweep" {
  name                = "${local.name}-sweep"
  description         = "Archive sweep: completed matches never archived, cancels never acknowledged."
  schedule_expression = "rate(1 day)"
}

resource "aws_cloudwatch_event_target" "sweep" {
  rule = aws_cloudwatch_event_rule.sweep.name
  arn  = aws_lambda_function.sweep.arn
}

resource "aws_lambda_permission" "sweep" {
  statement_id  = "AllowEventBridgeSweep"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.sweep.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.sweep.arn
}
