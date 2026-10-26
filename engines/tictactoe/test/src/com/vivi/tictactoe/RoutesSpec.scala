package com.vivi.tictactoe

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
    private def fixture() = {
        val store = InMemoryMatchStore[TicTacToeMatch]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store = store, matchmaker = recorder, baseUrl = "http://engine.test")
        val routes = Routes(engine = engine, playAuth = PlayAuth.Trusted, matchmakerKey = None)

        val create = Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "tic-tac-toe",
          isPublic = true,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("X"),
              characterId = None,
              characterState = None
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("O"),
              characterId = None,
              characterState = None
            )
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

        val created = routes(EngineRequest(method = "POST", path = "/games", query = Map.empty, body = write(create)))
        (routes, store, created)
    }

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest(method = "GET", path = path, query = query))

    private def as(player: String) = Map("as" -> player)

    test("GET /matches/:id/status answers matchmaker's status call") {
        val (routes, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes = routes, path = "/matches/m-9/status").body)
        assertEquals(status.isCompleted, false)
        assertEquals(status.participants.map(_.participantId).toSet, Set(1L, 2L))
    }

    test("a move posted by a player updates the board and answers with the new state") {
        val (routes, store, _) = fixture()

        val answer =
            routes(
              EngineRequest(
                method = "POST",
                path = "/matches/m-9/moves",
                query = as("sub-alice"),
                body = write(Protocol.MoveRequest(4))
              )
            )
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.board, "....X....")
        assertEquals(state.turn, Some("O"))
        assertEquals(state.you, Some("X"))
        assertEquals(store.get("m-9").get.board.encoded, "....X....")
    }

    test("a refused move answers with the reason and leaves the board alone") {
        val (routes, store, _) = fixture()

        val answer = routes(
          EngineRequest(
            method = "POST",
            path = "/matches/m-9/moves",
            query = as("sub-bob"),
            body = write(Protocol.MoveRequest(0))
          )
        )
        assertEquals(answer.status, 400)
        assertEquals(ujson.read(answer.body)("error").str, "it is X's turn, not O's")
        assertEquals(store.get("m-9").get.board.encoded, ".........")
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
