package com.vivi.matchmaker.bounce

import munit.FunSuite
import com.vivi.matchmaker.model.SuppressionReason

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

    test("an SNS envelope is unwrapped, and its notification read") {
        val records = Handler.records(sqsEvent("m-1" -> snsEnvelope(notification)))
        assertEquals(records.map(_.messageId), Seq("m-1"))
        assertEquals(records.head.events.map(_.email), Seq("player@example.invalid"))
        assertEquals(records.head.events.map(_.reason), Seq(SuppressionReason.Complaint))
    }

    /* Raw message delivery is a checkbox on the subscription. Both shapes are read because the
     * effect of someone changing it would otherwise be that every bounce becomes unreadable, with
     * nothing failing to say so. */
    test("a raw notification, with no envelope, is read the same way") {
        val records = Handler.records(sqsEvent("m-2" -> notification))
        assertEquals(records.head.events.map(_.email), Seq("player@example.invalid"))
    }

    test("every message in a batch is read, and each keeps its own id") {
        val records = Handler.records(
          sqsEvent("m-1" -> snsEnvelope(notification), "m-2" -> notification, "m-3" -> snsEnvelope("{}"))
        )
        assertEquals(records.map(_.messageId), Seq("m-1", "m-2", "m-3"))
        assertEquals(records.map(_.events.size), Seq(1, 1, 0))
    }

    /* Accepted with nothing to record, rather than failed. A body that will not parse will not
     * parse on the next receive either, so failing it buys three more receives and a message in the
     * dead-letter queue; the body is logged where it can be read instead. */
    test("a body that is not JSON at all is a message with no events") {
        val records = Handler.records(sqsEvent("m-4" -> "not json"))
        assertEquals(records.map(_.messageId), Seq("m-4"))
        assertEquals(records.head.events, Seq.empty)
    }

    test("a record with no id or no body is not a record") {
        val event = ujson.write(
          ujson.Obj(
            "Records" -> ujson.Arr(
              ujson.Obj("body" -> notification),
              ujson.Obj("messageId" -> "m-5"),
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

    test("the batch response names the failures, and is empty when there are none") {
        assertEquals(Handler.response(Nil), """{"batchItemFailures":[]}""")
        assertEquals(
          Handler.response(Seq("m-1", "m-2")),
          """{"batchItemFailures":[{"itemIdentifier":"m-1"},{"itemIdentifier":"m-2"}]}"""
        )
    }
}
