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
        val engine = Engine(store = store, matchmaker = RecordingMatchmaker(), baseUrl = "http://engine.test")
        val routes = Routes(engine = engine, playAuth = PlayAuth.Trusted, matchmakerKey = None)
        val create = Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "stratego",
          isPublic = true,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("Red"),
              characterId = None,
              characterState = None
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Blue"),
              characterId = None,
              characterState = None
            )
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )
        routes(EngineRequest(method = "POST", path = "/games", query = Map.empty, body = write(create)))
        (routes, store)
    }

    private def post(routes: Routes, player: String, body: String) =
        routes(EngineRequest(method = "POST", path = "/matches/m-9/moves", query = Map("as" -> player), body = body))

    private def deploy(routes: Routes, player: String, side: Side, fixed: Map[Int, Rank] = Map.empty) =
        post(
          routes = routes,
          player = player,
          body = write(Protocol.MoveRequest(setup = Some(names(setup(side, fixed)))))
        )

    test("a setup posted by a player answers with their own army, and nothing of the other's") {
        val (routes, _) = fixture()
        assertEquals(deploy(routes = routes, player = "sub-bob", side = Side.Blue).status, 200)
        val answer = deploy(routes = routes, player = "sub-alice", side = Side.Red)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.phase, "play")
        assertEquals(state.you, Some("Red"))
        assertEquals(state.turn, Some("Red"))
        assert(state.pieces.filter(_.side == "Blue").forall(_.rank.isEmpty))
    }

    test("a move posted as a pair of squares is made, and answered with the new state") {
        val (routes, store) = fixture()
        deploy(routes = routes, player = "sub-alice", side = Side.Red, fixed = Map(30 -> Rank.Scout))
        deploy(routes = routes, player = "sub-bob", side = Side.Blue)
        val answer = post(routes = routes, player = "sub-alice", body = """{"from":30,"to":40}""")
        assertEquals(answer.status, 200)
        assertEquals(read[Protocol.StateResponse](answer.body).turn, Some("Blue"))
        assert(store.get("m-9").get.board(40).isDefined)
    }

    test("a concession posted by a player ends the match, and answers with the state it left") {
        val (routes, store) = fixture()
        val answer = post(routes = routes, player = "sub-bob", body = """{"isConcession":true}""")
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.phase, "over")
        assertEquals(state.winner, Some("Red"))
        assertEquals(store.get("m-9").get.conceded, Some(Side.Blue))
        // A concession with anything else in the body is neither shape.
        assertEquals(
          post(routes = routes, player = "sub-alice", body = """{"isConcession":true,"from":30,"to":40}""").status,
          400
        )
    }

    test("a refused move answers with the reason, and a body that is none of the shapes is a 400") {
        val (routes, _) = fixture()
        val early = post(routes = routes, player = "sub-alice", body = """{"from":30,"to":40}""")
        assertEquals(early.status, 400)
        assertEquals(ujson.read(early.body)("error").str, "deploy your army before moving")

        val both = post(routes = routes, player = "sub-alice", body = """{"setup":[],"from":30,"to":40}""")
        assertEquals(both.status, 400)
        assert(ujson.read(both.body)("error").str.startsWith("a move is {"))
        assertEquals(post(routes = routes, player = "sub-alice", body = """{"from":30}""").status, 400)
    }

    test("a move from a square off the board is a 400, not a 500") {
        val (routes, _) = fixture()
        deploy(routes = routes, player = "sub-alice", side = Side.Red)
        deploy(routes = routes, player = "sub-bob", side = Side.Blue)
        val answer = post(routes = routes, player = "sub-alice", body = """{"from":100,"to":0}""")
        assertEquals(answer.status, 400)
        assertEquals(
          ujson.read(answer.body)("error").str,
          "square 100 is not on the board; squares are numbered 0 to 99"
        )
    }

    test("the public board shows only what both players have seen") {
        val (routes, _) = fixture()
        deploy(routes = routes, player = "sub-alice", side = Side.Red)
        deploy(routes = routes, player = "sub-bob", side = Side.Blue)
        val board =
            read[Protocol.StateResponse](routes(EngineRequest(method = "GET", path = "/matches/m-9/board/state")).body)
        assertEquals(board.you, None)
        assertEquals(board.pieces.size, 80)
        assert(board.pieces.forall(_.rank.isEmpty))
    }
}
