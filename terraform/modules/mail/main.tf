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
      actions = ["ses:SendEmail"]

      # Both resources, and both are required. A SendEmail that names a configuration set is
      # authorized against the identity *and* against the set, so a policy listing only the
      # identity fails every send the moment MAIL_CONFIG_SET is set -- with an AccessDenied that
      # reads as though the sender were unverified.
      resources = [statement.value, aws_sesv2_configuration_set.mail.arn]
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

  /* No `publish` and no alias, unlike the API function.
   *
   * The API has both because API Gateway must name a stable target and because SnapStart resumes
   * a snapshot taken per published version. Neither applies here: the event source mapping below
   * invokes this function unqualified, so $LATEST is what runs, and there is no cold-start
   * snapshot to qualify for. An alias would be a second thing every deploy has to remember to
   * move — which, when it is forgotten, fails silently. Rolling this one back is redeploying the
   * previous jar.
   */

  /* One environment variable, where there used to be none.
   *
   * The region still comes from AWS_REGION, which the runtime sets, and the credentials from the
   * role; who the mail is from, who it is to and what it says are all still in the message, which
   * is the point of the message carrying its own sender.
   *
   * MAIL_CONFIG_SET is not about one mail, which is why it is here rather than in the message: it
   * names the SES configuration set every send is attributed to, and so is a property of the
   * deployment. It is also what makes SES report a bounce back at all -- see the configuration set
   * below. Unset means sends are not attributed and nothing is reported, which is what every
   * environment did before this existed.
   */
  environment {
    variables = {
      MAIL_CONFIG_SET = aws_sesv2_configuration_set.mail.configuration_set_name
    }
  }

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

# ---------------------------------------------------------------------------
# What SES says afterwards
# ---------------------------------------------------------------------------

/* Sending is otherwise one-way. A mail goes on the queue, the function calls SES, and whatever SES
 * learns next -- that the mailbox does not exist, that the person marked it as spam -- is reported
 * to nobody: the send has already succeeded from the function's point of view, because SES
 * accepted it. So a dead address costs three redeliveries and a message in the DLQ above, and then
 * the next event mails it again, forever.
 *
 * A configuration set is what makes SES report back. Every send names it (the mailer passes
 * MAIL_CONFIG_SET as ConfigurationSetName), and SES publishes the events below to the set's event
 * destinations. It is the one line the whole of bounce handling hangs from: a send with no
 * configuration set is delivered identically and says nothing afterwards.
 */
resource "aws_sesv2_configuration_set" "mail" {
  configuration_set_name = local.name

  # No reputation_options and no suppression_options block: the account-level defaults are what is
  # wanted. SES's own suppression list already stops *delivery* to an address that bounced or
  # complained, which is a useful backstop and not a substitute -- it cannot stop matchmaker
  # queueing, cannot explain the silence to the player, and cannot treat a complaint as the opt-out
  # it plainly is. The table this feeds is the record matchmaker acts on.
  delivery_options {
    tls_policy = "OPTIONAL"
  }
}

/* SNS in the middle, which is not a fan-out and not a preference: an SES v2 event destination can
 * name a Kinesis stream, a CloudWatch namespace, an EventBridge bus or an SNS topic, and cannot
 * name a queue. So the topic exists to reach the queue, and has exactly one subscription.
 *
 * The queue, rather than the consuming function directly, for the same reason the mail queue is
 * there at all: a bounce arrives whether or not the function is healthy, and a retry and a
 * dead-letter queue are the difference between a suppression that is delayed and one that is lost.
 */
resource "aws_sns_topic" "mail_events" {
  name = "${local.name}-events"
}

data "aws_iam_policy_document" "mail_events_topic" {
  statement {
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.mail_events.arn]

    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }

    # Only this account's SES, and only this configuration set. Without the condition the policy
    # would let any account's SES publish to the topic, and a suppression written from somebody
    # else's bounce is a player who stops hearing from us for no reason.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_sesv2_configuration_set.mail.arn]
    }
  }
}

resource "aws_sns_topic_policy" "mail_events" {
  arn    = aws_sns_topic.mail_events.arn
  policy = data.aws_iam_policy_document.mail_events_topic.json
}

/* Bounces, complaints and delivery delays, and nothing else.
 *
 * Not SEND or DELIVERY: those are the good news, they are the overwhelming majority of the volume,
 * and recording them would be paying SNS and SQS to learn what we already assumed. Not OPEN or
 * CLICK either -- matchmaker's mail carries no tracking pixel and no rewritten links, and asking
 * for those events would mean SES adding both.
 *
 * DELIVERY_DELAY is the one that is arguably optional. It is not a failure yet: SES is still
 * trying, and most delays are followed by a delivery. It is here because three of them inside a
 * week is a mailbox that is not taking our mail whatever the reason given, and because a delay
 * that is never resolved otherwise produces no event at all.
 */
resource "aws_sesv2_configuration_set_event_destination" "mail_events" {
  configuration_set_name = aws_sesv2_configuration_set.mail.configuration_set_name
  event_destination_name = "${local.name}-events"

  event_destination {
    enabled              = true
    matching_event_types = ["BOUNCE", "COMPLAINT", "DELIVERY_DELAY"]

    sns_destination {
      topic_arn = aws_sns_topic.mail_events.arn
    }
  }
}

/* Where an event waits for the consumer, which lives in the api module because it needs the
 * database and therefore the VPC. This module owns the queue for the same reason it owns the mail
 * queue: it is the thing that produces the messages.
 *
 * Retention is the default fourteen days rather than the mail queue's shorter window. Nobody is
 * waiting on a bounce, and an event that arrives late is still worth recording -- where a
 * notification that arrives late is worth less than nothing.
 */
resource "aws_sqs_queue" "bounce" {
  name = "${local.name}-bounce"

  visibility_timeout_seconds = var.bounce_timeout_s * 6
  message_retention_seconds  = 1209600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.bounce_dead_letter.arn
    maxReceiveCount     = var.max_receive_count
  })
}

/* An event that could not be recorded three times. Worth keeping and worth noticing: these are the
 * suppressions that did not happen, so an address in here is one matchmaker is still mailing.
 */
resource "aws_sqs_queue" "bounce_dead_letter" {
  name                      = "${local.name}-bounce-dlq"
  message_retention_seconds = 1209600
}

data "aws_iam_policy_document" "bounce_queue" {
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.bounce.arn]

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }

    # This topic and no other, which is what stops the queue being an open drop box for anything in
    # the account that can find its url.
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_sns_topic.mail_events.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "bounce" {
  queue_url = aws_sqs_queue.bounce.id
  policy    = data.aws_iam_policy_document.bounce_queue.json
}

/* Raw message delivery on, so the queue holds the SES notification itself rather than the
 * notification as a string inside an SNS envelope.
 *
 * The consumer reads both shapes, deliberately -- this is a checkbox, and the failure mode of
 * someone changing it is otherwise that every bounce becomes silently unreadable. Raw is the
 * default here because the envelope adds nothing the consumer uses: it does not verify the SNS
 * signature (the queue policy above is what says the message came from this topic) and it does not
 * care about the topic arn, having only ever been subscribed to one.
 */
resource "aws_sns_topic_subscription" "bounce" {
  topic_arn            = aws_sns_topic.mail_events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.bounce.arn
  raw_message_delivery = true
}

data "aws_caller_identity" "current" {}
