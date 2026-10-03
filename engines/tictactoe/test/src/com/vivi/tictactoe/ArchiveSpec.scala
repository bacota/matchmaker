package com.vivi.tictactoe

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{
    ArchivingMatchStore,
    EngineRequest,
    InMemoryMatchStore,
    PlayAuth,
    QuietTests,
    RecordingMatchmaker
}
import Protocol.given

/** A finished match archived through matchmaker, and read back once its live copy is gone — played through a real game,
  * since what is archived is that game's stored JSON.
  *
  * Matchmaker is the recording one, which keeps track of archives as matchmaker would and holds their bytes in a map
  * standing in for S3.
  */
class ArchiveSpec extends FunSuite with QuietTests {

    private val matchmakerUrl = "http://matchmaker.test"

    private def fixture(isPublic: Boolean = true) = {
        val live = InMemoryMatchStore[TicTacToeMatch]()
        val recorder = RecordingMatchmaker()
        val store = ArchivingMatchStore(live, recorder, matchmakerUrl, recorder.archiveStore)
        val engine = Engine(store, recorder, "http://engine.test")
        val routes = Routes(engine, PlayAuth.Trusted, None)
        val create = Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "tic-tac-toe",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("X"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("O"), None, None)
          ),
          moveCallbackUrl = Some(s"$matchmakerUrl/games/1/matches/m-1/moves"),
          resultsCallbackUrl = Some(s"$matchmakerUrl/games/1/matches/m-1/results")
        )
        engine.createGame(create)
        (engine, routes, live, recorder)
    }

    /** X takes the top row: the match ends on the fifth move. */
    private def playToTheEnd(engine: Engine): Unit =
        List(("sub-alice", 0), ("sub-bob", 3), ("sub-alice", 1), ("sub-bob", 4), ("sub-alice", 2)).foreach {
            (player, cell) => assert(engine.move("m-1", player, cell).isRight, s"cell $cell")
        }

    test("a finished match is archived after its results, and its live copy deleted only once that is confirmed") {
        val (engine, _, live, recorder) = fixture()
        val before = live.get("m-1")
        playToTheEnd(engine)

        assertEquals(recorder.results.size, 1)
        assert(recorder.isArchived("m-1"))
        assertEquals(live.get("m-1"), None)
        // What was archived is the stored JSON, which reads back as the match did.
        val bytes = recorder.archiveStore.bytesAt(recorder.archiveStore.urlOf("m-1")).get
        val archived = read[TicTacToeMatch](String(bytes, "UTF-8"))
        assert(archived.completed)
        assertEquals(archived.matchId, before.get.matchId)
    }

    test("an archived match is still read, with or without the hint, and its moves still refused") {
        val (engine, _, _, _) = fixture()
        playToTheEnd(engine)

        assertEquals(engine.read("m-1").map(_.winner), Right(Some(Mark.X)))
        assertEquals(engine.read("m-1", archived = true).map(_.winner), Right(Some(Mark.X)))
        assert(engine.move("m-1", "sub-bob", 8).isLeft)
    }

    test("the play page, the board and the state are all served from the archive") {
        val (engine, routes, _, _) = fixture()
        playToTheEnd(engine)

        assertEquals(routes(EngineRequest("GET", "/matches/m-1/play", Map("archived" -> "1"))).status, 200)
        assertEquals(routes(EngineRequest("GET", "/matches/m-1/board", Map("archived" -> "1"))).status, 200)
        val state = routes(EngineRequest("GET", "/matches/m-1/state", Map("as" -> "sub-bob")))
        assertEquals(state.status, 200)
        assertEquals(read[Protocol.StateResponse](state.body).winner, Some("X"))
    }

    test("an expired friendly archive is a 410: a page for a link, an error for the state") {
        val (engine, routes, _, recorder) = fixture()
        playToTheEnd(engine)
        recorder.expiredArchives = Set("m-1")

        val page = routes(EngineRequest("GET", "/matches/m-1/play", Map("archived" -> "1")))
        assertEquals(page.status, 410)
        assert(page.contentType.startsWith("text/html"), page.contentType)
        assert(page.body.contains("kept for 30 days"), page.body)

        assertEquals(routes(EngineRequest("GET", "/matches/m-1/state", Map("as" -> "sub-bob"))).status, 410)
    }

    test("an archive that has gone from the store is reported to matchmaker, which decides it has expired") {
        val (engine, routes, _, recorder) = fixture()
        playToTheEnd(engine)
        recorder.archiveStore.expire("m-1")

        assertEquals(routes(EngineRequest("GET", "/matches/m-1/board")).status, 410)
        assertEquals(recorder.reportedExpired, List("m-1"))
    }

    test("archiving that fails leaves the live copy, and matchmaker's next status call archives it") {
        val (engine, routes, live, recorder) = fixture()
        recorder.refuseArchiving = true
        playToTheEnd(engine)
        assert(live.get("m-1").isDefined)
        assert(!recorder.isArchived("m-1"))

        recorder.refuseArchiving = false
        val status = routes(EngineRequest("GET", "/matches/m-1/status"))
        assertEquals(read[Protocol.GameStatusResponse](status.body).completed, true)
        assert(recorder.isArchived("m-1"))
        assertEquals(live.get("m-1"), None)
    }

    test("a matchmaker that keeps no archives leaves every match where it is") {
        val (engine, _, live, recorder) = fixture()
        recorder.archivingUnavailable = true
        playToTheEnd(engine)
        assert(live.get("m-1").isDefined)
        assertEquals(engine.read("m-1").map(_.completed), Right(true))
    }

    test("a match still being played is not archived by a status call") {
        val (engine, routes, live, recorder) = fixture()
        assert(engine.move("m-1", "sub-alice", 0).isRight)
        routes(EngineRequest("GET", "/matches/m-1/status"))
        assert(!recorder.isArchived("m-1"))
        assert(live.get("m-1").isDefined)
    }

    test("a lost confirm is recovered: matchmaker answering that it has the archive lets the live copy go") {
        val (engine, routes, live, recorder) = fixture()
        playToTheEnd(engine)
        // Put the live copy back, as if the delete after the confirm had never happened.
        val archived = engine.read("m-1").toOption.get
        live.create(archived)

        routes(EngineRequest("GET", "/matches/m-1/status"))
        assertEquals(live.get("m-1"), None)
    }

    test("a match matchmaker has no archive of, and no live copy, is not found") {
        val (engine, _, _, _) = fixture()
        assertEquals(engine.read("nope").left.map(_.status), Left(404))
        assertEquals(engine.read("nope", archived = true).left.map(_.status), Left(404))
    }
}
