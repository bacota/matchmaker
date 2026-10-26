package com.vivi.rps

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{EngineRequest, InMemoryMatchStore, PlayAuth, RecordingMatchmaker}
import Protocol.given

/** Drives the engine the way the outside world does: as requests.
  *
  * The two servers are thin enough that if these pass, both do — which is the point of `Routes` being
  * transport-independent.
  */
class RoutesSpec extends FunSuite {

    /** Trusted play auth, which is the local zero-setup mode: a caller names themselves with `?as=`. What the play
      * routes do with the identity — find the seat, refuse if there is none — is the same whichever mode established
      * it, and the modes themselves are `PlayAuthSpec`.
      */
    private def fixture(isPublic: Boolean = true) = {
        val store = InMemoryMatchStore[RpsMatch]()
        val engine = Engine(store = store, matchmaker = RecordingMatchmaker(), baseUrl = "http://engine.test")
        val routes = Routes(engine = engine, playAuth = PlayAuth.Trusted, matchmakerKey = None)
        val created = routes(
          EngineRequest(method = "POST", path = "/games", query = Map.empty, body = write(createRequest(isPublic)))
        )
        (routes, store, created)
    }

    private def createRequest(isPublic: Boolean) =
        Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "rock-paper-scissors",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("One"),
              characterId = None,
              characterState = None
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Two"),
              characterId = None,
              characterState = None
            )
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest(method = "GET", path = path, query = query))

    private def as(player: String) = Map("as" -> player)

    private def throwing(routes: Routes, player: String, shape: String, matchId: String = "m-9") =
        routes(
          EngineRequest(
            method = "POST",
            path = s"/matches/$matchId/moves",
            query = as(player),
            body = write(Protocol.MoveRequest(shape))
          )
        )

    test("GET /matches/:id/status answers matchmaker's status call, with both seats pending") {
        val (routes, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes = routes, path = "/matches/m-9/status").body)
        assertEquals(status.isCompleted, false)
        assertEquals(status.participants.filter(_.isPending).map(_.participantId), List(1L, 2L))
    }

    test("a throw posted by a player is recorded and answered with the new state") {
        val (routes, store, _) = fixture()

        val answer = throwing(routes = routes, player = "sub-alice", shape = "rock")
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.you, Some("One"))
        assertEquals(state.yourThrow, Some("Rock"))
        assertEquals(state.waitingFor, List("Two"))
        assertEquals(state.isCompleted, false)
        assertEquals(store.get("m-9").get.throws.map(_.shape), List(Shape.Rock))
    }

    test("a throw may be named by its initial, and anything else is a 400 saying so") {
        val (routes, store, _) = fixture()

        assertEquals(
          read[Protocol.StateResponse](throwing(routes = routes, player = "sub-alice", shape = "s").body).yourThrow,
          Some("Scissors")
        )

        val bad = throwing(routes = routes, player = "sub-bob", shape = "scissor")
        assertEquals(bad.status, 400)
        assertEquals(ujson.read(bad.body)("error").str, "'scissor' is not a throw; expected rock, paper or scissors")
        assertEquals(store.get("m-9").get.throws.size, 1)
    }

    test("a refused throw answers with the reason and leaves the match alone") {
        val (routes, store, _) = fixture()
        throwing(routes = routes, player = "sub-alice", shape = "rock")

        val again = throwing(routes = routes, player = "sub-alice", shape = "paper")
        assertEquals(again.status, 400)
        assertEquals(ujson.read(again.body)("error").str, "you have already thrown; a throw cannot be taken back")
        assertEquals(store.get("m-9").get.throws.map(_.shape), List(Shape.Rock))
    }

    test("the second throw resolves the match over the wire, and both throws are then told") {
        val (routes, store, _) = fixture()
        throwing(routes = routes, player = "sub-alice", shape = "paper")

        val state = read[Protocol.StateResponse](throwing(routes = routes, player = "sub-bob", shape = "rock").body)
        assert(state.isCompleted)
        assertEquals(state.winner, Some("One"))
        assertEquals(state.players.map(_.shape), List(Some("Paper"), Some("Rock")))
        assert(store.get("m-9").get.isCompleted)
    }

    /* The one thing a watcher must not be able to do is see a throw before the player facing it
     * does — the public board is a url anybody may hold, including the other player. */
    test("the public board shows that a player has thrown, and not what") {
        val (routes, _, _) = fixture(isPublic = true)
        throwing(routes = routes, player = "sub-alice", shape = "rock")

        val watching = read[Protocol.StateResponse](get(routes = routes, path = "/matches/m-9/board/state").body)
        assertEquals(watching.players.map(_.isThrown), List(true, false))
        assert(watching.players.forall(_.shape.isEmpty))
        // The page names the three shapes as buttons, so what must be absent from it is a *thrown*
        // one: the state inlined into the public board carries none.
        assert(!get(routes = routes, path = "/matches/m-9/board").body.contains("\"shape\":\"Rock\""))

        throwing(routes = routes, player = "sub-bob", shape = "paper")
        val resolved = read[Protocol.StateResponse](get(routes = routes, path = "/matches/m-9/board/state").body)
        assertEquals(resolved.players.map(_.shape), List(Some("Rock"), Some("Paper")))
    }

    test("no page or state names a player by their sign-in subject, to the other player or on the public board") {
        val routes = fixture()._1
        def text(path: String, query: Map[String, String] = Map.empty) =
            routes(EngineRequest(method = "GET", path = path, query = query)).body
        val watched = List(text("/matches/m-9/board"), text("/matches/m-9/board/state"))
        assert(watched.forall(t => !t.contains("sub-alice") && !t.contains("sub-bob")))
        val alices = List(
          text(path = "/matches/m-9/play", query = as("sub-alice")),
          text(path = "/matches/m-9/state", query = as("sub-alice"))
        )
        assert(alices.forall(!_.contains("sub-bob")))
    }
}
