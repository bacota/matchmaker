package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model._

class MatchRepoSpec extends PropertySuite {
    property("create then read returns the match just created") {
        forAll(Generators.genString) { matchIdStr =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndChallenge <- Generators.gameWithChallenge(session)
                        (createdGame, challengeId) = gameAndChallenge
                        matchId = MatchId(matchIdStr)
                        m <- Generators.matchFrom(session, createdGame.gameId, matchId, challengeId)
                        created <- matchRepo.create(m)
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    property("update then read returns the match as it was rewritten, its friendliness included") {
        forAll(Generators.genString) { matchIdStr =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndChallenge <- Generators.gameWithChallenge(session)
                        (createdGame, challengeId) = gameAndChallenge
                        matchId = MatchId(matchIdStr)
                        m <- Generators.matchFrom(session, createdGame.gameId, matchId, challengeId)
                        _ <- matchRepo.create(m)
                        rewritten = m.copy(friendly = !m.friendly, isPublic = !m.isPublic)
                        _ <- matchRepo.update(rewritten)
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(rewritten)
                }
                .unsafeRunSync()
        }
    }

    test("a match is friendly unless it says otherwise") {
        val unsaid =
            Match(
              GameId(1),
              MatchId("m"),
              Some(ChallengeId(1)),
              PlayerId(1),
              "",
              None,
              java.time.Instant.EPOCH,
              None,
              "{}"
            )
        assert(unsaid.friendly)
    }

    private val seatOf: skunk.Query[(Int, Long), (Long, Int)] = {
        import skunk.implicits._
        import skunk.codec.all._
        sql"SELECT slot_id, seed FROM participant WHERE game_id = $int4 AND participant_id = $int8".query(int8 *: int4)
    }

    /** A tournament in a game of `gameType`, its first round, and one pool with one slot, by seed. */
    private def pool(session: skunk.Session[cats.effect.IO], gameType: GameType) =
        for {
            made <- Tournaments.made(session, gameType)
            (g, owner, t) = made
            fixtures = new FixtureRepo(session)
            _ <- fixtures.createRound(TournamentRound(g.gameId, t.tournamentId, 1))
            f <- fixtures.createFixture(com.vivi.matchmaker.model.Fixture(g.gameId, t.tournamentId, FixtureId(0), 1, 1))
            slot <- fixtures.createSlot(
              FixtureSlot(g.gameId, t.tournamentId, f.fixtureId, SlotId(0), SlotSource.Seed(1))
            )
        } yield (g, owner, t, f, slot)

    private def tournamentMatch(
        g: Game,
        owner: Player,
        t: Tournament,
        f: com.vivi.matchmaker.model.Fixture,
        matchNo: Int
    ): Match =
        Match(
          g.gameId,
          MatchId(java.util.UUID.randomUUID().toString),
          None,
          owner.playerId,
          "round 1",
          None,
          java.time.Instant.EPOCH,
          None,
          "{}",
          fixture = Some(MatchFixture(t.tournamentId, f.fixtureId, matchNo))
        )

    // A tournament match (V53) names its pool and its number there, and has no challenge.
    test("a tournament match reads back with its fixture, and a seat is written with its slot and seed") {
        val (read, seat, again) = TestSession.resource
            .use { session =>
                for {
                    made <- pool(session, GameType.Plain)
                    (g, owner, t, f, slot) = made
                    m = tournamentMatch(g, owner, t, f, 1)
                    _ <- new MatchRepo(session).create(m)
                    read <- new MatchRepo(session).read(g.gameId, m.matchId)
                    p <- new ParticipantRepo(session).create(
                      PlainParticipant(ParticipantId(0), g.gameId, m.matchId, owner.playerId, false, false, None, None),
                      1500,
                      seat = Some(TournamentSeat(t.tournamentId, f.fixtureId, slot.slotId, 1))
                    )
                    seat <- session.unique(seatOf)((g.gameId.value, p.participantId.value))
                    // Match 1 of the pool is made once: the claim a queued creation relies on.
                    again <- session.transaction
                        .use(_ => new MatchRepo(session).create(tournamentMatch(g, owner, t, f, 1)))
                        .attempt
                } yield (read, seat, again)
            }
            .unsafeRunSync()
        assertEquals(read.flatMap(_.fixture).map(_.matchNo), Some(1))
        assertEquals(seat._2, 1)
        assert(again.isLeft)
    }

    test("a match is refused that names both a challenge and a fixture") {
        val both = TestSession.resource
            .use { session =>
                for {
                    made <- pool(session, GameType.Character)
                    (g, owner, t, f, _) = made
                    challenge <- Generators.challengeIn(session, g)
                    both <- session.transaction
                        .use(_ =>
                            new MatchRepo(session)
                                .create(tournamentMatch(g, owner, t, f, 2).copy(challengeId = Some(challenge)))
                        )
                        .attempt
                } yield both
            }
            .unsafeRunSync()
        assert(both.isLeft)
    }
}
