# ---------------------------------------------------------------------------
# The ends of matches
# ---------------------------------------------------------------------------

/* When a match ends -- its results arrive, a refresh finds it over, a clock runs out, its creator
 * cancels it -- the API function puts the match's id on this queue, and the function below settles
 * what the end owes: its engine is prompted to archive it if it has not, and a cancel is told to
 * the engine, and its players are placed on the game's leaderboard (RankingService). See
 * com.vivi.matchmaker.service.EndingService. Ratings are not among it: they move in the API call
 * that ends the match, in its transaction, and depend on no queue -- only the places they earn
 * follow here. An admin setting a rating, or reclassifying a finished match, puts just the game's
 * id on this queue, for its leaderboard.
 *
 * This replaces the daily sweep, and the queue's redelivery is what replaces its retries: a match
 * still owed something is failed back to the queue, delivered again once the visibility timeout has
 * passed, and after max_receive_count tries moved to the dead-letter queue -- where it can be read
 * and redriven, or settled by hand (ending.Handler.main).
 *
 * A standard queue. Settling is idempotent -- an archive confirmed once is answered as such, a
 * cancel already heard is not sent again -- so a duplicate delivery costs a read and nothing else,
 * and the order matches end in does not matter. */
locals {
  ending_name = "${local.name}-ending"

  # Lambda's maximum. A batch is settled one match after another, each perhaps waiting on an
  # engine, and the function stops starting on them half a minute before this (ending.Handler).
  ending_timeout_s = 900
}

resource "aws_sqs_queue" "match_ended" {
  name = "${local.name}-match-ended"

  # The interval between tries of a match still owed something. Six times the function's timeout,
  # as AWS recommends for a queue a function drains, and as the mail queue has: an hour and a half.
  # A match that finished moments ago is owed its archive while its engine makes it, so the first
  # try usually finds that done; one that does not waits for the next.
  visibility_timeout_seconds = local.ending_timeout_s * 6

  # Fourteen days, the most there is: enough to outlast every try, and an outage of the database
  # or the function besides, without a message expiring before it is settled.
  message_retention_seconds = 1209600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.match_ended_dead_letter.arn
    # Ten tries an hour and a half apart: fifteen hours for an engine to come back. An engine that
    # does not archive at all lands every match of its game here.
    maxReceiveCount = var.ending_max_receive_count
  })
}

/* Where a match goes when its end could not be settled. Kept fourteen days: nothing is waiting on
 * these, and they are the record of what is owed. */
resource "aws_sqs_queue" "match_ended_dead_letter" {
  name                      = "${local.name}-match-ended-dlq"
  message_retention_seconds = 1209600
}

/* Sending, for the API function, and receiving, for the event source mapping that polls as the same
 * role -- one role for both, as the bounce function shares it, for the reason given there. */
data "aws_iam_policy_document" "match_ended" {
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.match_ended.arn]
  }

  statement {
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.match_ended.arn]
  }
}

resource "aws_iam_role_policy" "match_ended" {
  name   = "${local.name}-match-ended"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.match_ended.json
}

resource "aws_cloudwatch_log_group" "ending" {
  name              = "/aws/lambda/${local.ending_name}"
  retention_in_days = var.log_retention_days
}

/* A further function from the api's jar, inside the VPC beside it, with its role: it reads and
 * writes the same tables, calls the same engines with the same keys, and checks the same buckets.
 *
 * Small, because nobody waits on it: a queue absorbs a slow cold start, and what it does is a few
 * queries and an engine call per match. */
resource "aws_lambda_function" "ending" {
  function_name = local.ending_name
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = "com.vivi.matchmaker.ending.Handler::handleRequest"

  # The same jar as the api function, for the reason the bounce function gives.
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.ending_memory_mb
  timeout     = local.ending_timeout_s

  # No SnapStart, alias or publish: the event source mapping invokes $LATEST, and nobody waits on
  # its cold start.

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = {
      DB_HOST     = local.db_host
      DB_PORT     = local.db_port
      DB_NAME     = var.db_name
      DB_USER     = var.db_user
      DB_PASSWORD = var.db_password
      # One match at a time, and a little concurrency within one.
      DB_POOL_SIZE = "2"

      ARCHIVE_BUCKET          = aws_s3_bucket.archive["permanent"].bucket
      FRIENDLY_ARCHIVE_BUCKET = aws_s3_bucket.archive["friendly"].bucket
    }
  }

  # No MATCH_ENDED_QUEUE_URL: nothing here ends a match, so nothing here says one ended.

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_iam_role_policy_attachment.vpc_access,
    aws_cloudwatch_log_group.ending,
  ]
}

/* The poller. `ReportBatchItemFailures`, as the other consumers have: the handler answers with the
 * matches still owed something, so one engine that is down costs its own matches a redelivery
 * rather than the whole batch.
 *
 * No batching window: a cancelled match is dropped by its engine as soon as it can be. */
resource "aws_lambda_event_source_mapping" "match_ended" {
  event_source_arn = aws_sqs_queue.match_ended.arn
  function_name    = aws_lambda_function.ending.arn

  batch_size              = 10
  function_response_types = ["ReportBatchItemFailures"]

  /* A ceiling on how many run at once, which is about the database, as the bounce consumer's is:
   * ending_max_concurrency * 2 connections at worst, beside the api function's. A burst of endings
   * waits in the queue rather than taking connections players' requests need. */
  scaling_config {
    maximum_concurrency = var.ending_max_concurrency
  }

  depends_on = [aws_iam_role_policy.match_ended]
}
