package com.vivi.matchmaker.ending

import munit.FunSuite
import com.vivi.matchmaker.model.{GameId, MatchId}

/** Getting from an SQS batch to the matches whose ends are to be settled, and back to the messages to deliver again.
  *
  * Settling itself is `EndingServiceSpec`'s and needs a database; this needs nothing. What it covers is that the body
  * `SqsMatchEndings` writes is the body this reads, and that a message nobody can read is failed — so that it reaches
  * the dead-letter queue — rather than acknowledged and lost.
  */
class EndingHandlerSpec extends FunSuite {

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
                    "eventSourceARN" -> "arn:aws:sqs:us-east-1:123456789012:matchmaker-dev-match-ended"
                  )
              }
            )
          )
        )

    test("the body a match's ending is queued with is read back as that match") {
        val body = upickle.default.write(MatchEnded.of(GameId(7), MatchId("3f1c-match")))
        assertEquals(
          Handler.records(sqsEvent("m-1" -> body)),
          Seq(Handler.Record.Understood("m-1", MatchEnded(7, "3f1c-match")))
        )
    }

    test("a body that is not a match's ending is unreadable, and one without an id is skipped") {
        val records = Handler.records(sqsEvent("m-1" -> "not json", "m-2" -> """{"gameId":"seven"}"""))
        assertEquals(records.map(_.getClass.getSimpleName), Seq("Unreadable", "Unreadable"))
        assertEquals(Handler.records("""{"Records":[{"body":"{}"}]}"""), Nil)
    }

    test("the messages to deliver again are named in the partial-batch response") {
        assertEquals(
          ujson.read(Handler.response(Seq("m-2"))),
          ujson.Obj("batchItemFailures" -> ujson.Arr(ujson.Obj("itemIdentifier" -> "m-2")))
        )
        assertEquals(ujson.read(Handler.response(Nil)), ujson.Obj("batchItemFailures" -> ujson.Arr()))
    }
}
