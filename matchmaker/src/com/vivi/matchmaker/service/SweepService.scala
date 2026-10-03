package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.{Duration, Instant}
import com.vivi.matchmaker.engine.GameEngineClient
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.persistence.{ArchiveRepo, GameApiKeyRepo}

/** What one run of the sweep did. `stillUnarchived` and `stillUnreleased` are the matches it asked about and that are
  * still owed afterwards — the ones worth a look, if they keep turning up.
  */
case class SweepReport(
    prompted: Int,
    stillUnarchived: List[MatchId],
    released: Int,
    stillUnreleased: List[MatchId]
)

/** Catches what archiving and cancelling leave owed when something does not answer at the moment it should
  * (archiving-matches-plan.md). Run on a schedule; nothing here is on any player's request path.
  *
  *   - A completed match never archived: its engine finished it, and then failed to archive it — or never reported its
  *     result, so matchmaker refused the archive. The engine is asked for the match's status, which is how an engine is
  *     prompted to archive a finished match it still holds (`GameEngine.archiveIfFinished`).
  *   - A cancelled match whose engine never acknowledged the cancel: it is told again.
  *
  * Each match is asked about at most once every [[retryAfter]], recorded as `swept_at` before it is asked, so that one
  * that can never be settled — an engine that has lost the match, or has gone — does not hold the front of every run. A
  * completed match is left alone for [[grace]] first: its engine is archiving it already.
  *
  * Every engine is asked with no transaction open, per the rule on external calls, and one failing does not stop the
  * rest.
  */
class SweepService(
    sessionPool: SessionPool,
    engine: GameEngineClient,
    matches: MatchService,
    now: () => Instant = () => Instant.now(),
    batch: Int = 50,
    grace: Duration = Duration.ofHours(1),
    retryAfter: Duration = Duration.ofDays(1),
    /** One game's matches only, rather than every game's: for a run by hand against one engine, and for the tests. */
    game: Option[GameId] = None
) {

    def run(): IO[SweepReport] =
        for {
            prompted <- prompt()
            released <- release()
        } yield SweepReport(prompted._1, prompted._2, released._1, released._2)

    private def prompt(): IO[(Int, List[MatchId])] = {
        val at = now()
        for {
            owed <- sessionPool.use(session =>
                new ArchiveRepo(session).listUnarchived(at.minus(grace), at.minus(retryAfter), batch, game)
            )
            _ <- owed.traverse_ { row =>
                for {
                    key <- sessionPool.use { session =>
                        new ArchiveRepo(session).recordSwept(row.gameId, row.matchId) *>
                            new GameApiKeyRepo(session).forGame(row.gameId)
                    }
                    // The answer is not wanted: asking is what prompts the engine, and whether it worked is
                    // read back from the match below.
                    _ <- engine
                        .status(row.statusUrl, key, None)
                        .void
                        .handleErrorWith(e =>
                            log(s"prompting the engine to archive match ${row.matchId.value} failed: $e")
                        )
                } yield ()
            }
            still <- sessionPool.use { session =>
                owed.filterA(row =>
                    new ArchiveRepo(session).read(row.gameId, row.matchId).map(_.forall(_.archivedAt.isEmpty))
                )
            }
        } yield (owed.size, still.map(_.matchId))
    }

    private def release(): IO[(Int, List[MatchId])] = {
        val at = now()
        for {
            owed <- sessionPool.use(session =>
                new ArchiveRepo(session).listUnreleased(at.minus(retryAfter), batch, game)
            )
            _ <- owed.traverse_ { row =>
                sessionPool.use(session => new ArchiveRepo(session).recordSwept(row.gameId, row.matchId)) *>
                    matches.releaseEngine(row.gameId, row.matchId)
            }
            still <- sessionPool.use { session =>
                owed.filterA(row => new ArchiveRepo(session).isReleased(row.gameId, row.matchId).map(!_))
            }
        } yield (owed.size, still.map(_.matchId))
    }

    private def log(message: String): IO[Unit] = IO.blocking(System.err.println(message))
}
