package com.vivi.stratego

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{
    EngineRequest,
    InMemoryMatchStore,
    InMemoryMessageStore,
    Messages,
    PlayAuth,
    RecordingMatchmaker
}
import Protocol.given
import Armies.{names, setup}

/** Drives the engine the way the outside world does: as requests, in the trusted local mode where a caller names
  * themselves with `?as=`.
  */
class RoutesSpec extends FunSuite {

    private def fixture(isPublic: Boolean = true) = {
        val store = InMemoryMatchStore[StrategoMatch]()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        val nicknames = Map("sub-alice" -> "Alice", "sub-bob" -> "Bob", "sub-carol" -> "Carol")
        val routes =
            Routes(engine, PlayAuth.Trusted, None, messages = Some(Messages(InMemoryMessageStore(), nicknames.get)))
        val create = Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "stratego",
          isPublic = isPublic,
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

    test("a concession posted by a player ends the match, and answers with the state it left") {
        val (routes, store) = fixture()
        val answer = post(routes, "sub-bob", """{"concede":true}""")
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.phase, "over")
        assertEquals(state.winner, Some("Red"))
        assertEquals(store.get("m-9").get.conceded, Some(Side.Blue))
        // A concession with anything else in the body is neither shape.
        assertEquals(post(routes, "sub-alice", """{"concede":true,"from":30,"to":40}""").status, 400)
    }

    private def say(routes: Routes, who: String, board: String, text: String) =
        routes(
          EngineRequest(
            "POST",
            "/matches/m-9/messages",
            Map("as" -> who),
            ujson.write(ujson.Obj("board" -> board, "text" -> text))
          )
        )

    private def boards(routes: Routes, who: Option[String]) = {
        val response = who match {
            case Some(w) => routes(EngineRequest("GET", "/matches/m-9/messages", Map("as" -> w), ""))
            case None    => routes(EngineRequest("GET", "/matches/m-9/board/messages", Map.empty, ""))
        }
        (response.status, Option.when(response.status == 200)(ujson.read(response.body)))
    }

    private def texts(view: ujson.Value, board: String): Option[List[String]] =
        view.obj.get(board).filterNot(_.isNull).map(_.arr.toList.map(_("text").str))

    test("players write to each other, and a signed-in watcher of a public match writes on the observers' board") {
        val (routes, _) = fixture()
        assertEquals(say(routes, "sub-alice", "players", "good luck").status, 200)
        assertEquals(say(routes, "sub-carol", "observers", "go red").status, 200)

        val (_, Some(alice)) = boards(routes, Some("sub-alice")): @unchecked
        assertEquals(texts(alice, "players"), Some(List("good luck")))
        assertEquals(alice("players")(0)("name").str, "Alice")
        assert(alice("players")(0)("mine").bool)
        // Not until the match is over.
        assertEquals(texts(alice, "observers"), None)

        val (_, Some(carol)) = boards(routes, Some("sub-carol")): @unchecked
        assertEquals(texts(carol, "players"), Some(List("good luck")))
        assertEquals(texts(carol, "observers"), Some(List("go red")))
        assertEquals(carol("canWrite").arr.map(_.str).toList, List("observers"))

        // Somebody not signed in reads the public board's, and is told signing in would let them write.
        val (_, Some(anyone)) = boards(routes, None): @unchecked
        assertEquals(texts(anyone, "observers"), Some(List("go red")))
        assert(anyone("signInToWrite").bool)
    }

    test("nobody writes on the other's board, and nobody signed out writes at all") {
        val (routes, _) = fixture()
        assertEquals(say(routes, "sub-alice", "observers", "psst").status, 403)
        assertEquals(say(routes, "sub-carol", "players", "hi").status, 403)
        assertEquals(
          routes(
            EngineRequest("POST", "/matches/m-9/messages", Map.empty, """{"board":"observers","text":"hi"}""")
          ).status,
          401
        )
        assertEquals(say(routes, "sub-alice", "lobby", "hi").status, 400)
    }

    test("a private match has no observers: nobody without a seat reads or writes its boards") {
        val (routes, _) = fixture(isPublic = false)
        assertEquals(say(routes, "sub-alice", "players", "gl").status, 200)
        assertEquals(boards(routes, Some("sub-carol"))._1, 403)
        assertEquals(boards(routes, None)._1, 403)
        assertEquals(say(routes, "sub-carol", "observers", "hi").status, 403)
    }

    test("once the match is over the players read the observers' board, and nobody writes on either") {
        val (routes, _) = fixture()
        say(routes, "sub-carol", "observers", "go red")
        post(routes, "sub-bob", """{"concede":true}""")
        val (_, Some(alice)) = boards(routes, Some("sub-alice")): @unchecked
        assertEquals(texts(alice, "observers"), Some(List("go red")))
        assertEquals(alice("canWrite").arr.toList, Nil)
        assertEquals(say(routes, "sub-alice", "players", "gg").status, 400)
    }

    test("a refused move answers with the reason, and a body that is none of the shapes is a 400") {
        val (routes, _) = fixture()
        val early = post(routes, "sub-alice", """{"from":30,"to":40}""")
        assertEquals(early.status, 400)
        assertEquals(ujson.read(early.body)("error").str, "deploy your army before moving")

        val both = post(routes, "sub-alice", """{"setup":[],"from":30,"to":40}""")
        assertEquals(both.status, 400)
        assert(ujson.read(both.body)("error").str.startsWith("a move is {"))
        assertEquals(post(routes, "sub-alice", """{"from":30}""").status, 400)
    }

    test("a move from a square off the board is a 400, not a 500") {
        val (routes, _) = fixture()
        deploy(routes, "sub-alice", Side.Red)
        deploy(routes, "sub-bob", Side.Blue)
        val answer = post(routes, "sub-alice", """{"from":100,"to":0}""")
        assertEquals(answer.status, 400)
        assertEquals(
          ujson.read(answer.body)("error").str,
          "square 100 is not on the board; squares are numbered 0 to 99"
        )
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
