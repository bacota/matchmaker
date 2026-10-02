package com.vivi.stratego

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{EngineRequest, InMemoryMatchStore, PlayAuth, RecordingMatchmaker}
import Protocol.given
import Armies.{names, setup}

/** Drives the engine the way the outside world does: as requests, in the trusted local mode where a caller names
  * themselves with `?as=`.
  */
class RoutesSpec extends FunSuite {

    private def fixture() = {
        val store = InMemoryMatchStore[StrategoMatch]()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        val routes = Routes(engine, PlayAuth.Trusted, None)
        val create = Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "stratego",
          isPublic = true,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Blue"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )
        routes(EngineRequest("POST", "/games", Map.empty, write(create)))
        (routes, store)
    }

    private def post(routes: Routes, player: String, body: String) =
        routes(EngineRequest("POST", "/matches/m-9/moves", Map("as" -> player), body))

    private def deploy(routes: Routes, player: String, side: Side, fixed: Map[Int, Rank] = Map.empty) =
        post(routes, player, write(Protocol.MoveRequest(setup = Some(names(setup(side, fixed))))))

    test("a setup posted by a player answers with their own army, and nothing of the other's") {
        val (routes, _) = fixture()
        assertEquals(deploy(routes, "sub-bob", Side.Blue).status, 200)
        val answer = deploy(routes, "sub-alice", Side.Red)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.phase, "play")
        assertEquals(state.you, Some("Red"))
        assertEquals(state.turn, Some("Red"))
        assert(state.pieces.filter(_.side == "Blue").forall(_.rank.isEmpty))
    }

    test("a move posted as a pair of squares is made, and answered with the new state") {
        val (routes, store) = fixture()
        deploy(routes, "sub-alice", Side.Red, Map(30 -> Rank.Scout))
        deploy(routes, "sub-bob", Side.Blue)
        val answer = post(routes, "sub-alice", """{"from":30,"to":40}""")
        assertEquals(answer.status, 200)
        assertEquals(read[Protocol.StateResponse](answer.body).turn, Some("Blue"))
        assert(store.get("m-9").get.board(40).isDefined)
    }

    test("a refused move answers with the reason, and a body that is neither shape is a 400") {
        val (routes, _) = fixture()
        val early = post(routes, "sub-alice", """{"from":30,"to":40}""")
        assertEquals(early.status, 400)
        assertEquals(ujson.read(early.body)("error").str, "deploy your army before moving")

        val both = post(routes, "sub-alice", """{"setup":[],"from":30,"to":40}""")
        assertEquals(both.status, 400)
        assert(ujson.read(both.body)("error").str.startsWith("a move is either"))
        assertEquals(post(routes, "sub-alice", """{"from":30}""").status, 400)
    }

    test("the public board shows only what both players have seen") {
        val (routes, _) = fixture()
        deploy(routes, "sub-alice", Side.Red)
        deploy(routes, "sub-bob", Side.Blue)
        val board = read[Protocol.StateResponse](routes(EngineRequest("GET", "/matches/m-9/board/state")).body)
        assertEquals(board.you, None)
        assertEquals(board.pieces.size, 80)
        assert(board.pieces.forall(_.rank.isEmpty))
    }
}
