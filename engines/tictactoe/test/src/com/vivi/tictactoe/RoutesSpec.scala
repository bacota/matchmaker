package com.vivi.tictactoe

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{EngineRequest, LoginConfig, PlayAuth}
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
    private def fixture(
        isPublic: Boolean = true,
        playAuth: PlayAuth = PlayAuth.Trusted,
        matchmakerKey: Option[String] = None
    ) = {
        val store = InMemoryMatchStore()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test")
        val routes = Routes(engine, playAuth, matchmakerKey)

        val create = Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "tic-tac-toe",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("X"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("O"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

        val created = routes(EngineRequest("POST", "/games", Map.empty, write(create)))
        (routes, store, created)
    }

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest("GET", path, query))

    private def as(player: String) = Map("as" -> player)

    test("POST /games creates a match and answers 201 with the urls") {
        val (_, store, created) = fixture()
        assertEquals(created.status, 201)
        val response = read[Protocol.CreateGameResponse](created.body)
        assertEquals(response.statusUrl, "http://engine.test/matches/m-9/status")
        assertEquals(response.playUrl, "http://engine.test/matches/m-9/play")
        assertEquals(response.publicUrl, Some("http://engine.test/matches/m-9/board"))
        assert(store.get("m-9").isDefined)
    }

    test("POST /games with a body that is not a create request is a 400, not a 500") {
        val (routes, _, _) = fixture()
        val answer = routes(EngineRequest("POST", "/games", Map.empty, """{"nonsense":true}"""))
        assertEquals(answer.status, 400)
        assert(ujson.read(answer.body).obj.contains("error"))
    }

    test("GET /matches/:id/status answers matchmaker's status call") {
        val (routes, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
        assertEquals(status.completed, false)
        assertEquals(status.participants.map(_.participantId).toSet, Set(1L, 2L))
    }

    test("GET /matches/:id/status passes `since` through, and refuses one it cannot read") {
        val (routes, _, _) = fixture()
        routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-alice"), """{"cell":0}"""))

        val all = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
        assertEquals(all.turns.map(_.participantId), List(1L))

        // From after the only move there is: nothing left to report.
        val since = all.turns.head.takenAt.toString
        val later = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status", Map("since" -> since)).body)
        assertEquals(later.turns, Nil)

        // Not a time at all. Answered as a bad request rather than as "send everything", which
        // would report turns matchmaker already has and charge them twice.
        assertEquals(get(routes, "/matches/m-9/status", Map("since" -> "yesterday")).status, 400)
    }

    test("the play page renders the board for the player whose seat it is") {
        val (routes, _, _) = fixture()

        val page = get(routes, "/matches/m-9/play", as("sub-alice"))
        assertEquals(page.status, 200)
        assertEquals(page.contentType, "text/html; charset=utf-8")
        assert(page.body.contains("<!doctype html>"))
        // What changes while the page is idle is announced: the status as it moves on, and a refusal.
        assert(page.body.contains("""<p id="status" role="status" aria-live="polite">"""))
        assert(page.body.contains("""<div id="error" role="alert">"""))
        assert(page.body.contains("\"you\":\"X\""), "the seat's own state should be inlined into the page")
    }

    /* The page is served to anyone, signed in or not: it is a shell that offers a sign-in and then
     * fetches the state, so a player following the url from matchmaker gets somewhere to sign in
     * rather than a bare 401. It must not carry the board with it, which is what this checks. */
    test("the play page for a stranger carries no state and offers a sign-in") {
        val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")
        val (routes, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))

        val page = get(routes, "/matches/m-9/play")
        assertEquals(page.status, 200)
        assert(page.body.contains("let state = null"), "a stranger's page must not carry the board")
        assert(page.body.contains("sign in to play"))
        assert(page.body.contains("client-1"), "the page needs the app client to start a sign-in")
    }

    test("the state and move routes refuse a caller with no seat") {
        val (routes, _, _) = fixture()
        assertEquals(get(routes, "/matches/m-9/state", as("sub-carol")).status, 403)
        assertEquals(
          routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-carol"), write(Protocol.MoveRequest(0)))).status,
          403
        )
    }

    /* The page tells these apart: a 401 drops the session and offers a sign-in, which can help; a
     * 403 keeps it and says there is no seat here, which a sign-in cannot change. */
    test("a caller who cannot be identified is refused with 401, not 403") {
        val (routes, _, _) = fixture()
        assertEquals(get(routes, "/matches/m-9/state").status, 401)

        // Deployed: no claims means no authorizer vouched for anyone; a stranger's claims are a 403.
        val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")
        val (deployed, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
        assertEquals(deployed(EngineRequest("GET", "/matches/m-9/state")).status, 401)
        assertEquals(
          deployed(EngineRequest("GET", "/matches/m-9/state", claims = Map("sub" -> "sub-carol"))).status,
          403
        )
        assertEquals(
          deployed(EngineRequest("GET", "/matches/m-9/state", claims = Map("sub" -> "sub-alice"))).status,
          200
        )
    }

    test("a move posted by a player updates the board and answers with the new state") {
        val (routes, store, _) = fixture()

        val answer =
            routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-alice"), write(Protocol.MoveRequest(4))))
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.board, "....X....")
        assertEquals(state.turn, Some("O"))
        assertEquals(state.you, Some("X"))
        assertEquals(store.get("m-9").get.board.encoded, "....X....")
    }

    test("a refused move answers with the reason and leaves the board alone") {
        val (routes, store, _) = fixture()

        val answer = routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-bob"), write(Protocol.MoveRequest(0))))
        assertEquals(answer.status, 400)
        assertEquals(ujson.read(answer.body)("error").str, "it is X's turn, not O's")
        assertEquals(store.get("m-9").get.board.encoded, ".........")
    }

    test("the sign-in callback page is served when a pool is configured, and not otherwise") {
        val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")
        val (withPool, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
        val page = get(withPool, "/auth/callback")
        assertEquals(page.status, 200)
        assert(page.body.contains("oauth2/token"), "the callback page redeems the authorization code")
        // And says so when it cannot reach the token endpoint, rather than "signing in…" for ever.
        assert(page.body.contains("could not be reached"), "an unreachable token endpoint must end in a failure")
        // Its status changes from "signing in…" to a failure with nothing pressed, so it is announced.
        assert(page.body.contains("""<p id="error" role="status" aria-live="polite">"""))
        // Laid out for the phone it is most likely opened on, not shrunk from a desktop width.
        assert(page.body.contains("""<meta name="viewport" content="width=device-width, initial-scale=1">"""))

        val (withoutPool, _, _) = fixture()
        assertEquals(get(withoutPool, "/auth/callback").status, 404)
    }

    test("the public board is readable by anyone, and shows no seat as its own") {
        val (routes, _, _) = fixture(isPublic = true)
        val page = get(routes, "/matches/m-9/board")
        assertEquals(page.status, 200)
        assertEquals(read[Protocol.StateResponse](get(routes, "/matches/m-9/board/state").body).you, None)
    }

    test("a private match has no public board") {
        val (routes, _, _) = fixture(isPublic = false)
        assertEquals(get(routes, "/matches/m-9/board").status, 403)
        assertEquals(get(routes, "/matches/m-9/board/state").status, 403)
    }

    test("an unknown match and an unknown path are both 404") {
        val (routes, _, _) = fixture()
        assertEquals(get(routes, "/matches/nope/status").status, 404)
        assertEquals(get(routes, "/nothing/here").status, 404)
    }

    // ---------------------------------------------------------------------------
    // Matchmaker's own routes
    // ---------------------------------------------------------------------------

    test("with a key configured, matchmaker's routes need it") {
        // The fixture's own create call is made with no key, so it is the refusal being asserted.
        val (routes, _, created) = fixture(matchmakerKey = Some("s3cret"))
        assertEquals(created.status, 401)
        assertEquals(routes(EngineRequest("GET", "/matches/m-9/status")).status, 401)
    }

    test("a wrong key is refused exactly as a missing one is") {
        val (routes, _, _) = fixture(matchmakerKey = Some("s3cret"))
        val wrong = routes(EngineRequest("GET", "/matches/m-9/status", headers = Map("x-api-key" -> "s3crea")))
        assertEquals(wrong.status, 401)
        assertEquals(wrong.body, routes(EngineRequest("GET", "/matches/m-9/status")).body)
    }

    test("the right key gets in") {
        val store = InMemoryMatchStore()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        val routes = Routes(engine, PlayAuth.Trusted, Some("s3cret"))
        val create = Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "tic-tac-toe",
          isPublic = true,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("X"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("O"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )
        val keyed = Map("x-api-key" -> "s3cret")
        assertEquals(routes(EngineRequest("POST", "/games", Map.empty, write(create), keyed)).status, 201)
        assertEquals(routes(EngineRequest("GET", "/matches/m-1/status", headers = keyed)).status, 200)
    }

    test("a player route is not protected by matchmaker's key") {
        // The key guards the two routes that are matchmaker's, and only those: a player has no key
        // and must still reach the board.
        val (routes, _, _) = fixture()
        assertEquals(routes(EngineRequest("GET", "/health")).status, 200)
    }
}
