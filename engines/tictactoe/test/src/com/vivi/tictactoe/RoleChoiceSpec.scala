package com.vivi.tictactoe

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{
    ChooseRequest,
    ChoosingView,
    EngineRequest,
    InMemoryMatchStore,
    PlayAuth,
    QuietTests,
    RecordingMatchmaker,
    RoleChoosing
}
import com.vivi.engine.ChoosingView.given
import Protocol.given

/** A match whose players choose their roles before the game begins ([[com.vivi.engine.RoleChoosing]]), played through
  * tic-tac-toe as matchmaker and the players reach it: as requests.
  */
class RoleChoiceSpec extends FunSuite with QuietTests {

    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val moveUrl = "http://matchmaker.test/games/1/matches/m-1/moves"
    private val resultsUrl = "http://matchmaker.test/games/1/matches/m-1/results"

    private def request(
        order: List[Long] = List(2L, 1L),
        live: Option[Protocol.LiveTerms] = None,
        isPublic: Boolean = false
    ) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "tic-tac-toe",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = live.map(_.timeLimitSeconds),
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, None, None, None, Some("alice")),
            Protocol.EnginePlayer("sub-bob", 2L, None, None, None, Some("bob"))
          ),
          moveCallbackUrl = Some(moveUrl),
          resultsCallbackUrl = Some(resultsUrl),
          live = live,
          roleChoice = Some(Protocol.RoleChoice(order, List("X", "O"), Map("X" -> "Crosses", "O" -> "Noughts")))
        )

    private class Fixture(create: Protocol.CreateGameRequest = request()) {
        val clock = AtomicReference(start)
        val store = InMemoryMatchStore[TicTacToeMatch]()
        val roles = InMemoryMatchStore[RoleChoosing]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test", () => clock.get, roles = roles)
        val routes = Routes(engine, PlayAuth.Trusted, None)
        val created = routes(EngineRequest("POST", "/games", Map.empty, write(create)))

        def advance(seconds: Long): Unit = clock.updateAndGet(_.plusSeconds(seconds))
        def get(path: String, as: Option[String] = None) =
            routes(EngineRequest("GET", path, as.map(a => Map("as" -> a)).getOrElse(Map.empty)))
        def post(path: String, as: String, body: String) =
            routes(EngineRequest("POST", path, Map("as" -> as), body))
        def choose(as: String, role: String) =
            post("/matches/m-1/role", as, write(ChooseRequest(role = Some(role))))
        def status = read[Protocol.GameStatusResponse](get("/matches/m-1/status").body)
        def view(as: String) = read[ChoosingView](get("/matches/m-1/state", Some(as)).body)
    }

    test("a match created with roles to choose has no game yet: the first chooser is pending, and nothing else") {
        val f = Fixture()
        assertEquals(f.created.status, 201)
        assert(f.store.get("m-1").isEmpty)
        val status = f.status
        assertEquals(status.participants.filter(_.pending).map(_.participantId), List(2L))
        assertEquals(status.sequence, Some(0L))
        assert(status.participants.forall(_.role.isEmpty))
        assert(status.turns.isEmpty)
    }

    test("the play page is the choosing page, and the chooser is offered the free roles by name") {
        val f = Fixture()
        val page = f.get("/matches/m-1/play", Some("sub-bob")).body
        assert(page.contains("Who plays which role"), page.take(200))
        val bob = f.view("sub-bob")
        assertEquals(bob.chooser, Some(2L))
        assertEquals(bob.free.map(_.displayName), List("Crosses", "Noughts"))
        assertEquals(bob.seats.map(_.name), List("alice", "bob"))
        // alice is shown the same choice being made, and may not make it.
        assertEquals(f.view("sub-alice").chooser, Some(2L))
        assertEquals(f.choose("sub-alice", "X").status, 400)
    }

    test("moves are refused while roles are being chosen, and a role that is not free is refused") {
        val f = Fixture()
        assertEquals(f.post("/matches/m-1/moves", "sub-bob", write(Protocol.MoveRequest(4))).status, 409)
        assertEquals(f.choose("sub-bob", "Z").status, 400)
        assertEquals(f.post("/matches/m-1/role", "sub-carol", write(ChooseRequest(Some("X")))).status, 403)
    }

    test("a choice is a turn: the last chooser is given the role left, and the game begins from the last choice") {
        val f = Fixture()
        f.advance(30)
        val answer = f.choose("sub-bob", "O")
        assertEquals(answer.status, 200)
        assert(read[ChoosingView](answer.body).done)

        val m = f.store.get("m-1").get
        assertEquals(m.seats.map(s => s.participantId -> s.mark.toString).toMap, Map(1L -> "X", 2L -> "O"))
        // The first move's clock starts at the choice.
        assertEquals(TicTacToeMatch.clockStartedAt(m), start.plusSeconds(30))

        // Reported as a move: bob's choice took 30 seconds, and alice (X) is to move now.
        val (url, choice) = f.recorder.moves.last
        assertEquals(url, moveUrl)
        assertEquals(choice.participantId, 2L)
        assertEquals((choice.startedAt, choice.takenAt), (start, start.plusSeconds(30)))
        assertEquals(choice.state.map(_.sequence), Some(1L))
        assertEquals(choice.state.toList.flatMap(_.pending.map(_.participantId)), List(1L))
        assertEquals(
          choice.state.toList.flatMap(_.roles).map(r => r.participantId -> r.role).toMap,
          Map(1L -> "X", 2L -> "O")
        )
    }

    test("once the game begins, its sequence, turns and roles count the choice before it") {
        val f = Fixture()
        f.advance(10)
        f.choose("sub-bob", "O")
        assertEquals(f.status.sequence, Some(1L))
        assertEquals(f.status.turns.map(_.participantId), List(2L))
        assertEquals(
          f.status.participants.map(p => p.participantId -> p.role).toMap,
          Map(1L -> Some("X"), 2L -> Some("O"))
        )

        f.advance(5)
        assertEquals(f.post("/matches/m-1/moves", "sub-alice", write(Protocol.MoveRequest(4))).status, 200)
        assertEquals(f.recorder.moves.last._2.state.map(_.sequence), Some(2L))
        assertEquals(f.status.turns.map(_.participantId), List(2L, 1L))
        // And the play page is the game's own now, and a choice is refused.
        assert(!f.get("/matches/m-1/play", Some("sub-alice")).body.contains("Who plays which role"))
        assertEquals(f.choose("sub-bob", "X").status, 409)
    }

    test("the results carry the choice among the turns, and every seat's role") {
        val f = Fixture()
        f.choose("sub-bob", "O")
        // alice (X) takes the top row while bob plays the middle.
        List(("sub-alice", 0), ("sub-bob", 3), ("sub-alice", 1), ("sub-bob", 4), ("sub-alice", 2)).foreach(
          (who, cell) => assertEquals(f.post("/matches/m-1/moves", who, write(Protocol.MoveRequest(cell))).status, 200)
        )
        val results = f.recorder.results.last._2
        assertEquals(results.results.map(r => r.participantId -> r.role).toMap, Map(1L -> Some("X"), 2L -> Some("O")))
        assertEquals(results.results.find(_.isWinner).map(_.participantId), Some(1L))
        assertEquals(results.turns.toList.flatten.map(_.participantId), List(2L, 1L, 2L, 1L, 2L, 1L))
        // Archived, the choosing record goes too.
        assert(f.roles.get("m-1").isEmpty)
    }

    test("a match with a game played without choosing reports no roles, as before") {
        val f = Fixture(
          request().copy(
            roleChoice = None,
            players = request().players.zip(List("X", "O")).map((p, r) => p.copy(role = Some(r)))
          )
        )
        assert(f.store.get("m-1").isDefined)
        assert(f.status.participants.forall(_.role.isEmpty))
        assertEquals(f.status.sequence, Some(0L))
    }

    test("a request whose choosers are not the seats sent without a role is refused") {
        assertEquals(Fixture(request(order = List(2L))).created.status, 400)
        assertEquals(Fixture(request(order = List(2L, 2L))).created.status, 400)
    }

    test("cancelling a match still choosing drops it") {
        val f = Fixture()
        assertEquals(f.post("/matches/m-1/cancel", "x", "").status, 204)
        assertEquals(f.get("/matches/m-1/status").status, 404)
    }

    test("tic-tac-toe cannot be conceded, so neither can its choosing") {
        val f = Fixture()
        assertEquals(f.post("/matches/m-1/role", "sub-bob", write(ChooseRequest(concede = true))).status, 400)
        assert(!f.view("sub-bob").canConcede)
    }

    test("a public match's board shows the choosing to anybody, with nobody's seat") {
        val f = Fixture(request(isPublic = true))
        val board = read[ChoosingView](f.get("/matches/m-1/board/state").body)
        assertEquals(board.you, None)
        assertEquals(board.chooser, Some(2L))
        assertEquals(Fixture().get("/matches/m-1/board/state").status, 403)
    }

    // ---- live ----------------------------------------------------------------------------------

    test("a live chooser who never opens the board, on a clock that does not wait for them, runs out and loses") {
        val f = Fixture(request(live = Some(Protocol.LiveTerms(60, "TOTAL", startOnOpen = Some(false)))))
        f.advance(59)
        assert(!f.status.completed)
        f.advance(2)
        val status = f.status
        assert(status.completed)
        val results = f.recorder.results.last._2.results
        assertEquals(
          results.map(r => r.participantId -> (r.rank, r.isWinner, r.forfeit)).toMap,
          Map(
            1L -> (1, true, true),
            2L -> (2, false, true)
          )
        )
        assertEquals(f.recorder.results.last._2.summary, Some("<strong>bob</strong> ran out of time choosing a role."))
        // And no move callbacks: a live match reports only its result.
        assert(f.recorder.moves.isEmpty)
        assertEquals(f.choose("sub-bob", "O").status, 400)
    }

    test("on a clock that waits for the board to be opened, a chooser who has not opened it does not run out") {
        val f = Fixture(request(live = Some(Protocol.LiveTerms(60, "TOTAL"))))
        f.advance(600)
        assert(!f.status.completed)
        assert(f.recorder.results.isEmpty)
    }

    test("under a live chess clock, the time a player spent choosing comes off their budget in the game") {
        val f = Fixture(request(live = Some(Protocol.LiveTerms(100, "TOTAL", startOnOpen = Some(false)))))
        f.advance(30)
        f.choose("sub-bob", "O")
        val clock = f.engine.core.clockView(f.store.get("m-1").get).get
        val remaining = clock.seats.map(s => s.participantId -> s.remainingMillis).toMap
        assertEquals(remaining(2L), Some(70000L))
        assertEquals(remaining(1L), Some(100000L))
    }

    test("a live game on a clock that does not wait for the board forfeits a first player who never opens it") {
        val roled = request(live = Some(Protocol.LiveTerms(60, "TOTAL", startOnOpen = Some(false)))).copy(
          roleChoice = None,
          players = request().players.zip(List("X", "O")).map((p, r) => p.copy(role = Some(r)))
        )
        val f = Fixture(roled)
        f.advance(61)
        assert(f.status.completed)
        val results = f.recorder.results.last._2.results
        assertEquals(results.find(_.participantId == 1L).map(r => (r.rank, r.forfeit)), Some((2, true)))
    }

    test("a choosing match round-trips through its json") {
        val f = Fixture()
        f.choose("sub-bob", "O")
        val stored = f.roles.get("m-1").get
        assertEquals(read[RoleChoosing](write(stored)), stored)
    }
}
