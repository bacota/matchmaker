package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.Files
import java.time.{Duration, Instant}
import munit.FunSuite
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.archive.{ArchiveStore, LocalArchiveStore}
import com.vivi.matchmaker.engine.{CreateGameRequest, CreateGameResponse, GameEngineClient, GameStatusResponse}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{ArchiveRepo, ChallengeRepo, GameRepo, MatchRepo, TestSession}

/** The sweep: completed matches never archived are prompted through their engine's status, cancels the engine never
  * acknowledged are sent again, and neither is asked about more than once a day.
  */
class SweepServiceSpec extends FunSuite {
    TestMigration.ensure()

    private val store = LocalArchiveStore(Files.createTempDirectory("sweep-spec"), "http://localhost:0")

    /** An engine that answers a status call by archiving the match, as a real one does for a finished match it still
      * holds — through matchmaker's own archive service, so what it does is recorded where the sweep reads it back.
      */
    private class Engine(archiveOnStatus: Boolean = true) extends GameEngineClient {
        @volatile var asked: List[String] = Nil
        @volatile var cancelled: List[String] = Nil
        @volatile var services: Option[Services[String]] = None
        @volatile var archiving: Option[(MatchId, String)] = None

        def createGame(url: String, key: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.raiseError(new IllegalStateException("not in these tests"))

        def status(url: String, key: Option[String], since: Option[Instant]): IO[GameStatusResponse] =
            IO { asked = asked :+ url } *> {
                (archiving, services) match {
                    case (Some((matchId, externalId)), Some(s)) if archiveOnStatus =>
                        val body = """{"matchId":"m"}""".getBytes
                        s.archives
                            .requestUpload(matchId, body.length.toLong, ArchiveStore.sha256(body), None, externalId)
                            .flatMap {
                                case UploadAnswer.Upload(signed) =>
                                    IO(store.put(signed.url.split("/local-archive/")(1), body, signed.headers))
                                case _ => IO.unit
                            } *> s.archives.confirm(matchId, externalId).void
                    case _ => IO.unit
                }
            }.as(GameStatusResponse(completed = true, participants = Nil))

        override def cancel(url: String, key: Option[String]): IO[Unit] = IO { cancelled = cancelled :+ url }
    }

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    /** A game, and one match of it: completed `finishedAgo`, or cancelled with a cancel url. */
    private def matchOf(
        services: Services[String],
        finishedAgo: Option[Duration],
        cancelled: Boolean = false
    ): IO[(Game, MatchId)] =
        TestSession.resource.use { session =>
            for {
                player <- services.registration.register(unique("swept"), unique("sub"))
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
                    "c",
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
                  finishedAgo.map(ago => Instant.now().minus(ago)),
                  Instant.ofEpochSecond(1000),
                  None,
                  "{}",
                  cancelled = cancelled
                )
                _ <- new MatchRepo(session).create(m)
                _ <- new MatchRepo(session).setUrls(
                  m.copy(statusUrl = Some(s"https://engine/matches/${matchId.value}/status")),
                  Option.when(cancelled)(s"https://engine/matches/${matchId.value}/cancel")
                )
            } yield (game, matchId)
        }

    private def sweepOf(services: Services[String], engine: Engine, game: Game, now: Instant = Instant.now()) =
        SweepService(TestServices.pool, engine, services.matches, now = () => now, game = Some(game.gameId))

    private def run[A](io: IO[A]): A = io.timeout(30.seconds).unsafeRunSync()

    private def archivedAt(game: Game, matchId: MatchId): Option[Instant] =
        run(TestSession.resource.use(session => new ArchiveRepo(session).read(game.gameId, matchId)))
            .flatMap(_.archivedAt)

    test("a completed match never archived is prompted through its engine's status, which archives it") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        engine.services = Some(services)
        val (game, matchId) = run(matchOf(services, Some(Duration.ofHours(2))))
        engine.archiving = Some(matchId -> game.externalId)

        val report = run(sweepOf(services, engine, game).run())
        assertEquals(engine.asked, List(s"https://engine/matches/${matchId.value}/status"))
        assertEquals((report.prompted, report.stillUnarchived), (1, Nil))
        assert(archivedAt(game, matchId).isDefined)
    }

    test("one the engine does not archive is reported, and not asked about again until a day has passed") {
        val engine = Engine(archiveOnStatus = false)
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, Some(Duration.ofHours(2))))

        val first = run(sweepOf(services, engine, game).run())
        assertEquals(first.stillUnarchived, List(matchId))
        val again = run(sweepOf(services, engine, game).run())
        assertEquals(again.prompted, 0)
        val tomorrow = run(sweepOf(services, engine, game, Instant.now().plus(Duration.ofHours(25))).run())
        assertEquals(tomorrow.prompted, 1)
        assertEquals(engine.asked.size, 2)
    }

    test("a match finished within the hour is left to the engine, which is archiving it already") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, _) = run(matchOf(services, Some(Duration.ofMinutes(5))))
        assertEquals(run(sweepOf(services, engine, game).run()).prompted, 0)
        assertEquals(engine.asked, Nil)
    }

    test("a cancel the engine never acknowledged is sent again, and recorded once it is") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, None, cancelled = true))

        val report = run(sweepOf(services, engine, game).run())
        assertEquals(engine.cancelled, List(s"https://engine/matches/${matchId.value}/cancel"))
        assertEquals((report.released, report.stillUnreleased), (1, Nil))
        assert(run(TestSession.resource.use(session => new ArchiveRepo(session).isReleased(game.gameId, matchId))))
        assertEquals(run(sweepOf(services, engine, game).run()).released, 0)
    }
}
