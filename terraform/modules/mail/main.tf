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
  name = "matchmaker-${var.environment}-mail"
}

data "aws_region" "current" {}

# ---------------------------------------------------------------------------
# The queue
# ---------------------------------------------------------------------------

/* Why there is a queue here at all, rather than matchmaker calling SES itself:
 *
 * The API function is the one a player is waiting on. Sending a mail per participant on that path
 * would make starting a match as slow as SES is, and would give a transient SES failure the power
 * to be the last thing that happens in a start that otherwise succeeded. The queue turns that
 * into a write to somewhere durable, with retries and a dead-letter queue behind it.
 *
 * A standard queue, not FIFO. Delivery is at-least-once either way, so a duplicate is possible;
 * for a notification that is a second copy of "your match has started", which is a nuisance and
 * nothing worse. FIFO's exactly-once window would close it, at the cost of throughput limits and
 * a deduplication id that has to be invented by the sender -- worth it for something a player is
 * charged for, not for this.
 */
resource "aws_sqs_queue" "mail" {
  name = local.name

  # Long enough for the whole batch, with a wide margin: a message becomes visible again while
  # the function is still working on it if this is too short, and is then sent twice.
  visibility_timeout_seconds = var.lambda_timeout_s * 6
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    maxReceiveCount     = var.max_receive_count
  })
}

/* Where a mail goes when it cannot be sent.
 *
 * Kept for the full fourteen days, unlike the queue itself: nothing is waiting on these, and they
 * are the only evidence of what went wrong. A message arriving here means SES refused the same
 * mail three times -- an unverified recipient while the account is in the SES sandbox, an address
 * that does not exist, a sending quota reached.
 */
resource "aws_sqs_queue" "dead_letter" {
  name                      = "${local.name}-dlq"
  message_retention_seconds = 1209600
}

# ---------------------------------------------------------------------------
# The function
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "mailer" {
  name               = "${local.name}-lambda"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  role       = aws_iam_role.mailer.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

/* No VPC access policy, because the function is not in a VPC.
 *
 * It has nothing in the VPC to reach: no database, no engine, no matchmaker call. Everything it
 * needs is in the message it was handed. Leaving it outside means it reaches SES and SQS over
 * their public endpoints, so this module needs neither a NAT gateway nor interface endpoints --
 * which is also why the API function, which *is* in the VPC, needs an SQS endpoint of its own to
 * reach the queue. That one is the deployment's to provide; see the api module's queue_url.
 */

data "aws_iam_policy_document" "mailer" {
  # Reading the queue. The event source mapping polls as this role, so these three are what make
  # the trigger work at all rather than what the function's own code calls.
  statement {
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.mail.arn]
  }

  # Sending, as one identity and no other. `ses:FromAddress` is the condition that makes that
  # true: without it the action allows sending as any identity the account has verified.
  dynamic "statement" {
    for_each = var.sender_identity_arn == "" ? [] : [var.sender_identity_arn]
    content {
      actions   = ["ses:SendEmail"]
      resources = [statement.value]
    }
  }
}

resource "aws_iam_role_policy" "mailer" {
  name   = local.name
  role   = aws_iam_role.mailer.id
  policy = data.aws_iam_policy_document.mailer.json
}

resource "aws_cloudwatch_log_group" "mailer" {
  name              = "/aws/lambda/${local.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "mailer" {
  function_name = local.name
  role          = aws_iam_role.mailer.arn
  runtime       = "java21"
  handler       = "com.vivi.matchmaker.mailer.Handler::handleRequest"

  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_timeout_s

  # No environment variables at all. The region comes from AWS_REGION, which the runtime sets,
  # and the credentials from the role; everything else -- who the mail is from, who it is to,
  # what it says -- is in the message. That is the point of the message carrying its own sender.

  depends_on = [
    aws_iam_role_policy_attachment.basic_execution,
    aws_cloudwatch_log_group.mailer,
  ]
}

/* The poller. Lambda long-polls the queue as this role and invokes the function with a batch.
 *
 * `ReportBatchItemFailures` is what makes a single bad address cost one redelivery rather than
 * ten: without it, one message failing redelivers the whole batch, and the nine that were already
 * sent are sent again. The function answers with the ids it could not send -- see `Handler`.
 */
resource "aws_lambda_event_source_mapping" "mail" {
  event_source_arn = aws_sqs_queue.mail.arn
  function_name    = aws_lambda_function.mailer.arn

  batch_size                         = var.batch_size
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_batching_window_in_seconds = 5
}
