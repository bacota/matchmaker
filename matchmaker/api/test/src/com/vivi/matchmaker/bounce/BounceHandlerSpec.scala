package com.vivi.matchmaker.bounce

import munit.FunSuite
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}

/** Getting from an SQS batch to a list of suppressions: the envelopes, and what happens to a message that has none.
  *
  * The write itself is `SuppressionRepoSpec`'s and needs a database; this needs nothing, and covers the two things that
  * are easy to get wrong and invisible afterwards — the double encoding SNS does to the notification, and a message
  * that must be accepted rather than retried forever.
  */
class BounceHandlerSpec extends FunSuite {

    private val notification = """{
      "eventType": "Complaint",
      "complaint": {
        "complainedRecipients": [{ "emailAddress": "player@example.invalid" }],
        "complaintFeedbackType": "abuse"
      },
      "mail": { "messageId": "0100017b-1234-4abc-8def-0123456789ab-000000" }
    }"""

    private def sqsEvent(bodies: (String, String)*): String =
        ujson.write(
          ujson.Obj(
            "Records" -> ujson.Arr.from(
              bodies.map { (messageId, body) =>
                  ujson.Obj(
                    "messageId" -> messageId,
                    "receiptHandle" -> "AQEB...",
                    "body" -> body,
                    "eventSource" -> "aws:sqs",
                    "eventSourceARN" -> "arn:aws:sqs:us-east-1:123456789012:matchmaker-dev-bounce"
                  )
              }
            )
          )
        )

    /* What a plain SNS subscription delivers: the notification as a *string* of JSON inside an
     * envelope, which has to be parsed twice. */
    private def snsEnvelope(message: String): String =
        ujson.write(
          ujson.Obj(
            "Type" -> "Notification",
            "MessageId" -> "9f1c3a1e-0000-5555-aaaa-0123456789ab",
            "TopicArn" -> "arn:aws:sns:us-east-1:123456789012:matchmaker-dev-mail-events",
            "Message" -> message,
            "Timestamp" -> "2026-09-17T18:00:02.000Z",
            "SignatureVersion" -> "1"
          )
        )

    private def eventsOf(record: Handler.Record): Seq[EmailSuppression.Event] =
        record match {
            case Handler.Record.Understood(_, events) => events
            case Handler.Record.Unreadable(id, why)   => fail(s"$id was unreadable: $why")
        }

    test("an SNS envelope is unwrapped, and its notification read") {
        val records = Handler.records(sqsEvent("m-1" -> snsEnvelope(notification)))
        assertEquals(records.map(_.messageId), Seq("m-1"))
        assertEquals(eventsOf(records.head).map(_.email), Seq("player@example.invalid"))
        assertEquals(eventsOf(records.head).map(_.reason), Seq(SuppressionReason.Complaint))
    }

    /* Raw message delivery is a checkbox on the subscription. Both shapes are read because the
     * effect of someone changing it would otherwise be every bounce failing to the dead-letter
     * queue -- visible, now, but still every bounce. */
    test("a raw notification, with no envelope, is read the same way") {
        val records = Handler.records(sqsEvent("m-2" -> notification))
        assertEquals(eventsOf(records.head).map(_.email), Seq("player@example.invalid"))
    }

    test("every message in a batch is read, and each keeps its own id") {
        val records = Handler.records(
          sqsEvent("m-1" -> snsEnvelope(notification), "m-2" -> notification, "m-3" -> snsEnvelope("{}"))
        )
        assertEquals(records.map(_.messageId), Seq("m-1", "m-2", "m-3"))
        assertEquals(eventsOf(records(0)).size, 1)
        assertEquals(eventsOf(records(1)).size, 1)
        // The third is not a notification at all, and is a failure rather than an empty success.
        assert(records(2).isInstanceOf[Handler.Record.Unreadable], records(2).toString)
    }

    /* Failed, not acknowledged, which is the correction to how this started out.
     *
     * Retrying will not make it parse. The dead-letter queue is not a retry mechanism here: it is
     * where a suppression that was lost becomes something with a queue depth and a terraform output
     * naming it, rather than a line in CloudWatch. What is at stake is not a message -- it is
     * matchmaker going on mailing an address SES told us to stop mailing. */
    test("a body that is not JSON at all is failed to the dead-letter queue") {
        val records = Handler.records(sqsEvent("m-4" -> "not json"))
        assertEquals(records.map(_.messageId), Seq("m-4"))
        assert(records.head.isInstanceOf[Handler.Record.Unreadable], records.head.toString)
    }

    test("a record with no body is unreadable rather than absent") {
        val event = ujson.write(ujson.Obj("Records" -> ujson.Arr(ujson.Obj("messageId" -> "m-5"))))
        val records = Handler.records(event)
        assertEquals(records.map(_.messageId), Seq("m-5"))
        assert(records.head.isInstanceOf[Handler.Record.Unreadable], records.head.toString)
    }

    /* The one thing that is still skipped rather than failed: `batchItemFailures` names a message by
     * its id, so a record without one cannot be reported as a failure -- there is nothing to name.
     * SQS always sends it, so this is a record we were not given rather than one we are dropping. */
    test("a record with no id is not a record, since a failure could not name it") {
        val event = ujson.write(
          ujson.Obj(
            "Records" -> ujson.Arr(
              ujson.Obj("body" -> notification),
              ujson.Obj("messageId" -> "m-6", "body" -> notification)
            )
          )
        )
        assertEquals(Handler.records(event).map(_.messageId), Seq("m-6"))
    }

    test("an event that is not an SQS batch is no records") {
        assertEquals(Handler.records("{}"), Seq.empty)
        assertEquals(Handler.records("""{ "Records": {} }"""), Seq.empty)
        assertEquals(Handler.records("[]"), Seq.empty)
    }

    /* A kind we do not act on is acknowledged with nothing recorded -- the distinction that makes
     * failing the unreadable ones affordable. Asserted here as well as in `SesEventSpec` because it
     * is the handler that decides what is acknowledged. */
    test("a valid event we have no use for is understood, and not failed") {
        val delivery = """{ "eventType": "Delivery", "mail": { "messageId": "x" }, "delivery": {} }"""
        val records = Handler.records(sqsEvent("m-7" -> snsEnvelope(delivery)))
        assertEquals(eventsOf(records.head), Seq.empty)
    }

    test("the batch response names the failures, and is empty when there are none") {
        assertEquals(Handler.response(Nil), """{"batchItemFailures":[]}""")
        assertEquals(
          Handler.response(Seq("m-1", "m-2")),
          """{"batchItemFailures":[{"itemIdentifier":"m-1"},{"itemIdentifier":"m-2"}]}"""
        )
    }
}
