package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.Instant
import munit.FunSuite
import skunk.implicits._
import skunk.codec.all.{int4, text, timestamptz}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.engine.{
    CreateGameRequest,
    CreateGameResponse,
    GameEngineClient,
    GameEngineError,
    GameStatusResponse
}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{ArchiveRepo, ChallengeRepo, GameRepo, MatchRepo, ParticipantRepo, TestSession}

/** A cancelled match's engine is told, so that it can drop the match — and a cancel stands whether or not it hears. */
class CancelReleaseSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    private class RecordingEngine(fail: Boolean = false) extends GameEngineClient {
        @volatile var cancelled: List[(String, Option[String])] = Nil

        def createGame(url: String, key: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.raiseError(new IllegalStateException("not in these tests"))
        def status(url: String, key: Option[String], since: Option[Instant]): IO[GameStatusResponse] =
            IO.raiseError(new IllegalStateException("not in these tests"))
        override def cancel(cancelUrl: String, apiKey: Option[String]): IO[Unit] =
            if (fail) IO.raiseError(GameEngineError("engine is down"))
            else IO { cancelled = cancelled :+ (cancelUrl -> apiKey) }
    }

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    /** A plain game's running match, created by `player`, whose engine gave `cancelUrl`. */
    private def runningMatch(services: Services[String], cancelUrl: Option[String]): IO[(Player, Game, MatchId)] =
        TestSession.resource.use { session =>
            for {
                player <- services.registration.register(unique("canceller"), unique("sub"))
                game <- new GameRepo[String](session).create(
                  Game(
                    GameId.unassigned,
                    GameType.Plain,
                    "tictactoe",
                    "Tic-tac-toe",
                    "description",
                    "url",
                    active = true,
                    Seq(GameRole(GameRoleId(0), GameId.unassigned, "X", optional = false, displayName = "X")),
                    Seq.empty,
                    unique("engine")
                  )
                )
                challenge <- new ChallengeRepo(session).create(
                  PlainChallenge(
                    ChallengeId(0),
                    player.playerId,
                    "challenge",
                    None,
                    None,
                    "{}",
                    game.gameId,
                    isPublic = false,
                    Some(game.roles.head.gameRoleId)
                  )
                )
                matchId = MatchId(java.util.UUID.randomUUID().toString)
                m = Match(
                  game.gameId,
                  matchId,
                  challenge.challengeId,
                  "d",
                  None,
                  Instant.ofEpochSecond(1000),
                  None,
                  "{}"
                )
                _ <- new MatchRepo(session).create(m)
                _ <- new MatchRepo(session).setUrls(m.copy(statusUrl = Some("https://engine/status")), cancelUrl)
                _ <- new ParticipantRepo(session).create(
                  PlainParticipant(
                    ParticipantId(0),
                    game.gameId,
                    matchId,
                    player.playerId,
                    pending = true,
                    completed = false,
                    None,
                    game.roles.head.gameRoleId
                  ),
                  EloRating.initial
                )
            } yield (player, game, matchId)
        }

    private def released(gameId: GameId, matchId: MatchId): IO[Option[Instant]] =
        TestSession.resource.use { session =>
            session
                .unique(
                  sql"SELECT engine_released FROM match WHERE game_id = $int4 AND match_id = $text"
                      .query(timestamptz.opt)
                )((gameId.value, matchId.value))
                .map(_.map(_.toInstant))
        }

    private def run[A](io: IO[A]): A = io.timeout(30.seconds).unsafeRunSync()

    test("the engine is told at its cancel url, with the game's key, and that it heard is recorded") {
        val engine = RecordingEngine()
        val services = TestServices.servicesWith(engine)
        val (player, game, matchId) = run(runningMatch(services, Some("https://engine/matches/m/cancel")))

        val cancelled = run(services.matches.cancel(game.gameId, matchId, player.externalId))
        assert(cancelled.cancelled)
        assertEquals(engine.cancelled.map(_._1), List("https://engine/matches/m/cancel"))
        assert(run(released(game.gameId, matchId)).isDefined)
        // Listed for the sweep no longer.
        val owed =
            run(TestSession.resource.use(session => new ArchiveRepo(session).listUnreleased(Instant.now())))
        assert(!owed.exists(_.matchId == matchId))
    }

    test("an engine that does not answer leaves the cancel standing, and the match owed a retry".tag(Quiet)) {
        val services = TestServices.servicesWith(RecordingEngine(fail = true))
        val (player, game, matchId) = run(runningMatch(services, Some("https://engine/matches/m/cancel")))

        val cancelled = run(services.matches.cancel(game.gameId, matchId, player.externalId))
        assert(cancelled.cancelled)
        assertEquals(run(released(game.gameId, matchId)), None)
        val owed =
            run(TestSession.resource.use(session => new ArchiveRepo(session).listUnreleased(Instant.now())))
        assert(owed.exists(row => row.matchId == matchId && row.cancelUrl == "https://engine/matches/m/cancel"))
    }

    test("an engine that gave no cancel url is not told, and is owed nothing") {
        val engine = RecordingEngine()
        val services = TestServices.servicesWith(engine)
        val (player, game, matchId) = run(runningMatch(services, None))

        run(services.matches.cancel(game.gameId, matchId, player.externalId))
        assertEquals(engine.cancelled, Nil)
        val owed =
            run(TestSession.resource.use(session => new ArchiveRepo(session).listUnreleased(Instant.now())))
        assert(!owed.exists(_.matchId == matchId))
    }
}
