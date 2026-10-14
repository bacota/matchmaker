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
        val body = upickle.default.write(MatchEnded.of(GameId(7), MatchId(31)))
        assertEquals(
          Handler.records(sqsEvent("m-1" -> body)),
          Seq(Handler.Record.Understood("m-1", MatchEnded(7, 31)))
        )
    }

    test("a game's ratings changing is read back as that, and a match's ending is not mistaken for it") {
        val records = Handler.records(
          sqsEvent(
            "m-1" -> upickle.default.write(RatingsChanged(7)),
            "m-2" -> upickle.default.write(MatchEnded.of(GameId(7), MatchId(31)))
          )
        )
        assertEquals(
          records,
          Seq(
            Handler.Record.Understood("m-1", RatingsChanged(7)),
            Handler.Record.Understood("m-2", MatchEnded(7, 31))
          )
        )
    }

    test("a tournament's match due, round due and round check are read back as themselves, and nothing else") {
        val records = Handler.records(
          sqsEvent(
            "m-1" -> upickle.default.write(TournamentMatchDue(gameId = 7, tournamentId = 3, matchId = 32)),
            "m-2" -> upickle.default.write(TournamentRoundDue(7, 3)),
            "m-3" -> upickle.default.write(RatingsChanged(7)),
            "m-4" -> upickle.default.write(MatchEnded.of(GameId(7), MatchId(31))),
            "m-5" -> upickle.default.write(
              TournamentRoundCheck(gameId = 7, tournamentId = 3, checkedAt = 1760000000000L)
            )
          )
        )
        assertEquals(
          records,
          Seq(
            Handler.Record.Understood("m-1", TournamentMatchDue(gameId = 7, tournamentId = 3, matchId = 32)),
            Handler.Record.Understood("m-2", TournamentRoundDue(7, 3)),
            Handler.Record.Understood("m-3", RatingsChanged(7)),
            Handler.Record.Understood("m-4", MatchEnded(7, 31)),
            Handler.Record
                .Understood("m-5", TournamentRoundCheck(gameId = 7, tournamentId = 3, checkedAt = 1760000000000L))
          )
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
