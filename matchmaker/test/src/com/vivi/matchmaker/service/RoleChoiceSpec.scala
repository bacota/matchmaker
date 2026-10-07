package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    EloRatingRepo,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    ResultRepo,
    TestSession,
    TurnRepo
}

/** Seats whose roles are chosen in the engine (V52): created with none, and given the role each player chose when the
  * engine reports it — on a move, in a status answer, or with the results.
  *
  * Nothing in matchmaker creates such a match yet (tournaments will), so the match and its seats are written here
  * directly, as the engine would find them once created.
  */
class RoleChoiceSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    /* A ceiling on a case that has hung; see CLAUDE.md. */
    private val caseTimeout = 60.seconds

    private class StubEngine extends GameEngineClient {
        @volatile var status: GameStatusResponse = GameStatusResponse(completed = false, participants = Nil)

        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.raiseError(GameEngineError("not used"))

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(status)
    }

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private case class Fixture(
        engine: StubEngine,
        services: Services[String],
        game: Game,
        first: Player,
        second: Player,
        matchId: MatchId,
        firstSeat: ParticipantId,
        secondSeat: ParticipantId
    ) {
        def attacker: GameRole = game.roles.find(_.name == "attacker").get
        def defender: GameRole = game.roles.find(_.name == "defender").get
    }

    /** Two players in a match whose roles are still to be chosen, the second of them choosing now — since
      * `chooserSince` — under a chess clock of `limit`.
      */
    private def fixture(
        friendly: Boolean = true,
        limit: Duration = Duration.ofHours(1),
        chooserSince: Instant = Instant.now()
    ): IO[Fixture] = {
        val engine = new StubEngine
        val services = TestServices.servicesWith(engine)
        for {
            first <- services.registration.register(unique("chooser"), unique("chooser-sub"))
            second <- services.registration.register(unique("chooser"), unique("chooser-sub"))
            made <- TestSession.resource.use { session =>
                for {
                    game <- new GameRepo[String](session).create(
                      Game(
                        GameId.unassigned,
                        GameType.Plain,
                        "Duel",
                        "Duel",
                        "description",
                        "https://engine.example.com/games",
                        active = true,
                        Seq(
                          GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false, "Attacker"),
                          GameRole(GameRoleId(0), GameId.unassigned, "defender", optional = false, "Defender")
                        ),
                        Seq.empty,
                        unique("duel"),
                        choosesRoles = true
                      )
                    )
                    matchId = MatchId(unique("m"))
                    _ <- new MatchRepo(session).create(
                      Match(
                        game.gameId,
                        matchId,
                        None,
                        first.playerId,
                        "round 1",
                        None,
                        Instant.now().minusSeconds(600),
                        Some(limit),
                        "{}",
                        statusUrl = Some("https://engine/status/1"),
                        timeLimitKind = TimeLimitKind.Total,
                        friendly = friendly
                      )
                    )
                    repo = new ParticipantRepo(session)
                    firstSeat <- repo.create(
                      PlainParticipant(
                        ParticipantId(0),
                        game.gameId,
                        matchId,
                        first.playerId,
                        false,
                        false,
                        None,
                        None
                      ),
                      EloRating.initial
                    )
                    secondSeat <- repo.create(
                      PlainParticipant(
                        ParticipantId(0),
                        game.gameId,
                        matchId,
                        second.playerId,
                        pending = true,
                        completed = false,
                        due = Some(chooserSince.plus(limit)),
                        gameRoleId = None
                      ),
                      EloRating.initial
                    )
                } yield (game, matchId, firstSeat.participantId, secondSeat.participantId)
            }
            (game, matchId, firstSeat, secondSeat) = made
        } yield Fixture(engine, services, game, first, second, matchId, firstSeat, secondSeat)
    }

    private def seats(f: Fixture): IO[Map[ParticipantId, (Option[GameRoleId], Option[Int])]] =
        TestSession.resource.use(session =>
            new ParticipantRepo(session)
                .eloSeatsForMatch(f.game.gameId, f.matchId)
                .map(_.map(s => s.participantId -> (s.gameRoleId, s.eloRoleStart)).toMap)
        )

    /** The second player choosing `defender`, which leaves `attacker` to the first: the engine's move callback for it.
      */
    private def chosen(f: Fixture, startedAt: Instant, takenAt: Instant, sequence: Long = 1L): IO[Unit] =
        f.services.engine.recordMove(
          f.game.gameId,
          f.matchId,
          f.secondSeat,
          List(f.firstSeat),
          takenAt,
          startedAt,
          f.game.externalId,
          Some(
            MoveState(
              sequence,
              List(SeatClock(f.firstSeat, takenAt)),
              List(ReportedRole(f.secondSeat, "defender"), ReportedRole(f.firstSeat, "attacker"))
            )
          )
        )

    test("the roles a move reports are written onto the seats that had none, with each player's rating in them") {
        val result = for {
            f <- fixture()
            before <- seats(f)
            at = Instant.now()
            _ <- chosen(f, at.minusSeconds(30), at)
            after <- seats(f)
            used <- TestSession.resource.use(session => new TurnRepo(session).timeUsed(f.game.gameId, f.matchId))
        } yield (f, before, after, used)
        val (f, before, after, used) = result.timeout(caseTimeout).unsafeRunSync()
        assert(before.values.forall(_ == (None, None)), before)
        assertEquals(
          after,
          Map(
            f.firstSeat -> (Some(f.attacker.gameRoleId), Some(EloRating.initial)),
            f.secondSeat -> (Some(f.defender.gameRoleId), Some(EloRating.initial))
          )
        )
        // The choice was a turn, and its time is the chooser's.
        assertEquals(used.get(f.secondSeat), Some(Duration.ofSeconds(30)))
    }

    test("a seat's role, once written, is not changed by a later report naming another") {
        val result = for {
            f <- fixture()
            at = Instant.now()
            _ <- chosen(f, at.minusSeconds(5), at)
            _ <- f.services.engine.recordMove(
              f.game.gameId,
              f.matchId,
              f.firstSeat,
              List(f.secondSeat),
              at.plusSeconds(1),
              at,
              f.game.externalId,
              Some(
                MoveState(
                  2L,
                  List(SeatClock(f.secondSeat, at.plusSeconds(1))),
                  List(ReportedRole(f.secondSeat, "attacker"), ReportedRole(f.firstSeat, "defender"))
                )
              )
            )
            after <- seats(f)
        } yield (f, after)
        val (f, after) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(after(f.firstSeat)._1, Some(f.attacker.gameRoleId))
        assertEquals(after(f.secondSeat)._1, Some(f.defender.gameRoleId))
    }

    test("a status answer's roles are written too, when a refresh asks") {
        val result = for {
            f <- fixture()
            _ = f.engine.status = GameStatusResponse(
              completed = false,
              participants = List(
                EngineParticipantStatus(f.firstSeat.value, true, false, Some(Instant.now()), Some("attacker")),
                EngineParticipantStatus(f.secondSeat.value, false, false, Some(Instant.now()), Some("defender"))
              )
            )
            _ <- f.services.engine.refresh(f.game.gameId, f.matchId, f.first.externalId)
            after <- seats(f)
        } yield (f, after)
        val (f, after) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(
          after.view.mapValues(_._1).toMap,
          Map(
            f.firstSeat -> Some(f.attacker.gameRoleId),
            f.secondSeat -> Some(f.defender.gameRoleId)
          )
        )
    }

    test("a rated match ending with a seat that never chose moves the overall ratings, and none in any role") {
        val result = for {
            f <- fixture(friendly = false)
            _ <- f.services.engine.recordResults(
              f.game.gameId,
              f.matchId,
              List(
                ReportedResult(f.firstSeat, 1, Map.empty, isWinner = true, forfeit = true, role = Some("attacker")),
                ReportedResult(f.secondSeat, 2, Map.empty, isWinner = false, forfeit = true)
              ),
              f.game.externalId
            )
            after <- seats(f)
            results <- TestSession.resource.use(session =>
                List(f.firstSeat, f.secondSeat).traverse(new ResultRepo(session).read(f.game.gameId, _))
            )
            ratings <- TestSession.resource.use(session =>
                List(f.first, f.second).traverse(p =>
                    new EloRatingRepo(session).read(f.game.gameId, p.playerId).map(_.map(_.rating))
                )
            )
            listed <- f.services.matches.results(f.first.externalId)
        } yield (f, after, results.flatten, ratings, listed)
        val (f, after, results, ratings, listed) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(after(f.firstSeat)._1, Some(f.attacker.gameRoleId))
        assertEquals(after(f.secondSeat)._1, None)
        assertEquals(
          results.map(r => (r.rank, r.eloDelta.isDefined, r.eloRoleDelta)),
          List((1, true, None), (2, true, None))
        )
        assertEquals(ratings, List(Some(1516), Some(1484)))
        // And the seat with no role is listed among the results all the same, with none.
        assertEquals(
          listed.filter(_.matchId == f.matchId).map(r => r.participantId -> r.roleName).toMap,
          Map(f.firstSeat -> Some("Attacker"), f.secondSeat -> None)
        )
    }

    test("a chooser who has run out of time is forfeited when the match is next read, as any player is".tag(Quiet)) {
        val since = Instant.now().minusSeconds(120)
        val result = for {
            f <- fixture(limit = Duration.ofSeconds(60), chooserSince = since)
            // The engine agrees: the second seat is still choosing, since two minutes ago.
            _ = f.engine.status = GameStatusResponse(
              completed = false,
              participants = List(
                EngineParticipantStatus(f.firstSeat.value, false, false, Some(since)),
                EngineParticipantStatus(f.secondSeat.value, true, false, Some(since))
              )
            )
            read <- f.services.engine.read(f.game.gameId, f.matchId, f.first.externalId)
            results <- TestSession.resource.use(session =>
                List(f.firstSeat, f.secondSeat).traverse(new ResultRepo(session).read(f.game.gameId, _))
            )
        } yield (read, results.flatten)
        val (read, results) = result.timeout(caseTimeout).unsafeRunSync()
        assert(read.completed)
        assertEquals(results.map(r => (r.rank, r.forfeit)), List((1, true), (2, true)))
    }
}
