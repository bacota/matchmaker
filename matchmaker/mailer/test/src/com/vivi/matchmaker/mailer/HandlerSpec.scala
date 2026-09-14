package com.vivi.matchmaker.mailer

import munit.FunSuite

/** Reading an SQS event, and saying which of its messages failed. */
class HandlerSpec extends FunSuite {

    private def event(bodies: (String, String)*): String =
        upickle.default.write(
          ujson.Obj(
            "Records" -> ujson.Arr.from(
              bodies.map((id, body) => ujson.Obj("messageId" -> id, "body" -> body, "receiptHandle" -> "handle"))
            )
          )
        )

    private def mail(recipient: String): String =
        upickle.default.write(MailMessage("from@example.com", recipient, "subject", "body"))

    test("every record in the batch is read, in order") {
        val records = Handler.records(event("m-1" -> mail("a@example.com"), "m-2" -> mail("b@example.com")))
        assertEquals(records.map(_.messageId), Seq("m-1", "m-2"))
        assertEquals(records.map(_.message.recipient), Seq("a@example.com", "b@example.com"))
    }

    test("an event with no records at all is no work rather than an error") {
        assertEquals(Handler.records("""{"Records":[]}"""), Seq.empty)
        assertEquals(Handler.records("{}"), Seq.empty)
    }

    // Redelivery cannot help a body that will not parse, so it is dropped here rather than left to
    // fail its way to the dead-letter queue three attempts later.
    test("a body that is not a mail is dropped, and does not take the batch with it") {
        val records = Handler.records(event("m-1" -> "not json at all", "m-2" -> mail("b@example.com")))
        assertEquals(records.map(_.messageId), Seq("m-2"))
    }

    test("a record missing the fields SQS always sends is skipped") {
        val malformed = """{"Records":[{"body":"{}"},{"messageId":"m-2"}]}"""
        assertEquals(Handler.records(malformed), Seq.empty)
    }

    /* The batch response. An empty list is the whole batch succeeding; anything in it is
     * redelivered, and nothing else is. */

    test("a batch that all sent reports no failures") {
        assertEquals(Handler.response(Seq.empty), """{"batchItemFailures":[]}""")
    }

    test("only the messages that failed are named") {
        assertEquals(Handler.response(Seq("m-2")), """{"batchItemFailures":[{"itemIdentifier":"m-2"}]}""")
    }
}
