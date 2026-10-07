package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import skunk.{Command, Session}
import skunk.implicits._
import skunk.codec.all.{int4, int8}
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._

class FixtureRepoSpec extends FunSuite {
    TestMigration.ensure()

    private def run[A](f: Session[IO] => IO[A]): A = TestSession.resource.use(f).unsafeRunSync()

    test(
      "rounds read back in order with their overrides, and are stamped started with live, then checked and completed"
    ) {
        val (rounds, stamped) = run { session =>
            val repo = new FixtureRepo(session)
            for {
                made <- Tournaments.made(session)
                (g, _, t) = made
                _ <- repo.createRound(TournamentRound(g.gameId, t.tournamentId, 2, reseed = true))
                _ <- repo.createRound(
                  TournamentRound(
                    g.gameId,
                    t.tournamentId,
                    1,
                    duration = Some(Duration.ofHours(3)),
                    poolSize = Some(4),
                    minPoolAdvance = Some(2),
                    tiebreaker = Some(Tiebreaker.Rematch)
                  )
                )
                rounds <- repo.listRounds(g.gameId, t.tournamentId)
                stamped <- session.transaction.use { _ =>
                    for {
                        _ <- repo.readRoundForUpdate(g.gameId, t.tournamentId, 1)
                        _ <- repo.startRound(g.gameId, t.tournamentId, 1, live = true)
                        _ <- repo.checkRound(g.gameId, t.tournamentId, 1)
                        _ <- repo.completeRound(g.gameId, t.tournamentId, 1)
                        read <- repo.readRoundForUpdate(g.gameId, t.tournamentId, 1)
                    } yield read.get
                }
            } yield (rounds, stamped)
        }
        assertEquals(rounds.map(_.round), List(1, 2))
        assertEquals(rounds.head.duration, Some(Duration.ofHours(3)))
        assertEquals(rounds.head.tiebreaker, Some(Tiebreaker.Rematch))
        assert(rounds(1).reseed)
        assert(stamped.live && stamped.started && stamped.completed && stamped.checkedAt.isDefined)
    }

    test("pools and their slots read back in place, each slot by its source, and a slot is filled once") {
        val (fixtures, slots, filled, byeFilled) = run { session =>
            val repo = new FixtureRepo(session)
            for {
                made <- Tournaments.made(session)
                (g, _, t) = made
                _ <- repo.createRound(TournamentRound(g.gameId, t.tournamentId, 1))
                _ <- repo.createRound(TournamentRound(g.gameId, t.tournamentId, 2))
                b <- repo.createFixture(Fixture(g.gameId, t.tournamentId, FixtureId(0), 1, 2))
                a <- repo.createFixture(Fixture(g.gameId, t.tournamentId, FixtureId(0), 1, 1))
                f <- repo.createFixture(Fixture(g.gameId, t.tournamentId, FixtureId(0), 2, 1))
                seed1 <- repo.createSlot(
                  FixtureSlot(g.gameId, t.tournamentId, a.fixtureId, SlotId(0), SlotSource.Seed(1))
                )
                seed4 <- repo.createSlot(
                  FixtureSlot(g.gameId, t.tournamentId, a.fixtureId, SlotId(0), SlotSource.Seed(4))
                )
                bye <- repo.createSlot(FixtureSlot(g.gameId, t.tournamentId, b.fixtureId, SlotId(0), SlotSource.Bye))
                winner <- repo.createSlot(
                  FixtureSlot(g.gameId, t.tournamentId, f.fixtureId, SlotId(0), SlotSource.Winner(a.fixtureId, 1))
                )
                // Somebody to fill a slot with.
                entrant <- Tournaments.player(session)
                entry <- new EntryRepo(session).create(
                  TournamentEntry(g.gameId, t.tournamentId, EntryId(0), entrant.playerId)
                )
                p <- new TournamentParticipantRepo(session).create(
                  TournamentParticipant(g.gameId, t.tournamentId, TournamentParticipantId(0), entry.entryId, 1, 1)
                )
                other <- Tournaments.player(session)
                otherEntry <- new EntryRepo(session).create(
                  TournamentEntry(g.gameId, t.tournamentId, EntryId(0), other.playerId)
                )
                q <- new TournamentParticipantRepo(session).create(
                  TournamentParticipant(g.gameId, t.tournamentId, TournamentParticipantId(0), otherEntry.entryId, 2, 2)
                )
                _ <- repo.fill(g.gameId, t.tournamentId, a.fixtureId, seed1.slotId, p.tournamentParticipantId)
                // A second fill changes nothing: a slot's occupant is settled once.
                _ <- repo.fill(g.gameId, t.tournamentId, a.fixtureId, seed1.slotId, q.tournamentParticipantId)
                _ <- repo.fill(g.gameId, t.tournamentId, b.fixtureId, bye.slotId, q.tournamentParticipantId)
                fixtures <- repo.listFixtures(g.gameId, t.tournamentId, 1)
                slots <- repo.listSlots(g.gameId, t.tournamentId)
            } yield (
              fixtures.map(_.position),
              slots.map(_.source),
              slots.find(_.slotId == seed1.slotId).flatMap(_.occupant).contains(p.tournamentParticipantId),
              slots.find(_.slotId == bye.slotId).flatMap(_.occupant)
            )
        }
        assertEquals(fixtures, List(1, 2))
        assertEquals(slots.take(3), List(SlotSource.Seed(1), SlotSource.Seed(4), SlotSource.Bye))
        assert(slots(3).isInstanceOf[SlotSource.Winner])
        assert(filled)
        assertEquals(byeFilled, None)
    }

    private val slotWithByeAndSeed: Command[(Int, Long, Long)] =
        sql"""INSERT INTO fixture_slot (game_id, tournament_id, fixture_id, bye, seed)
          VALUES ($int4, $int8, $int8, true, 1)""".command

    private val slotWithNoSource: Command[(Int, Long, Long)] =
        sql"""INSERT INTO fixture_slot (game_id, tournament_id, fixture_id) VALUES ($int4, $int8, $int8)""".command

    test("the database refuses a slot with two sources, or none") {
        val refused = run { session =>
            for {
                made <- Tournaments.made(session)
                (g, _, t) = made
                _ <- new FixtureRepo(session).createRound(TournamentRound(g.gameId, t.tournamentId, 1))
                f <- new FixtureRepo(session).createFixture(Fixture(g.gameId, t.tournamentId, FixtureId(0), 1, 1))
                key = (g.gameId.value, t.tournamentId.value, f.fixtureId.value)
                both <- session.transaction.use(_ => session.execute(slotWithByeAndSeed)(key)).attempt
                neither <- session.transaction.use(_ => session.execute(slotWithNoSource)(key)).attempt
            } yield (both.isLeft, neither.isLeft)
        }
        assertEquals(refused, (true, true))
    }
}
