package com.vivi.matchmaker.ending

import cats.effect.IO
import java.time.Instant
import upickle.default.{macroRW, read, write, ReadWriter}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import com.vivi.matchmaker.model.{GameId, MatchId, TournamentId}
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

    /** That a tournament's match is due to be created (new-design.md, Phase 5): its seats are settled, and the engine's
      * game is to be made. Said after the transaction that made it due has committed. Only a due match is created, and
      * the engine answers a create it already has with the game it made, so a message delivered twice makes one game.
      */
    def tournamentMatchDue(gameId: GameId, tournamentId: TournamentId, matchId: MatchId): IO[Unit] = IO.unit

    /** That a tournament with automatic rounds has finished a round, and its next is to be begun. */
    def tournamentRoundDue(gameId: GameId, tournamentId: TournamentId): IO[Unit] = IO.unit

    /** That a tournament's owner pressed Check round at `checkedAt`, and the round's matches are to be looked at:
      * clocks run out enforced, late choosers forfeited, matches still due queued again (`TournamentRounds.runCheck`).
      * On the queue rather than in the request, because each match may be a call to its engine, and a round of many
      * could outlast the API's thirty seconds.
      */
    def tournamentRoundCheck(gameId: GameId, tournamentId: TournamentId, checkedAt: Instant): IO[Unit] = IO.unit
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
    def parse(body: String): Either[String, MatchEnded | RatingsChanged] =
        try Right(read[MatchEnded](body))
        catch {
            case _: Exception =>
                try Right(read[RatingsChanged](body))
                catch { case e: Exception => Left(s"not a match ending: ${e.getMessage}") }
        }

    /** Any message the queue carries, most specific first: each of these reads as the less specific ones after it, by a
      * reader that ignores the fields it does not know.
      */
    def parseAny(body: String): Either[String, QueueMessage] =
        try Right(read[TournamentMatchDue](body))
        catch {
            case _: Exception =>
                try Right(read[TournamentRoundCheck](body))
                catch {
                    case _: Exception =>
                        try Right(read[TournamentRoundDue](body))
                        catch { case _: Exception => parse(body) }
                }
        }
}

/** A tournament's match, due to be created in its engine: the `match` row it already is (V52). */
case class TournamentMatchDue(gameId: Int, tournamentId: Long, matchId: String)

object TournamentMatchDue {
    given ReadWriter[TournamentMatchDue] = macroRW
}

/** A tournament with automatic rounds, whose next round is due to begin. Told from a game's ratings changing by its
  * `tournamentId`, and from a match due by having no pool.
  */
case class TournamentRoundDue(gameId: Int, tournamentId: Long)

object TournamentRoundDue {
    given ReadWriter[TournamentRoundDue] = macroRW
}

/** A tournament's round, to be checked because its owner asked at `checkedAt` (epoch milliseconds). Told from a round
  * due by `checkedAt`, which a round due does not have.
  */
case class TournamentRoundCheck(gameId: Int, tournamentId: Long, checkedAt: Long)

object TournamentRoundCheck {
    given ReadWriter[TournamentRoundCheck] = macroRW
}

/** Everything the ending queue carries. */
type QueueMessage = MatchEnded | RatingsChanged | TournamentMatchDue | TournamentRoundDue | TournamentRoundCheck

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

    override def tournamentMatchDue(gameId: GameId, tournamentId: TournamentId, matchId: MatchId): IO[Unit] =
        send(
          write(TournamentMatchDue(gameId.value, tournamentId.value, matchId.value)),
          s"match ${matchId.value} of tournament ${tournamentId.value} was not queued; Check round will create it"
        )

    override def tournamentRoundDue(gameId: GameId, tournamentId: TournamentId): IO[Unit] =
        send(
          write(TournamentRoundDue(gameId.value, tournamentId.value)),
          s"the next round of tournament ${tournamentId.value} was not queued; its owner can start it"
        )

    override def tournamentRoundCheck(gameId: GameId, tournamentId: TournamentId, checkedAt: Instant): IO[Unit] =
        send(
          write(TournamentRoundCheck(gameId.value, tournamentId.value, checkedAt.toEpochMilli)),
          s"the check of tournament ${tournamentId.value}'s round was not queued; its owner can check it again"
        )

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
        matchDue: (GameId, TournamentId, MatchId) => IO[Unit] = (_, _, _) => IO.unit,
        roundDue: (GameId, TournamentId) => IO[Unit] = (_, _) => IO.unit,
        roundCheck: (GameId, TournamentId) => IO[Unit] = (_, _) => IO.unit
    ): MatchEndings =
        new MatchEndings {
            def ended(gameId: GameId, matchId: MatchId): IO[Unit] =
                background(settle(gameId, matchId), s"settling the end of match ${matchId.value} failed")

            def ratingsChanged(gameId: GameId): IO[Unit] =
                background(rank(gameId), s"ranking game ${gameId.value} failed")

            override def tournamentMatchDue(gameId: GameId, tournamentId: TournamentId, matchId: MatchId): IO[Unit] =
                background(
                  matchDue(gameId, tournamentId, matchId),
                  s"creating match ${matchId.value} of tournament ${tournamentId.value} failed"
                )

            override def tournamentRoundDue(gameId: GameId, tournamentId: TournamentId): IO[Unit] =
                background(
                  roundDue(gameId, tournamentId),
                  s"starting the next round of tournament ${tournamentId.value} failed"
                )

            override def tournamentRoundCheck(
                gameId: GameId,
                tournamentId: TournamentId,
                checkedAt: Instant
            ): IO[Unit] =
                background(
                  roundCheck(gameId, tournamentId),
                  s"checking the round of tournament ${tournamentId.value} failed"
                )

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
