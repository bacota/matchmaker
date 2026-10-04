package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{ChallengeRepo, GameRepo, ParticipantRepo, PlayerRepo, TestSession}

/** Players' Elo ratings in a game (V42): moved by a match that is not friendly as it completes, left alone by one that
  * is, and set outright by an admin of the game.
  */
class EloRatingServiceSpec extends PropertySuite {
    TestMigration.ensure()

    /* A ceiling on a case that has hung, not on how long one should take: each case builds a game,
     * several registrations, a challenge, acceptances and a start. See CLAUDE.md. */
    private val caseTimeout = 60.seconds

    /* `status` is what the engine says of a running match when asked, which the forfeit case sets
     * to confirm that a turn has run out. */
    private class StubEngine extends GameEngineClient {
        @volatile var status: GameStatusResponse = GameStatusResponse(completed = false, participants = Nil)

        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(status)
    }

    private val engine = new StubEngine
    private val services = TestServices.servicesWith(engine)

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def register(): IO[Player] = services.registration.register(unique("elo"), unique("elo-sub"))

    private def makeGame(): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "Duel",
                "Duel",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false, displayName = "First"),
                  GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false, displayName = "Second")
                ),
                Seq.empty,
                unique("duel")
              )
            )
        }

    /** A plain game of two required roles, an overall admin, an admin of the game, and two players. */
    private case class Fixture(game: Game, overall: Player, host: Player, first: Player, second: Player)

    private def fixture(): IO[Fixture] =
        for {
            game <- makeGame()
            overall <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(overall.copy(isAdmin = true)))
            host <- register()
            _ <- services.gameAdmins.grant(game.gameId, host.playerId, overall.externalId)
            first <- register()
            second <- register()
        } yield Fixture(game, overall.copy(isAdmin = true), host, first, second)

    /** A match between the two players, hosted by the game's admin, started and not yet over. */
    private def started(f: Fixture, friendly: Boolean, timeLimit: Option[Duration] = None): IO[MatchId] =
        for {
            created <- services.challenges.create(
              PlainChallenge(
                ChallengeId(0),
                f.host.playerId,
                "a match for you two",
                start = None,
                timeLimit = timeLimit,
                settings = "{}",
                gameId = f.game.gameId,
                gameRoleId = None,
                isOpen = false,
                friendly = friendly,
                autoStart = true
              ),
              f.host.externalId,
              Seq(
                Invite(f.first.playerId, Some(f.game.roles(0).gameRoleId)),
                Invite(f.second.playerId, Some(f.game.roles(1).gameRoleId))
              )
            )
            _ <- services.challenges
                .accept(f.game.gameId, created.challengeId, None, f.game.roles(0).gameRoleId, f.first.externalId)
            _ <- services.challenges
                .accept(f.game.gameId, created.challengeId, None, f.game.roles(1).gameRoleId, f.second.externalId)
            matchId <- TestSession.resource.use(session =>
                new ChallengeRepo(session).startedMatch(f.game.gameId, created.challengeId)
            )
        } yield matchId.get

    /** The engine reporting `winner` first and the other player second. */
    private def finish(f: Fixture, matchId: MatchId, winner: Player): IO[Unit] =
        for {
            seats <- TestSession.resource.use(session =>
                new ParticipantRepo(session).listForMatch(f.game.gameId, matchId)
            )
            results = seats.map { (p, _, _) =>
                val won = p.playerId == winner.playerId
                ReportedResult(p.participantId, rank = if (won) 1 else 2, scores = Map.empty, isWinner = won)
            }
            _ <- services.engine.recordResults(f.game.gameId, matchId, results, f.game.externalId)
        } yield ()

    private def ratings(f: Fixture): IO[Map[PlayerId, (Int, Int)]] =
        services.ratings
            .list(f.game.gameId, f.first.externalId)
            .map(_.map(r => r.player.playerId -> (r.rating, r.matches)).toMap)

    /** Each seat's V43 columns, by its player: what they began the match rated, and what it did to that. */
    private def eloSeats(f: Fixture, matchId: MatchId): IO[Map[PlayerId, (Option[Int], Option[Int])]] =
        TestSession.resource.use(session =>
            new ParticipantRepo(session)
                .eloSeatsForMatch(f.game.gameId, matchId)
                .map(_.map(row => row.playerId -> (row.eloStart, row.eloDelta)).toMap)
        )

    private def refusal[A](io: IO[A]): IO[Throwable] =
        io.attempt.map(_.swap.getOrElse(fail("expected a refusal, but it was allowed")))

    test("a match that is not friendly moves both players from the starting rating, once however often it is told") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            // The engine's callback retried: the match is already completed, so nothing moves again.
            _ <- finish(f, matchId, f.first)
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (f, now, recorded)
        val (f, now, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(now, Map(f.first.playerId -> (1516, 1), f.second.playerId -> (1484, 1)))
        assertEquals(
          recorded,
          Map(f.first.playerId -> (Some(1500), Some(16)), f.second.playerId -> (Some(1500), Some(-16)))
        )
    }

    test("the delta is worked out from the ratings the match began at, and added to the rating as it is now") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            // Changed while the match is played. The match was begun at 1500 apiece, and is rated as
            // such; the change stands, and the delta is added to it.
            _ <- services.ratings.set(f.game.gameId, f.first.playerId, 1700, f.host.externalId)
            _ <- finish(f, matchId, f.first)
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (f, now, recorded)
        val (f, now, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          recorded,
          Map(f.first.playerId -> (Some(1500), Some(16)), f.second.playerId -> (Some(1500), Some(-16)))
        )
        assertEquals(now, Map(f.first.playerId -> (1716, 1), f.second.playerId -> (1484, 1)))
    }

    test("a turn that runs out in a match that is not friendly is a loss to the player who ran out") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false, timeLimit = Some(Duration.ofMinutes(10)))
            seats <- TestSession.resource.use(session =>
                new ParticipantRepo(session).listForMatch(f.game.gameId, matchId).map(_.map(_._1))
            )
            first = seats.find(_.playerId == f.first.playerId).get
            second = seats.find(_.playerId == f.second.playerId).get
            // The second player moved an hour ago, against a ten-minute limit: the first is fifty
            // minutes over, and the engine, when asked, says the same.
            anHourAgo <- IO.realTimeInstant.map(_.minusSeconds(3600))
            m <- services.engine.read(f.game.gameId, matchId, f.first.externalId)
            _ <- services.engine.recordMove(
              f.game.gameId,
              matchId,
              moved = second.participantId,
              next = List(first.participantId),
              takenAt = anHourAgo,
              startedAt = m.start,
              callerExternalId = f.game.externalId
            )
            _ <- IO {
                engine.status = GameStatusResponse(
                  completed = false,
                  participants = List(
                    EngineParticipantStatus(first.participantId.value, true, false, Some(anHourAgo)),
                    EngineParticipantStatus(second.participantId.value, false, false, None)
                  )
                )
            }
            refreshed <- services.engine.refresh(f.game.gameId, matchId, f.second.externalId)
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (f, refreshed, now, recorded)
        val (f, refreshed, now, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assert(refreshed.completed, refreshed)
        assertEquals(now, Map(f.first.playerId -> (1484, 1), f.second.playerId -> (1516, 1)))
        assertEquals(
          recorded.view.mapValues(_._2).toMap,
          Map(f.first.playerId -> Some(-16), f.second.playerId -> Some(16))
        )
    }

    test("a friendly match records what its players began it rated, and moves nobody's rating nor gives anybody one") {
        val result = for {
            f <- fixture()
            _ <- services.ratings.set(f.game.gameId, f.first.playerId, 1600, f.host.externalId)
            matchId <- started(f, friendly = true)
            _ <- finish(f, matchId, f.first)
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (f, now, recorded)
        val (f, now, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(now, Map(f.first.playerId -> (1600, 0)))
        assertEquals(recorded, Map(f.first.playerId -> (Some(1600), None), f.second.playerId -> (Some(1500), None)))
    }

    test("a rating an admin of the game set is where the next rated match moves it from") {
        val result = for {
            f <- fixture()
            set <- services.ratings.set(f.game.gameId, f.first.playerId, 1600, f.host.externalId)
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            now <- ratings(f)
        } yield (f, set, now)
        val (f, set, now) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(set, EloRating(PublicPlayer(f.first.playerId, f.first.nickname), 1600, 0))
        // Expected 0.64 for the favourite: 32 * 0.36 is 11.5, rounded to 12.
        assertEquals(now, Map(f.first.playerId -> (1612, 1), f.second.playerId -> (1488, 1)))
    }

    test("setting a rating leaves alone how many matches stand behind it, and the list is highest first") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            _ <- services.ratings.set(f.game.gameId, f.second.playerId, 2000, f.overall.externalId)
            listed <- services.ratings.list(f.game.gameId, f.host.externalId)
        } yield (f, listed)
        val (f, listed) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          listed.map(r => (r.player.playerId, r.rating, r.matches)),
          List((f.second.playerId, 2000, 1), (f.first.playerId, 1516, 1))
        )
    }

    test("only an admin may set a rating, within the range, of a player and game that exist") {
        val result = for {
            f <- fixture()
            g <- fixture()
            byPlayer <- refusal(services.ratings.set(f.game.gameId, f.first.playerId, 1700, f.first.externalId))
            // An admin of another game is nobody special here.
            byOtherAdmin <- refusal(services.ratings.set(f.game.gameId, f.first.playerId, 1700, g.host.externalId))
            tooHigh <- refusal(
              services.ratings.set(f.game.gameId, f.first.playerId, EloRating.maximum + 1, f.host.externalId)
            )
            noPlayer <- refusal(services.ratings.set(f.game.gameId, PlayerId(-1), 1700, f.host.externalId))
            noGame <- refusal(services.ratings.set(GameId(-1), f.first.playerId, 1700, f.overall.externalId))
            now <- ratings(f)
        } yield (byPlayer, byOtherAdmin, tooHigh, noPlayer, noGame, now)
        val (byPlayer, byOtherAdmin, tooHigh, noPlayer, noGame, now) = result.timeout(caseTimeout).unsafeRunSync()
        assert(byPlayer.isInstanceOf[UnauthorizedError], byPlayer)
        assert(byOtherAdmin.isInstanceOf[UnauthorizedError], byOtherAdmin)
        assert(tooHigh.isInstanceOf[ValidationError], tooHigh)
        assert(noPlayer.isInstanceOf[NotFoundError], noPlayer)
        assert(noGame.isInstanceOf[NotFoundError], noGame)
        assertEquals(now, Map.empty[PlayerId, (Int, Int)])
    }

    test("the ratings of a game that does not exist are not found") {
        val result = for {
            player <- register()
            refused <- refusal(services.ratings.list(GameId(-1), player.externalId))
        } yield refused
        val refused = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.isInstanceOf[NotFoundError], refused)
    }
}
