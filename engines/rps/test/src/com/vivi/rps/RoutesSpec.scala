package com.vivi.rps

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
  private def fixture(
      isPublic: Boolean = true,
      playAuth: PlayAuth = PlayAuth.Trusted,
      matchmakerKey: Option[String] = None
  ) = {
    val store = InMemoryMatchStore()
    val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
    val routes = Routes(engine, playAuth, matchmakerKey)
    val created = routes(EngineRequest("POST", "/games", Map.empty, write(createRequest(isPublic))))
    (routes, store, created)
  }

  private def createRequest(isPublic: Boolean = true, matchId: String = "m-9") =
    Protocol.CreateGameRequest(
      matchId = matchId,
      gameName = "rock-paper-scissors",
      isPublic = isPublic,
      parameters = Map.empty,
      settings = "{}",
      timeLimitSeconds = None,
      players = List(
        Protocol.EnginePlayer("sub-alice", 1L, Some("One"), None, None),
        Protocol.EnginePlayer("sub-bob", 2L, Some("Two"), None, None)
      ),
      moveCallbackUrl = None,
      resultsCallbackUrl = None
    )

  private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
    routes(EngineRequest("GET", path, query))

  private def as(player: String) = Map("as" -> player)

  private def throwing(routes: Routes, player: String, shape: String, matchId: String = "m-9") =
    routes(EngineRequest("POST", s"/matches/$matchId/moves", as(player), write(Protocol.MoveRequest(shape))))

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

  test("GET /matches/:id/status answers matchmaker's status call, with both seats pending") {
    val (routes, _, _) = fixture()
    val status = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
    assertEquals(status.completed, false)
    assertEquals(status.participants.filter(_.pending).map(_.participantId), List(1L, 2L))
  }

  test("GET /matches/:id/status passes `since` through, and refuses one it cannot read") {
    val (routes, _, _) = fixture()
    throwing(routes, "sub-alice", "rock")

    val all = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
    assertEquals(all.turns.map(_.participantId), List(1L))

    // From after the only throw there is: nothing left to report.
    val since = all.turns.head.takenAt.toString
    val later = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status", Map("since" -> since)).body)
    assertEquals(later.turns, Nil)

    // Not a time at all. Answered as a bad request rather than as "send everything", which
    // would report turns matchmaker already has and charge them twice.
    assertEquals(get(routes, "/matches/m-9/status", Map("since" -> "yesterday")).status, 400)
  }

  test("the play page renders the state for the player whose seat it is") {
    val (routes, _, _) = fixture()

    val page = get(routes, "/matches/m-9/play", as("sub-alice"))
    assertEquals(page.status, 200)
    assertEquals(page.contentType, "text/html; charset=utf-8")
    assert(page.body.contains("<!doctype html>"))
    assert(page.body.contains("\"you\":\"One\""), "the seat's own state should be inlined into the page")
  }

  /* The page is served to anyone, signed in or not: it is a shell that offers a sign-in and then
   * fetches the state, so a player following the url from matchmaker gets somewhere to sign in
   * rather than a bare 401. It must not carry the match with it, which is what this checks. */
  test("the play page for a stranger carries no state and offers a sign-in") {
    val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback")
    val (routes, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))

    val page = get(routes, "/matches/m-9/play")
    assertEquals(page.status, 200)
    assert(page.body.contains("let state = null"), "a stranger's page must not carry the match")
    assert(page.body.contains("sign in to play"))
    assert(page.body.contains("client-1"), "the page needs the app client to start a sign-in")
  }

  test("the state and move routes refuse a caller with no seat") {
    val (routes, _, _) = fixture()
    assertEquals(get(routes, "/matches/m-9/state", as("sub-carol")).status, 403)
    assertEquals(get(routes, "/matches/m-9/state").status, 403)
    assertEquals(throwing(routes, "sub-carol", "rock").status, 403)
  }

  test("a throw posted by a player is recorded and answered with the new state") {
    val (routes, store, _) = fixture()

    val answer = throwing(routes, "sub-alice", "rock")
    assertEquals(answer.status, 200)
    val state = read[Protocol.StateResponse](answer.body)
    assertEquals(state.you, Some("One"))
    assertEquals(state.yourThrow, Some("Rock"))
    assertEquals(state.waitingFor, List("Two"))
    assertEquals(state.completed, false)
    assertEquals(store.get("m-9").get.throws.map(_.shape), List(Shape.Rock))
  }

  test("a throw may be named by its initial, and anything else is a 400 saying so") {
    val (routes, store, _) = fixture()

    assertEquals(read[Protocol.StateResponse](throwing(routes, "sub-alice", "s").body).yourThrow, Some("Scissors"))

    val bad = throwing(routes, "sub-bob", "scissor")
    assertEquals(bad.status, 400)
    assertEquals(ujson.read(bad.body)("error").str, "'scissor' is not a throw; expected rock, paper or scissors")
    assertEquals(store.get("m-9").get.throws.size, 1)
  }

  test("a refused throw answers with the reason and leaves the match alone") {
    val (routes, store, _) = fixture()
    throwing(routes, "sub-alice", "rock")

    val again = throwing(routes, "sub-alice", "paper")
    assertEquals(again.status, 400)
    assertEquals(ujson.read(again.body)("error").str, "you have already thrown; a throw cannot be taken back")
    assertEquals(store.get("m-9").get.throws.map(_.shape), List(Shape.Rock))
  }

  test("the second throw resolves the match over the wire, and both throws are then told") {
    val (routes, store, _) = fixture()
    throwing(routes, "sub-alice", "paper")

    val state = read[Protocol.StateResponse](throwing(routes, "sub-bob", "rock").body)
    assert(state.completed)
    assertEquals(state.winner, Some("One"))
    assertEquals(state.players.map(_.shape), List(Some("Paper"), Some("Rock")))
    assert(store.get("m-9").get.completed)
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

  /* The one thing a watcher must not be able to do is see a throw before the player facing it
   * does — the public board is a url anybody may hold, including the other player. */
  test("the public board shows that a player has thrown, and not what") {
    val (routes, _, _) = fixture(isPublic = true)
    throwing(routes, "sub-alice", "rock")

    val watching = read[Protocol.StateResponse](get(routes, "/matches/m-9/board/state").body)
    assertEquals(watching.players.map(_.thrown), List(true, false))
    assert(watching.players.forall(_.shape.isEmpty))
    // The page names the three shapes as buttons, so what must be absent from it is a *thrown*
    // one: the state inlined into the public board carries none.
    assert(!get(routes, "/matches/m-9/board").body.contains("\"shape\":\"Rock\""))

    throwing(routes, "sub-bob", "paper")
    val resolved = read[Protocol.StateResponse](get(routes, "/matches/m-9/board/state").body)
    assertEquals(resolved.players.map(_.shape), List(Some("Rock"), Some("Paper")))
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
      "body" -> """{"shape":"rock"}""",
      "isBase64Encoded" -> false
    )
    val decoded = Handler.decode(ujson.write(event))
    assertEquals(decoded.method, "POST")
    assertEquals(decoded.path, "/matches/m-9/moves")
    assertEquals(decoded.body, """{"shape":"rock"}""")
    assertEquals(decoded.claims.get("sub"), Some("sub-alice"))
    // Lowercased on the way in, since payload v2 does and a lookup for "Authorization" must match.
    assertEquals(decoded.headers.get("content-type"), Some("application/json"))

    val encoded = ujson.read(Handler.encode(EngineResponse(201, """{"ok":true}""")))
    assertEquals(encoded("statusCode").num, 201.0)
    assertEquals(encoded("body").str, """{"ok":true}""")
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
    val engine = Engine(InMemoryMatchStore(), RecordingMatchmaker(), "http://engine.test")
    val routes = Routes(engine, PlayAuth.Trusted, Some("s3cret"))
    val keyed = Map("x-api-key" -> "s3cret")
    assertEquals(routes(EngineRequest("POST", "/games", Map.empty, write(createRequest(matchId = "m-1")), keyed)).status, 201)
    assertEquals(routes(EngineRequest("GET", "/matches/m-1/status", headers = keyed)).status, 200)
  }

  test("a player route is not protected by matchmaker's key") {
    // The key guards the two routes that are matchmaker's, and only those: a player has no key
    // and must still reach the play page.
    val (routes, _, _) = fixture()
    assertEquals(routes(EngineRequest("GET", "/health")).status, 200)
  }

  test("MATCHMAKER_API_KEY is required in Lambda and optional outside it") {
    assertEquals(Config.matchmakerKey(Map("MATCHMAKER_API_KEY" -> "k").get), Some("k"))
    assertEquals(Config.matchmakerKey(_ => None), None)
    // Blank is the same as unset: a variable set to "" is a forgotten one, not an opt-out.
    assertEquals(Config.matchmakerKey(Map("MATCHMAKER_API_KEY" -> "  ").get), None)
    intercept[IllegalStateException](Config.matchmakerKey(Map("AWS_LAMBDA_FUNCTION_NAME" -> "engine").get))
  }
}
