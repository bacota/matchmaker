package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.{Duration, Instant}
import com.vivi.matchmaker.archive.ArchiveBucket
import com.vivi.matchmaker.engine.GameEngineClient
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.persistence.{ArchiveRepo, GameApiKeyRepo}

/** What one run of the sweep did. `prompted` is how many engines it asked to archive a match, and `released` how many
  * cancels an engine acknowledged — not how many it tried, since a failed one is swallowed and logged.
  * `stillUnarchived` and `stillUnreleased` are the matches it asked about and that are still owed afterwards — the ones
  * worth a look, if they keep turning up. `deferred` is how many owed matches it did not reach before its deadline,
  * which the next run starts with.
  */
case class SweepReport(
    prompted: Int,
    stillUnarchived: List[MatchId],
    released: Int,
    stillUnreleased: List[MatchId],
    deferred: Int = 0,
    // Archives moved to the bucket their match's friendliness says, after a move made when an admin
    // changed it failed part way (V44); and the ones still in the wrong one.
    moved: Int = 0,
    stillMisplaced: List[MatchId] = Nil
)

/** Catches what archiving and cancelling leave owed when something does not answer at the moment it should
  * (archiving-matches-plan.md). Run on a schedule; nothing here is on any player's request path.
  *
  *   - A cancelled match whose engine never acknowledged the cancel: it is told again.
  *   - A completed match never archived: its engine finished it, and then failed to archive it — or never reported its
  *     result, so matchmaker refused the archive. The engine is asked for the match's status, which is how an engine is
  *     prompted to archive a finished match it still holds (`GameEngine.archiveIfFinished`).
  *   - An archive in the wrong bucket: a game's admin changed whether its match was friendly after it was archived, and
  *     the move that follows failed part way (`ArchiveService.relocate`). It is moved again.
  *
  * Cancels first: there are few of them and each is one quick call, so a long backlog of matches to archive — the
  * matches finished before archiving existed, the first time it runs — cannot crowd them out.
  *
  * Run once a day, with no limit on how many matches a run takes on, only on how long it runs: given `deadline`, it
  * starts on no further match once that has passed, and the next run carries on. Each match is asked about at most once
  * every [[retryAfter]] — a little under a day, so that a daily run starting a few seconds early does not skip a match
  * a day — recorded as `swept_at` before it is asked. The matches asked about longest ago go last, so one that can
  * never be settled — an engine that has lost the match, or has gone — does not hold the front of every run. A
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
    grace: Duration = Duration.ofHours(1),
    retryAfter: Duration = Duration.ofHours(20),
    /** One game's matches only, rather than every game's: for a run by hand against one engine, and for the tests. */
    game: Option[GameId] = None,
    /** What moves an archive between buckets; none, and misplaced archives are left for a run that has one. */
    archives: Option[ArchiveService] = None
) {

    /** One run. `deadline` is when to stop starting on matches; none, for a run by hand that may take as long as it
      * needs.
      */
    def run(deadline: Option[Instant] = None): IO[SweepReport] =
        for {
            released <- release(deadline)
            relocated <- relocate(deadline)
            prompted <- prompt(deadline)
        } yield SweepReport(
          prompted.asked,
          prompted.still,
          released.asked - released.still.size,
          released.still,
          prompted.left + released.left + relocated.left,
          relocated.asked - relocated.still.size,
          relocated.still
        )

    private case class Pass(asked: Int, still: List[MatchId], left: Int)

    /* The rows a deadline leaves time for, in order: each is checked against the clock just before it is started, so
     * a run never begins a match after its deadline, however long the one before took. */
    private def within[A](deadline: Option[Instant], rows: List[A])(f: A => IO[Unit]): IO[List[A]] =
        // Built backwards and turned round once: appending would copy the list for every match, and the
        // first run after a deploy may have a long backlog.
        rows
            .foldLeftM(List.empty[A]) { (done, row) =>
                if (deadline.exists(d => !now().isBefore(d))) IO.pure(done)
                else f(row).as(row :: done)
            }
            .map(_.reverse)

    private def prompt(deadline: Option[Instant]): IO[Pass] = {
        val at = now()
        for {
            owed <- sessionPool.use(session =>
                new ArchiveRepo(session).listUnarchived(at.minus(grace), at.minus(retryAfter), game)
            )
            asked <- within(deadline, owed) { row =>
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
                asked.filterA(row =>
                    new ArchiveRepo(session).read(row.gameId, row.matchId).map(_.forall(_.archivedAt.isEmpty))
                )
            }
        } yield Pass(asked.size, still.map(_.matchId), owed.size - asked.size)
    }

    private def release(deadline: Option[Instant]): IO[Pass] = {
        val at = now()
        for {
            owed <- sessionPool.use(session => new ArchiveRepo(session).listUnreleased(at.minus(retryAfter), game))
            asked <- within(deadline, owed) { row =>
                sessionPool.use(session => new ArchiveRepo(session).recordSwept(row.gameId, row.matchId)) *>
                    matches.releaseEngine(row.gameId, row.matchId)
            }
            still <- sessionPool.use { session =>
                asked.filterA(row => new ArchiveRepo(session).isReleased(row.gameId, row.matchId).map(!_))
            }
        } yield Pass(asked.size, still.map(_.matchId), owed.size - asked.size)
    }

    /* Before the prompts, for the reason cancels are: few of them, each quick. */
    private def relocate(deadline: Option[Instant]): IO[Pass] =
        archives.fold(IO.pure(Pass(0, Nil, 0))) { service =>
            for {
                owed <- sessionPool.use(session =>
                    new ArchiveRepo(session).listMisplaced(now().minus(retryAfter), game)
                )
                asked <- within(deadline, owed) { row =>
                    sessionPool.use(session => new ArchiveRepo(session).recordSwept(row.gameId, row.matchId)) *>
                        service
                            .relocate(row.gameId, row.matchId)
                            .handleErrorWith(e => log(s"moving the archive of match ${row.matchId.value} failed: $e"))
                }
                still <- sessionPool.use { session =>
                    asked.filterA(row =>
                        new ArchiveRepo(session)
                            .read(row.gameId, row.matchId)
                            .map(
                              _.exists(r =>
                                  r.expiredAt.isEmpty && ArchiveService.bucketOf(r) != ArchiveBucket.of(r.friendly)
                              )
                            )
                    )
                }
            } yield Pass(asked.size, still.map(_.matchId), owed.size - asked.size)
        }

    private def log(message: String): IO[Unit] = IO.blocking(System.err.println(message))
}
