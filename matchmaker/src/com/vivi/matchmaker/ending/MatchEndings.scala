package com.vivi.matchmaker.ending

import cats.effect.IO
import upickle.default.{macroRW, read, write, ReadWriter}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.notify.SqsNotifier

/** That a match has ended — played out, forfeited or cancelled — and what its end still owes is to be settled: its
  * archive made, its engine told of a cancel, its players placed on the game's leaderboard (`EndingService.settle`).
  * Its rating is not among them: that moves in the transaction that ended it.
  *
  * Said after the transaction that ended the match has committed, so that whoever settles it reads the match as it
  * ended. Never fails the call that ended it, which has already happened: a failure here is logged, and the match is
  * settled when its ending is next said — a repeated results callback says it again — or by hand
  * (`ending.Handler.main`).
  */
trait MatchEndings {
    def ended(gameId: GameId, matchId: MatchId): IO[Unit]

    /** That ratings in the game have moved other than by a match ending — an admin set one, or said a finished match
      * was or was not friendly — and its leaderboard is to be put in order (`RankingService`). Said, and failing, as
      * [[ended]] is; one that is lost is put in order by the next ending of the game.
      */
    def ratingsChanged(gameId: GameId): IO[Unit]

    /** That a tournament match is due to be created — match `matchNo` of a pool — and its engine asked to make its game
      * (`TournamentPlayService.createMatch`). One message per match, so that a round's matches are made in parallel
      * rather than one after another inside the request that started it. Said, and failing, as [[ended]] is.
      */
    def due(message: MatchDue): IO[Unit]

    /** That a tournament match is to be checked against its clock (`TournamentPlayService.check`): its owner pressed
      * Check round. Said, and failing, as [[ended]] is.
      */
    def check(gameId: GameId, matchId: MatchId): IO[Unit]
}

/** A tournament match to create: the pool's `matchNo`th, worked out from the pool alone when the message is read. */
case class MatchDue(gameId: Int, tournamentId: Long, fixtureId: Long, matchNo: Int)

object MatchDue {
    given ReadWriter[MatchDue] = macroRW
}

/** A tournament match to check against its clock. `check` is always true: it is what tells this message from a
  * [[MatchEnded]], which has the same two other fields.
  */
case class MatchCheck(gameId: Int, matchId: String, check: Boolean = true)

object MatchCheck {
    given ReadWriter[MatchCheck] = upickle.default.macroRW[MatchCheck]

    /** Written with `check` present, as a defaulted field otherwise would not be. */
    def body(gameId: GameId, matchId: MatchId): String =
        ujson.write(ujson.Obj("gameId" -> gameId.value, "matchId" -> matchId.value, "check" -> true))
}

/** What goes on the queue: the match, and nothing about it. Whoever settles it reads the rest from the database, as it
  * stands then — a message that waited does not act on what was true when it was sent.
  */
case class MatchEnded(gameId: Int, matchId: String)

object MatchEnded {
    given ReadWriter[MatchEnded] = macroRW

    def of(gameId: GameId, matchId: MatchId): MatchEnded = MatchEnded(gameId.value, matchId.value)

    /** A message body, or why it is not one: a match's ending, or else a game's ratings changing. In that order,
      * because a match's ending is a game's ratings changing with a match id besides, and is read as one by a reader
      * that ignores the field it does not know.
      */
    def parse(body: String): Either[String, Message] = {
        val json =
            try Some(ujson.read(body).obj)
            catch { case _: Exception => None }
        def attempt[A: ReadWriter]: Option[A] =
            try Some(read[A](body))
            catch { case _: Exception => None }
        json match {
            case None => Left(s"not a message: $body")
            // The most particular first: each later kind would also read the fields of an earlier one.
            case Some(o) if o.contains("fixtureId") => attempt[MatchDue].toRight(s"not a due match: $body")
            case Some(o) if o.contains("check")     => attempt[MatchCheck].toRight(s"not a match check: $body")
            case Some(o) if o.contains("matchId")   => attempt[MatchEnded].toRight(s"not a match ending: $body")
            case Some(_)                            => attempt[RatingsChanged].toRight(s"not a match ending: $body")
        }
    }

