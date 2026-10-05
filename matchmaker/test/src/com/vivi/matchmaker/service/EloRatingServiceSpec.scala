package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    ChallengeRepo,
    EloRatingRepo,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    PlayerRepo,
    ResultRepo,
    TestSession
}

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

    /** An open challenge the game's admin offers and will not play in, which anybody may accept — `first` included, for
      * both of its seats when the challenge is friendly.
      */
    private def openChallenge(f: Fixture, friendly: Boolean): IO[Challenge] =
        services.challenges.create(
          PlainChallenge(
            ChallengeId(0),
            f.host.playerId,
            "anybody",
            start = None,
            timeLimit = None,
            settings = "{}",
            gameId = f.game.gameId,
            gameRoleId = None,
            isOpen = true,
            friendly = friendly,
            autoStart = true
          ),
          f.host.externalId
        )

    /** A friendly match `first` plays both seats of.
      *
      * Not a thing the services will make — a challenge takes each player once — so the second player's seat is handed
      * to the first in the table, which is how a match would look if that rule were ever relaxed.
      */
    private def playingThemselves(f: Fixture): IO[MatchId] =
        for {
            matchId <- started(f, friendly = true)
            _ <- TestSession.resource.use { session =>
                val repo = new ParticipantRepo(session)
                repo.listForMatch(f.game.gameId, matchId).flatMap { seats =>
                    seats.map(_._1).find(_.playerId == f.second.playerId) match {
                        case Some(seat: PlainParticipant) => repo.update(seat.copy(playerId = f.first.playerId))
                        case other                        => IO.raiseError(new IllegalStateException(s"seat: $other"))
                    }
                }
            }
        } yield matchId

    /** The engine reporting `winner` first and the other player second. */
    private def finish(f: Fixture, matchId: MatchId, winner: Player, through: Services[String] = services): IO[Unit] =
        for {
            seats <- TestSession.resource.use(session =>
                new ParticipantRepo(session).listForMatch(f.game.gameId, matchId)
            )
            results = seats.map { (p, _, _) =>
                val won = p.playerId == winner.playerId
                ReportedResult(p.participantId, rank = if (won) 1 else 2, scores = Map.empty, isWinner = won)
            }
            _ <- through.engine.recordResults(f.game.gameId, matchId, results, f.game.externalId)
        } yield ()

    /** The two players' ratings as anybody may see them: a player no rated match has moved and no admin has set has
      * none. Not by the leaderboard, which shows a player only once the listener has placed them.
      */
    private def ratings(f: Fixture): IO[Map[PlayerId, (Int, Int)]] =
        TestSession.resource.use { session =>
            val repo = new EloRatingRepo(session)
            List(f.first, f.second)
                .traverse(p =>
                    repo.readRated(f.game.gameId, p.playerId).map(_.map(r => p.playerId -> (r.rating, r.matches)))
                )
                .map(_.flatten.toMap)
        }

    /** The two players' rating rows as stored, listed or not: the list leaves out a player no rated match has moved and
      * no admin has set, and the row is still there for the match that began it.
      */
    private def storedRatings(f: Fixture): IO[Map[PlayerId, (Int, Int)]] =
        TestSession.resource.use { session =>
            val repo = new EloRatingRepo(session)
            List(f.first, f.second)
                .traverse(p =>
                    repo.read(f.game.gameId, p.playerId).map(_.map(r => p.playerId -> (r.rating, r.matches)))
                )
                .map(_.flatten.toMap)
        }

    /** What V43 holds for each seat, by its player: what they began the match rated, from the seat, and what the match
      * did to that, from its result — none for a seat with no result, or a result with no delta.
      */
    private def eloSeats(f: Fixture, matchId: MatchId): IO[Map[PlayerId, (Int, Option[Int])]] =
        TestSession.resource.use { session =>
            val results = new ResultRepo(session)
            new ParticipantRepo(session)
                .eloSeatsForMatch(f.game.gameId, matchId)
                .flatMap(_.traverse { row =>
                    results
                        .read(f.game.gameId, row.participantId)
                        .map(result => row.playerId -> (row.eloStart, result.flatMap(_.eloDelta)))
                })
                .map(_.toMap)
        }

    /** The match's result rows as `player` is shown them: each seat's Elo as it began, and the match's change to it. */
    private def resultElo(player: Player, matchId: MatchId): IO[Map[String, (Int, Option[Int])]] =
        services.matches
            .results(player.externalId)
            .map(_.filter(_.matchId == matchId).map(r => r.nickname -> (r.eloStart, r.eloDelta)).toMap)

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
            shown <- resultElo(f.second, matchId)
        } yield (f, now, recorded, shown)
        val (f, now, recorded, shown) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(now, Map(f.first.playerId -> (1516, 1), f.second.playerId -> (1484, 1)))
        assertEquals(
          shown,
          Map(f.first.nickname -> (1500, Some(16)), f.second.nickname -> (1500, Some(-16)))
        )
        assertEquals(
          recorded,
          Map(f.first.playerId -> (1500, Some(16)), f.second.playerId -> (1500, Some(-16)))
        )
    }

    test("settling a rated match's ending places its players on the leaderboard") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.second)
            // Whatever it says of the archive, which is not this test's business.
            _ <- services.ending.settle(f.game.gameId, matchId)
            board <- services.ratings.leaderboard(f.game.gameId, 0, f.first.externalId)
        } yield (f, board)
        val (f, board) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          board.ratings.map(r => (r.player.playerId, r.rank)),
          List(f.second.playerId -> Some(1), f.first.playerId -> Some(2))
        )
    }

    test("a player's standing is their place, the rating it was worked out from, and their rating now") {
        // Ended with nothing settling it in the background, which would place the moved rating whenever it ran: the
        // listener is this test's to run.
        val unsettled = TestServices.servicesWith(engine, matchEndings = Some(MatchEndings.disabled))
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first, unsettled)
            _ <- services.ending.settle(f.game.gameId, matchId)
            winner <- services.ratings.standing(f.game.gameId, f.first.playerId, f.second.externalId)
            // A rating moved since the player was placed: the place, and what it was worked out from, trail it until
            // the listener places them again.
            _ <- TestSession.resource.use(session =>
                new EloRatingRepo(session).played(f.game.gameId, f.second.playerId, 40, MatchRecord())
            )
            moved <- services.ratings.standing(f.game.gameId, f.second.playerId, f.first.externalId)
            board <- services.ratings.leaderboard(f.game.gameId, 0, f.first.externalId)
            unrated <- refusal(services.ratings.standing(f.game.gameId, f.host.playerId, f.first.externalId))
            noGame <- refusal(services.ratings.standing(GameId(-1), f.first.playerId, f.first.externalId))
        } yield (winner, moved, board, unrated, noGame)
        val (winner, moved, board, unrated, noGame) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals((winner.rank, winner.rankedRating, winner.rating), (Some(1), Some(1516), 1516))
        assertEquals((moved.rank, moved.rankedRating, moved.rating), (Some(2), Some(1484), 1524))
        // The leaderboard is in order by what it was placed by, which is what it shows.
        assertEquals(
          board.ratings.map(r => (r.rank, r.rankedRating)),
          List((Some(1), Some(1516)), (Some(2), Some(1484)))
        )
        assert(unrated.isInstanceOf[NotFoundError], unrated)
        assert(noGame.isInstanceOf[NotFoundError], noGame)
    }

    test("finding players in the rankings by the start of a nickname finds the rated ones, whatever the case") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            _ <- services.ending.settle(f.game.gameId, matchId)
            // Upper case, and only the start of the name.
            first <- services.ratings.findInRankings(
              f.game.gameId,
              f.first.nickname.toUpperCase.dropRight(4),
              f.host.externalId
            )
            // The host has played nothing here, so has no rating to be found by.
            host <- services.ratings.findInRankings(f.game.gameId, f.host.nickname, f.first.externalId)
            blank <- refusal(services.ratings.findInRankings(f.game.gameId, "  ", f.first.externalId))
            noGame <- refusal(services.ratings.findInRankings(GameId(-1), f.first.nickname, f.first.externalId))
        } yield (f, first, host, blank, noGame)
        val (f, first, host, blank, noGame) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          first.ratings.map(r => (r.player.playerId, r.rank, r.rating)),
          List((f.first.playerId, Some(1), 1516))
        )
        assert(!first.more)
        assertEquals(host, Leaderboard(Nil, more = false))
        assert(blank.isInstanceOf[ValidationError], blank)
        assert(noGame.isInstanceOf[NotFoundError], noGame)
    }

    test("a rated match's result goes on both players' records, and a friendly one on neither") {
        val result = for {
            f <- fixture()
            rated <- started(f, friendly = false)
            _ <- finish(f, rated, f.first)
            friendly <- started(f, friendly = true)
            _ <- finish(f, friendly, f.first)
            stored <- TestSession.resource.use { session =>
                val repo = new EloRatingRepo(session)
                (repo.read(f.game.gameId, f.first.playerId), repo.read(f.game.gameId, f.second.playerId)).tupled
            }
        } yield stored
        val (first, second) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(first.map(_.record), Some(MatchRecord(wins = 1)))
        assertEquals(second.map(_.record), Some(MatchRecord(losses = 1)))
    }

    test("a match made friendly after it finished takes its result off both players' records") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.second)
            _ <- services.matches.setFriendly(f.game.gameId, matchId, friendly = true, f.host.externalId)
            stored <- TestSession.resource.use { session =>
                val repo = new EloRatingRepo(session)
                (repo.read(f.game.gameId, f.first.playerId), repo.read(f.game.gameId, f.second.playerId)).tupled
            }
        } yield stored
        val (first, second) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(first.map(_.record), Some(MatchRecord()))
        assertEquals(second.map(_.record), Some(MatchRecord()))
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
          Map(f.first.playerId -> (1500, Some(16)), f.second.playerId -> (1500, Some(-16)))
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
            records <- TestSession.resource.use { session =>
                val repo = new EloRatingRepo(session)
                (repo.read(f.game.gameId, f.first.playerId), repo.read(f.game.gameId, f.second.playerId)).tupled
            }
        } yield (f, refreshed, now, recorded, records)
        val (f, refreshed, now, recorded, records) = result.timeout(caseTimeout).unsafeRunSync()
        // A loss and a win, each one by forfeit.
        assertEquals(
          (records._1.map(_.record), records._2.map(_.record)),
          (Some(MatchRecord(losses = 1, forfeitLosses = 1)), Some(MatchRecord(wins = 1, forfeitWins = 1)))
        )
        assert(refreshed.completed, refreshed)
        assertEquals(now, Map(f.first.playerId -> (1484, 1), f.second.playerId -> (1516, 1)))
        assertEquals(
          recorded.view.mapValues(_._2).toMap,
          Map(f.first.playerId -> Some(-16), f.second.playerId -> Some(16))
        )
    }

    test("results arriving after a refresh completed the match from a status with no ranks still rate it, once") {
        val result = (for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            // The results callback went astray, and a refresh found the game over.
            _ <- IO { engine.status = GameStatusResponse(completed = true, participants = Nil) }
            refreshed <- services.engine.refresh(f.game.gameId, matchId, f.first.externalId)
            before <- ratings(f)
            _ <- finish(f, matchId, f.first)
            _ <- finish(f, matchId, f.first)
            after <- ratings(f)
        } yield (f, refreshed, before, after))
            // The engine is shared with the cases after this one, which start matches of their own.
            .guarantee(IO { engine.status = GameStatusResponse(completed = false, participants = Nil) })
        val (f, refreshed, before, after) = result.timeout(caseTimeout).unsafeRunSync()
        assert(refreshed.completed, refreshed)
        assertEquals(before, Map.empty[PlayerId, (Int, Int)])
        assertEquals(after, Map(f.first.playerId -> (1516, 1), f.second.playerId -> (1484, 1)))
    }

    test("a player cannot take two seats of a challenge that is not friendly") {
        val result = for {
            f <- fixture()
            created <- openChallenge(f, friendly = false)
            _ <- services.challenges
                .accept(f.game.gameId, created.challengeId, None, f.game.roles(0).gameRoleId, f.first.externalId)
            refused <- refusal(
              services.challenges
                  .accept(f.game.gameId, created.challengeId, None, f.game.roles(1).gameRoleId, f.first.externalId)
            )
        } yield refused
        val refused = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.isInstanceOf[ConflictError], refused)
    }

    test("a match a player holds two seats of cannot be made one that is not friendly") {
        val result = for {
            f <- fixture()
            matchId <- playingThemselves(f)
            refused <- refusal(
              services.matches.setFriendly(f.game.gameId, matchId, friendly = false, f.host.externalId)
            )
            stored <- TestSession.resource.use(session => new MatchRepo(session).read(f.game.gameId, matchId))
        } yield (refused, stored)
        val (refused, stored) = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.isInstanceOf[ConflictError], refused)
        assertEquals(stored.map(_.friendly), Some(true))
    }

    test("a match that is not friendly but has a player in two seats completes, and is not rated".tag(Quiet)) {
        val result = for {
            f <- fixture()
            matchId <- playingThemselves(f)
            // Past every check that would have refused it: written straight to the table.
            _ <- TestSession.resource.use { session =>
                val repo = new MatchRepo(session)
                repo.read(f.game.gameId, matchId).flatMap(m => repo.update(m.get.copy(friendly = false)))
            }
            _ <- finish(f, matchId, f.first)
            stored <- TestSession.resource.use(session => new MatchRepo(session).read(f.game.gameId, matchId))
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (stored, now, recorded)
        val (stored, now, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assert(stored.exists(_.completed), stored)
        assertEquals(now, Map.empty[PlayerId, (Int, Int)])
        assertEquals(recorded.values.map(_._2).toSet, Set(Option.empty[Int]))
    }

    test("a completed friendly match made one that is not is rated then, from the ratings its seats began it at") {
        val result = for {
            f <- fixture()
            _ <- services.ratings.set(f.game.gameId, f.first.playerId, 1600, f.host.externalId)
            matchId <- started(f, friendly = true)
            _ <- finish(f, matchId, f.first)
            before <- ratings(f)
            _ <- services.matches.setFriendly(f.game.gameId, matchId, friendly = false, f.host.externalId)
            after <- ratings(f)
            recorded <- eloSeats(f, matchId)
        } yield (f, before, after, recorded)
        val (f, before, after, recorded) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(before, Map(f.first.playerId -> (1600, 0)))
        // Expected 0.64 for the favourite: 32 * 0.36 is 11.5, rounded to 12.
        assertEquals(after, Map(f.first.playerId -> (1612, 1), f.second.playerId -> (1488, 1)))
        assertEquals(recorded, Map(f.first.playerId -> (1600, Some(12)), f.second.playerId -> (1500, Some(-12))))
    }

    test("a completed match that was not friendly made friendly takes back what it did to the ratings") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            _ <- services.matches.setFriendly(f.game.gameId, matchId, friendly = true, f.host.externalId)
            after <- ratings(f)
            stored <- storedRatings(f)
            recorded <- eloSeats(f, matchId)
            // And back again: rated as it was the first time.
            _ <- services.matches.setFriendly(f.game.gameId, matchId, friendly = false, f.host.externalId)
            again <- ratings(f)
        } yield (f, after, stored, recorded, again)
        val (f, after, stored, recorded, again) = result.timeout(caseTimeout).unsafeRunSync()
        // Back where they began, with no rated match behind them -- and so no longer listed, as nobody is
        // who has not been rated.
        assertEquals(after, Map.empty[PlayerId, (Int, Int)])
        assertEquals(stored, Map(f.first.playerId -> (1500, 0), f.second.playerId -> (1500, 0)))
        assertEquals(recorded, Map(f.first.playerId -> (1500, None), f.second.playerId -> (1500, None)))
        assertEquals(again, Map(f.first.playerId -> (1516, 1), f.second.playerId -> (1484, 1)))
    }

    test("a completed match cannot change once a player in it has begun and finished another match since") {
        val result = for {
            f <- fixture()
            first <- started(f, friendly = false)
            _ <- finish(f, first, f.first)
            second <- started(f, friendly = false)
            _ <- finish(f, second, f.second)
            before <- ratings(f)
            refused <- refusal(services.matches.setFriendly(f.game.gameId, first, friendly = true, f.host.externalId))
            after <- ratings(f)
            stored <- TestSession.resource.use(session => new MatchRepo(session).read(f.game.gameId, first))
        } yield (refused, before, after, stored)
        val (refused, before, after, stored) = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.isInstanceOf[ConflictError], refused)
        assertEquals(after, before)
        assertEquals(stored.map(_.friendly), Some(false))
    }

    test("a match still being played that began after the changed one has its starting ratings moved with it") {
        val result = for {
            f <- fixture()
            first <- started(f, friendly = false)
            _ <- finish(f, first, f.first)
            // Begun at 1516 and 1484, which take in the first match.
            second <- started(f, friendly = false)
            began <- eloSeats(f, second)
            _ <- services.matches.setFriendly(f.game.gameId, first, friendly = true, f.host.externalId)
            moved <- eloSeats(f, second)
            _ <- finish(f, second, f.second)
            after <- ratings(f)
        } yield (f, began, moved, after)
        val (f, began, moved, after) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(began, Map(f.first.playerId -> (1516, None), f.second.playerId -> (1484, None)))
        assertEquals(moved, Map(f.first.playerId -> (1500, None), f.second.playerId -> (1500, None)))
        // Rated from 1500 apiece, as if the first match had always been friendly.
        assertEquals(after, Map(f.first.playerId -> (1484, 1), f.second.playerId -> (1516, 1)))
    }

    test("a friendly match records what its players began it rated, and moves nobody's rating nor gives anybody one") {
        val result = for {
            f <- fixture()
            _ <- services.ratings.set(f.game.gameId, f.first.playerId, 1600, f.host.externalId)
            matchId <- started(f, friendly = true)
            _ <- finish(f, matchId, f.first)
            now <- ratings(f)
            recorded <- eloSeats(f, matchId)
            shown <- resultElo(f.first, matchId)
        } yield (f, now, recorded, shown)
        val (f, now, recorded, shown) = result.timeout(caseTimeout).unsafeRunSync()
        // Shown with the results even though the match is friendly: it is who they were when they played.
        assertEquals(shown, Map(f.first.nickname -> (1600, None), f.second.nickname -> (1500, None)))
        assertEquals(now, Map(f.first.playerId -> (1600, 0)))
        assertEquals(recorded, Map(f.first.playerId -> (1600, None), f.second.playerId -> (1500, None)))
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

    test("setting a rating leaves alone how many matches stand behind it, and the leaderboard is highest first") {
        val result = for {
            f <- fixture()
            matchId <- started(f, friendly = false)
            _ <- finish(f, matchId, f.first)
            _ <- services.ratings.set(f.game.gameId, f.second.playerId, 2000, f.overall.externalId)
            // What the listener does once the set has said so: here, a run of it that has finished.
            _ <- services.ending.rank(f.game.gameId)
            listed <- services.ratings.leaderboard(f.game.gameId, 0, f.host.externalId)
        } yield (f, listed)
        val (f, listed) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          listed.ratings.map(r => (r.player.playerId, r.rating, r.matches, r.rank)),
          List((f.second.playerId, 2000, 1, Some(1)), (f.first.playerId, 1516, 1, Some(2)))
        )
        assert(!listed.more)
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

    test("the leaderboard of a game that does not exist is not found") {
        val result = for {
            player <- register()
            refused <- refusal(services.ratings.leaderboard(GameId(-1), 0, player.externalId))
        } yield refused
        val refused = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.isInstanceOf[NotFoundError], refused)
    }
}
