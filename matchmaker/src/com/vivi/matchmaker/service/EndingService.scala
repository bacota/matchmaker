package com.vivi.matchmaker.service

import cats.effect.IO
import java.time.{Duration, Instant}
import com.vivi.matchmaker.engine.GameEngineClient
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.persistence.{ArchiveRepo, GameApiKeyRepo}
import com.vivi.matchmaker.persistence.ArchiveRepo.EndingRow

/** Whether a match's end is settled, or something is still owed and it is to be tried again later. */
enum Settlement {
    case Settled
    case Owed(why: String)
}

/** Settles what a match's end owes, once the match has ended (`MatchEndings`). Run by the listener its ending is queued
  * to, and nowhere on a player's request path except where there is no queue.
  *
  *   - A completed match is archived. Archiving is the engine's: it uploads the match itself once its results are in,
  *     and the first look here usually finds that done. One that is not, once its engine has had [[grace]] to do it, is
  *     prompted through the engine's status call, as an engine is prompted to archive a finished match it still holds
  *     (`GameEngine.archiveIfFinished`).
  *   - A cancelled match's engine is told, so that it drops the match, and that it heard is recorded.
  *
  * Its rating is not here: that is moved by the call that ends the match, in its transaction, so that it neither waits
  * on the queue nor depends on it.
  *
  * Every part is safe to do twice — an archive confirmed once is answered as such, a cancel already heard is not sent
  * again — because the queue delivers at least once, and because what is owed is answered [[Settlement.Owed]] and the
  * whole of it is tried again.
  *
  * Every engine is asked with no transaction open, per the rule on external calls.
  */
class EndingService(
    sessionPool: SessionPool,
    engine: GameEngineClient,
    now: () => Instant = () => Instant.now(),
    /** How long after a match completes its engine is left to archive it before being prompted. */
    grace: Duration = Duration.ofMinutes(5)
) {

    def settle(gameId: GameId, matchId: MatchId): IO[Settlement] =
        sessionPool.use(session => new ArchiveRepo(session).ending(gameId, matchId)).flatMap {
            case Some(row) if row.cancelled             => release(row)
            case Some(row) if row.completedAt.isDefined => archive(row)
            // Not over, or not there: nothing about its end is owed, and nothing would come of asking again.
            case _ => IO.pure(Settlement.Settled)
        }

    private def archive(row: EndingRow): IO[Settlement] =
        (row.archivedAt, row.statusUrl, row.completedAt) match {
            case (Some(_), _, _) => IO.pure(Settlement.Settled)
            // Never created in the engine, or created by one that gave no status url: nothing to prompt.
            case (_, None, _) => IO.pure(Settlement.Settled)
            case (_, _, Some(at)) if now().isBefore(at.plus(grace)) =>
                IO.pure(Settlement.Owed("it finished moments ago, and its engine is archiving it"))
            case (_, Some(url), _) =>
                for {
                    key <- sessionPool.use(session => new GameApiKeyRepo(session).forGame(row.gameId))
                    // The answer is not wanted: asking is what prompts the engine, and whether it worked is read
                    // back from the match below.
                    _ <- engine
                        .status(url, key, None)
                        .void
                        .handleErrorWith(e =>
                            log(s"prompting the engine to archive match ${row.matchId.value} failed: $e")
                        )
                    archived <- sessionPool.use(session =>
                        new ArchiveRepo(session).read(row.gameId, row.matchId).map(_.exists(_.archivedAt.isDefined))
                    )
                } yield if (archived) Settlement.Settled else Settlement.Owed("its engine has not archived it")
        }

    /* An engine that gave no cancel url when the game was created is not told at all, and its board stays up --
     * matchmaker refuses its callbacks for the match either way. What is recorded afterwards is a single
     * conditional update, which needs no lock. */
    private def release(row: EndingRow): IO[Settlement] =
        row.cancelUrl.filter(_ => !row.released) match {
            case None => IO.pure(Settlement.Settled)
            case Some(url) =>
                sessionPool
                    .use(session => new GameApiKeyRepo(session).forGame(row.gameId))
                    .flatMap(key => engine.cancel(url, key))
                    .attempt
                    .flatMap {
                        case Right(_) =>
                            sessionPool
                                .use(session => new ArchiveRepo(session).recordReleased(row.gameId, row.matchId))
                                .as(Settlement.Settled)
                        case Left(e) => IO.pure(Settlement.Owed(s"telling its engine it was cancelled failed: $e"))
                    }
        }

    private def log(message: String): IO[Unit] = IO.blocking(System.err.println(message))
}