    /** Every kind of message the ending queue carries. */
    type Message = MatchEnded | RatingsChanged | MatchDue | MatchCheck
}

/** What goes on the queue when a game's ratings have moved with no match ending: the game, for its leaderboard. */
case class RatingsChanged(gameId: Int)

object RatingsChanged {
    given ReadWriter[RatingsChanged] = macroRW
}

/** Puts the ending on the queue the ending listener drains (`ending.Handler`), so that the request that ended the match
  * waits on none of the work, and that work gets the queue's retries — which take the place of the daily sweep that
  * used to catch whatever was left owed.
  */
class SqsMatchEndings(queueUrl: String, client: () => SqsClient) extends MatchEndings {

    def ended(gameId: GameId, matchId: MatchId): IO[Unit] =
        send(
          write(MatchEnded.of(gameId, matchId)),
          s"the end of match ${matchId.value} of game ${gameId.value} was not queued, and is not settled"
        )

    def ratingsChanged(gameId: GameId): IO[Unit] =
        send(
          write(RatingsChanged(gameId.value)),
          s"the change to game ${gameId.value}'s ratings was not queued, and its leaderboard waits for its next ending"
        )

    def due(message: MatchDue): IO[Unit] =
        send(
          write(message),
          s"match ${message.matchNo} of pool ${message.fixtureId} was not queued; Resume the round to queue it again"
        )

    def check(gameId: GameId, matchId: MatchId): IO[Unit] =
        send(MatchCheck.body(gameId, matchId), s"the check of match ${matchId.value} was not queued")

    private def send(body: String, failed: String): IO[Unit] =
        IO.blocking {
            client().sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build())
        }.void
            .handleErrorWith(e => IO.blocking(System.err.println(s"$failed: $e")))
}

object MatchEndings {

    /** Says nothing to anybody: for a service built where no ending is ever to be settled. */
    val disabled: MatchEndings = new MatchEndings {
        def ended(gameId: GameId, matchId: MatchId): IO[Unit] = IO.unit
        def ratingsChanged(gameId: GameId): IO[Unit] = IO.unit
        def due(message: MatchDue): IO[Unit] = IO.unit
        def check(gameId: GameId, matchId: MatchId): IO[Unit] = IO.unit
    }

    /** Settles the ending in this process rather than queueing it: for an environment with no queue — the local server
      * and the tests. Once: nothing retries what is left owed, which is what the queue is for. Unable to fail the
      * request, like the queue.
      *
      * On a fiber of its own, which the request does not wait for, as it does not wait for the queue's listener. Not
      * merely for speed: the ending is said by a caller still holding its session — `GameEngineService` says it from
      * inside one, and an automatic start from inside `ChallengeService`'s — and settling borrows a connection of its
      * own. Run in the request, it would wait for a connection while holding one, which with a pool of one is a
      * deadlock and with more is a way to exhaust it. On its own fiber it simply waits until one is given back.
      */
    def inline(
        settle: (GameId, MatchId) => IO[Unit],
        rank: GameId => IO[Unit],
        create: MatchDue => IO[Unit] = _ => IO.unit,
        checkMatch: (GameId, MatchId) => IO[Unit] = (_, _) => IO.unit
    ): MatchEndings =
        new MatchEndings {
            def ended(gameId: GameId, matchId: MatchId): IO[Unit] =
                background(settle(gameId, matchId), s"settling the end of match ${matchId.value} failed")

            def ratingsChanged(gameId: GameId): IO[Unit] =
                background(rank(gameId), s"ranking game ${gameId.value} failed")

            def due(message: MatchDue): IO[Unit] =
                background(create(message), s"creating match ${message.matchNo} of pool ${message.fixtureId} failed")

            def check(gameId: GameId, matchId: MatchId): IO[Unit] =
                background(checkMatch(gameId, matchId), s"checking match ${matchId.value} failed")

            private def background(work: IO[Unit], failed: String): IO[Unit] =
                work.handleErrorWith(e => IO.blocking(System.err.println(s"$failed: $e"))).start.void
        }

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
