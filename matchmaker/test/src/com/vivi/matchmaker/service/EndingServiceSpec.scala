package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.Files
import java.time.{Duration, Instant}
import munit.FunSuite
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.archive.{ArchiveStore, LocalArchiveStore}
import com.vivi.matchmaker.engine.{CreateGameRequest, CreateGameResponse, GameEngineClient, GameStatusResponse}
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.persistence.{ArchiveRepo, ChallengeRepo, GameRepo, MatchRepo, TestSession}

/** Settling a match's end, as the listener its ending is queued to does: a completed match not yet archived is prompted
  * through its engine's status, once its engine has had time to archive it itself; a cancel the engine has not
  * acknowledged is sent; and what is still owed is said so, to be tried again.
  */
class EndingServiceSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    private val store = LocalArchiveStore(Files.createTempDirectory("ending-spec"), "http://localhost:0")

    /** An engine that answers a status call by archiving the match, as a real one does for a finished match it still
      * holds — through matchmaker's own archive service, so what it does is recorded where settling reads it back.
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
                player <- services.registration.register(unique("ended"), unique("sub"))
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
                  Some(challenge.challengeId),
                  challenge.challenger,
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

    private def endingOf(engine: Engine, now: Instant = Instant.now()) =
        EndingService(TestServices.pool, engine, now = () => now)

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

        assertEquals(run(endingOf(engine).settle(game.gameId, matchId)), Settlement.Settled)
        assertEquals(engine.asked, List(s"https://engine/matches/${matchId.value}/status"))
        assert(archivedAt(game, matchId).isDefined)

        // Settled already: a second delivery of the same ending asks nothing.
        assertEquals(run(endingOf(engine).settle(game.gameId, matchId)), Settlement.Settled)
        assertEquals(engine.asked.size, 1)
    }

    test("one the engine does not archive is still owed, and asked about again each time") {
        val engine = Engine(archiveOnStatus = false)
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, Some(Duration.ofHours(2))))

        assert(run(endingOf(engine).settle(game.gameId, matchId)).isInstanceOf[Settlement.Owed])
        assert(run(endingOf(engine).settle(game.gameId, matchId)).isInstanceOf[Settlement.Owed])
        assertEquals(engine.asked.size, 2)
    }

    test("a match that has only just finished is left to its engine, which is archiving it already") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, Some(Duration.ofMinutes(1))))
        assert(run(endingOf(engine).settle(game.gameId, matchId)).isInstanceOf[Settlement.Owed])
        assertEquals(engine.asked, Nil)
    }

    test("a cancel the engine has not acknowledged is sent, and recorded once it is") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, None, cancelled = true))

        assertEquals(run(endingOf(engine).settle(game.gameId, matchId)), Settlement.Settled)
        assertEquals(engine.cancelled, List(s"https://engine/matches/${matchId.value}/cancel"))
        assert(run(TestSession.resource.use(session => new ArchiveRepo(session).isReleased(game.gameId, matchId))))
        assertEquals(run(endingOf(engine).settle(game.gameId, matchId)), Settlement.Settled)
        assertEquals(engine.cancelled.size, 1)
    }

    test("a cancel the engine does not acknowledge is still owed, and not recorded as heard") {
        val engine = new Engine() {
            override def cancel(url: String, key: Option[String]): IO[Unit] =
                IO.raiseError(new IllegalStateException("engine is down"))
        }
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, None, cancelled = true))

        assert(run(endingOf(engine).settle(game.gameId, matchId)).isInstanceOf[Settlement.Owed])
        assert(!run(TestSession.resource.use(session => new ArchiveRepo(session).isReleased(game.gameId, matchId))))
    }

    test("a match still being played, or one that does not exist, owes nothing") {
        val engine = Engine()
        val services = TestServices.servicesWith(engine, archiveStore = store)
        val (game, matchId) = run(matchOf(services, None))
        assertEquals(run(endingOf(engine).settle(game.gameId, matchId)), Settlement.Settled)
        assertEquals(run(endingOf(engine).settle(game.gameId, MatchId("no-such-match"))), Settlement.Settled)
        assertEquals((engine.asked, engine.cancelled), (Nil, Nil))
    }

    test("an ending settled in this process waits for the connection its caller holds, rather than deadlocking") {
        // One connection, which the results callback holds while it says the match has ended. Settling it there and
        // then would wait for that connection forever.
        val pool = TestServices.poolOf(1)
        val engine = Engine(archiveOnStatus = false)
        val ending = EndingService(pool, engine)
        @volatile var settled = List.empty[Settlement]
        val services = Services.fromPool[String](
          pool,
          engine,
          archiveStore = store,
          matchEndings = Some(
            MatchEndings.inline(
              (gameId, matchId) => ending.settle(gameId, matchId).map(s => settled :+= s),
              ending.rank(_).void
            )
          )
        )
        val (game, matchId) = run(matchOf(services, None))

        run(services.engine.recordResults(game.gameId, matchId, Nil, game.externalId))
        assert(
          run(TestSession.resource.use(session => new MatchRepo(session).read(game.gameId, matchId)))
              .exists(_.completed)
        )
        // And it is settled once the connection is given back: just finished, so owed its archive.
        run(IO.sleep(50.millis).iterateUntil(_ => settled.nonEmpty))
        assert(settled.head.isInstanceOf[Settlement.Owed], settled)
    }
}
