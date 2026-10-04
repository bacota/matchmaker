package com.vivi.matchmaker.ending

import cats.effect.IO
import upickle.default.{macroRW, read, write, ReadWriter}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.notify.SqsNotifier

/** That a match has ended — played out, forfeited or cancelled — and what its end still owes is to be settled: its
  * archive made, its engine told of a cancel (`EndingService.settle`). Its rating is not among them: that moves in the
  * transaction that ended it.
  *
  * Said after the transaction that ended the match has committed, so that whoever settles it reads the match as it
  * ended. Never fails the call that ended it, which has already happened: a failure here is logged, and the match is
  * settled when its ending is next said — a repeated results callback says it again — or by hand
  * (`ending.Handler.main`).
  */
trait MatchEndings {
    def ended(gameId: GameId, matchId: MatchId): IO[Unit]
}

/** What goes on the queue: the match, and nothing about it. Whoever settles it reads the rest from the database, as it
  * stands then — a message that waited does not act on what was true when it was sent.
  */
case class MatchEnded(gameId: Int, matchId: String)

object MatchEnded {
    given ReadWriter[MatchEnded] = macroRW

    def of(gameId: GameId, matchId: MatchId): MatchEnded = MatchEnded(gameId.value, matchId.value)

    /** A message body, or why it is not one. */
    def parse(body: String): Either[String, MatchEnded] =
        try Right(read[MatchEnded](body))
        catch { case e: Exception => Left(s"not a match ending: ${e.getMessage}") }
}

/** Puts the ending on the queue the ending listener drains (`ending.Handler`), so that the request that ended the match
  * waits on none of the work, and that work gets the queue's retries — which take the place of the daily sweep that
  * used to catch whatever was left owed.
  */
class SqsMatchEndings(queueUrl: String, client: () => SqsClient) extends MatchEndings {

    def ended(gameId: GameId, matchId: MatchId): IO[Unit] =
        IO.blocking {
            client().sendMessage(
              SendMessageRequest.builder().queueUrl(queueUrl).messageBody(write(MatchEnded.of(gameId, matchId))).build()
            )
        }.void
            .handleErrorWith(e =>
                IO.blocking(
                  System.err.println(
                    s"the end of match ${matchId.value} of game ${gameId.value} was not queued, and is not settled: $e"
                  )
                )
            )
}

object MatchEndings {

    /** Says nothing to anybody: for a service built where no ending is ever to be settled. */
    val disabled: MatchEndings = (_, _) => IO.unit

    /** Settles the ending at once, in the request that ended the match, rather than queueing it: for an environment
      * with no queue — the local server and the tests. Once: nothing retries what is left owed, which is what the queue
      * is for. Unable to fail the request, like the queue.
      */
    def inline(settle: (GameId, MatchId) => IO[Unit]): MatchEndings =
        (gameId, matchId) =>
            settle(gameId, matchId).handleErrorWith(e =>
                IO.blocking(System.err.println(s"settling the end of match ${matchId.value} failed: $e"))
            )

    /** The queue named by `MATCH_ENDED_QUEUE_URL`, or `inline` when there is none. */
    def fromEnvironment(
        inline: => MatchEndings,
        env: String => Option[String] = key => Option(System.getenv(key))
    ): MatchEndings =
        env("MATCH_ENDED_QUEUE_URL").map(_.trim).filter(_.nonEmpty) match {
            case None => inline
            case Some(queueUrl) =>
                val region = env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
                // Built on first use, for the reason SqsNotifier gives: SnapStart.
                new SqsMatchEndings(queueUrl, SqsNotifier.lazily(() => SqsNotifier.client(region)))
        }
}
