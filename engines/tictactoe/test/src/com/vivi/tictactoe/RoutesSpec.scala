package com.vivi.tictactoe

import munit.FunSuite
import upickle.default.{read, write}
import Protocol.given

/** Drives the engine the way the outside world does: as requests.
  *
  * The two servers are thin enough that if these pass, both do — which is the point of `Routes`
  * being transport-independent.
  */
class RoutesSpec extends FunSuite {

  /** Trusted play auth, which is the local zero-setup mode: a caller names themselves with
    * `?as=`. What the play routes do with the identity — find the seat, refuse if there is none —
    * is the same whichever mode established it, and the modes themselves are `PlayAuthSpec`.
    */
  private def fixture(isPublic: Boolean = true, playAuth: PlayAuth = PlayAuth.Trusted) = {
    val store = InMemoryMatchStore()
    val recorder = RecordingMatchmaker()
    val engine = Engine(store, recorder, "http://engine.test")
    val routes = Routes(engine, playAuth)

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

  test("the play page renders the board for the player whose seat it is") {
    val (routes, _, _) = fixture()

    val page = get(routes, "/matches/m-9/play", as("sub-alice"))
    assertEquals(page.status, 200)
    assertEquals(page.contentType, "text/html; charset=utf-8")
    assert(page.body.contains("<!doctype html>"))
    assert(page.body.contains("\"you\":\"X\""), "the seat's own state should be inlined into the page")
  }

  /* The page is served to anyone, signed in or not: it is a shell that offers a sign-in and then
   * fetches the state, so a player following the url from matchmaker gets somewhere to sign in
   * rather than a bare 401. It must not carry the board with it, which is what this checks. */
  test("the play page for a stranger carries no state and offers a sign-in") {
    val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback")
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
    assertEquals(get(routes, "/matches/m-9/state").status, 403)
    assertEquals(routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-carol"), write(Protocol.MoveRequest(0)))).status, 403)
  }

  test("a move posted by a player updates the board and answers with the new state") {
    val (routes, store, _) = fixture()

    val answer = routes(EngineRequest("POST", "/matches/m-9/moves", as("sub-alice"), write(Protocol.MoveRequest(4))))
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
    val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback")
    val (withPool, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
    val page = get(withPool, "/auth/callback")
    assertEquals(page.status, 200)
    assert(page.body.contains("oauth2/token"), "the callback page redeems the authorization code")

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

  test("a lambda event decodes to the same request the local server builds, claims included") {
    val event = ujson.Obj(
      "rawPath" -> "/matches/m-9/moves",
      "requestContext" -> ujson.Obj(
        "http" -> ujson.Obj("method" -> "POST"),
        // What the JWT authorizer writes into the event once it has verified the token.
        "authorizer" -> ujson.Obj("jwt" -> ujson.Obj("claims" -> ujson.Obj("sub" -> "sub-alice", "token_use" -> "id")))
      ),
      "headers" -> ujson.Obj("Content-Type" -> "application/json"),
      "body" -> """{"cell":4}""",
      "isBase64Encoded" -> false
    )
    val decoded = Handler.decode(ujson.write(event))
    assertEquals(decoded.method, "POST")
    assertEquals(decoded.path, "/matches/m-9/moves")
    assertEquals(decoded.body, """{"cell":4}""")
    assertEquals(decoded.claims.get("sub"), Some("sub-alice"))
    // Lowercased on the way in, since payload v2 does and a lookup for "Authorization" must match.
    assertEquals(decoded.headers.get("content-type"), Some("application/json"))

    val encoded = ujson.read(Handler.encode(EngineResponse(201, """{"ok":true}""")))
    assertEquals(encoded("statusCode").num, 201.0)
    assertEquals(encoded("body").str, """{"ok":true}""")
  }
}
