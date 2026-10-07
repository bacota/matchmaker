package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._

class TournamentParticipantRepoSpec extends FunSuite {
    TestMigration.ensure()

    private def run[A](f: Session[IO] => IO[A]): A = TestSession.resource.use(f).unsafeRunSync()

    /** A tournament with `n` entries, seeded 1 to n in entry order. */
    private def seeded(session: Session[IO], n: Int): IO[(Tournament, List[TournamentParticipant])] =
        for {
            made <- Tournaments.made(session)
            (g, _, t) = made
            entries <- (1 to n).toList.traverse(_ =>
                Tournaments
                    .player(session)
                    .flatMap(p =>
                        new EntryRepo(session).create(TournamentEntry(g.gameId, t.tournamentId, EntryId(0), p.playerId))
                    )
            )
            participants <- entries.zipWithIndex.traverse((e, i) =>
                new TournamentParticipantRepo(session).create(
                  TournamentParticipant(g.gameId, t.tournamentId, TournamentParticipantId(0), e.entryId, i + 1, i + 1)
                )
            )
        } yield (t, participants)

    test("the field reads back best seed first") {
        val (made, listed) = run { session =>
            for {
                s <- seeded(session, 3)
                (t, made) = s
                listed <- new TournamentParticipantRepo(session).list(t.gameId, t.tournamentId)
            } yield (made, listed)
        }
        assertEquals(listed, made)
    }

    // Reseeding swaps seeds in place. The unique constraint on seed is deferred to the commit, so the first half of a
    // swap does not collide with the second.
    test("two seeds swap inside one transaction, and a duplicate left at the commit is refused") {
        val (swapped, duplicate) = run { session =>
            val repo = new TournamentParticipantRepo(session)
            for {
                s <- seeded(session, 2)
                (t, List(first, second)) = s: @unchecked
                _ <- session.transaction.use { _ =>
                    repo.listForUpdate(t.gameId, t.tournamentId) *>
                        repo.setSeed(t.gameId, t.tournamentId, first.tournamentParticipantId, 2) *>
                        repo.setSeed(t.gameId, t.tournamentId, second.tournamentParticipantId, 1)
                }
                swapped <- repo.list(t.gameId, t.tournamentId)
                duplicate <- session.transaction
                    .use(_ => repo.setSeed(t.gameId, t.tournamentId, first.tournamentParticipantId, 1))
                    .attempt
            } yield (swapped.map(p => p.tournamentParticipantId -> p.seed), duplicate)
        }
        assertEquals(swapped.map(_._2), List(1, 2))
        assert(duplicate.isLeft)
    }

    test("withdrawing, a final rank and a ladder rank are each written and read back; the initial seed never moves") {
        val after = run { session =>
            val repo = new TournamentParticipantRepo(session)
            for {
                s <- seeded(session, 2)
                (t, List(first, _)) = s: @unchecked
                id = first.tournamentParticipantId
                _ <- repo.setWithdrawn(t.gameId, t.tournamentId, id, true)
                _ <- repo.setFinalRank(t.gameId, t.tournamentId, id, Some(3))
                _ <- repo.setLadderRank(t.gameId, t.tournamentId, id, -1)
                _ <- session.transaction.use(_ => repo.setSeed(t.gameId, t.tournamentId, id, 7))
                read <- session.transaction.use(_ => repo.readByEntryForUpdate(t.gameId, t.tournamentId, first.entryId))
            } yield read.get
        }
        assertEquals(
          (after.withdrawn, after.finalRank, after.ladderRank, after.seed, after.initialSeed),
          (true, Some(3), Some(-1), 7, 1)
        )
    }
}
