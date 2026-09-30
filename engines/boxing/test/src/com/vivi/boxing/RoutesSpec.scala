package com.vivi.boxing

import munit.FunSuite
import upickle.default.{read, write}
import Protocol.given

/** Drives the engine the way the outside world does: as requests.
  *
  * The two servers are thin enough that if these pass, both do — which is the point of `Routes` being
  * transport-independent.
  */
class RoutesSpec extends FunSuite {

    private val average = Fighter(5, 5, 5, 5, 5)

    /** Trusted play auth, the local zero-setup mode: a caller names themselves with `?as=`. */
    private def fixture(
        isPublic: Boolean = true,
        playAuth: PlayAuth = PlayAuth.Trusted,
        matchmakerKey: Option[String] = None,
        blue: Option[Fighter] = Some(average)
    ) = {
        val store = InMemoryMatchStore()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test")
        val routes = Routes(engine, playAuth, matchmakerKey)
        val created = routes(EngineRequest("POST", "/games", Map.empty, write(createRequest(isPublic, blue = blue))))
        (routes, store, created, recorder)
    }

    private def createRequest(
        isPublic: Boolean = true,
        matchId: String = "m-9",
        blue: Option[Fighter] = Some(average)
    ) =
        Protocol.CreateGameRequest(
          matchId = matchId,
          gameName = "boxing",
          isPublic = isPublic,
          parameters = Map("rounds" -> "3"),
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), Some(101L), Some(Fighter.toState(average))),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Blue"), Some(202L), blue.map(Fighter.toState).orElse(Some("")))
          ),
          moveCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/moves"),
          resultsCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/results")
        )

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest("GET", path, query))

    private def as(player: String) = Map("as" -> player)

    private def planning(
        routes: Routes,
        player: String,
        offense: Int,
        defense: Int,
        power: Int,
        matchId: String = "m-9"
    ) =
        routes(
          EngineRequest(
            "POST",
            s"/matches/$matchId/moves",
            as(player),
            write(Protocol.PlanRequest(offense, defense, power))
          )
        )

    private def building(routes: Routes, player: String, f: Fighter) =
        routes(
          EngineRequest(
            "POST",
            "/matches/m-9/fighter",
            as(player),
            write(Protocol.BuildRequest(f.strength, f.speed, f.agility, f.workrate, f.chin))
          )
        )

    test("POST /games creates a bout and answers 201 with the urls") {
        val (_, store, created, _) = fixture()
        assertEquals(created.status, 201)
        val response = read[Protocol.CreateGameResponse](created.body)
        assertEquals(response.statusUrl, "http://engine.test/matches/m-9/status")
        assertEquals(response.playUrl, "http://engine.test/matches/m-9/play")
        assertEquals(response.publicUrl, Some("http://engine.test/matches/m-9/board"))
        assert(store.get("m-9").isDefined)
    }

    test("POST /games with a body that is not a create request is a 400, not a 500") {
        val (routes, _, _, _) = fixture()
        val answer = routes(EngineRequest("POST", "/games", Map.empty, """{"nonsense":true}"""))
        assertEquals(answer.status, 400)
        assert(ujson.read(answer.body).obj.contains("error"))
    }

    test("GET /matches/:id/status answers matchmaker's status call, with both corners pending") {
        val (routes, _, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
        assertEquals(status.completed, false)
        assertEquals(status.participants.filter(_.pending).map(_.participantId), List(1L, 2L))
    }

    test("GET /matches/:id/status passes `since` through, and refuses one it cannot read") {
        val (routes, _, _, _) = fixture()
        planning(routes, "sub-alice", 5, 0, 0)

        val all = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
        assertEquals(all.turns.map(_.participantId), List(1L))

        val since = all.turns.head.takenAt.toString
        val later = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status", Map("since" -> since)).body)
        assertEquals(later.turns, Nil)

        assertEquals(get(routes, "/matches/m-9/status", Map("since" -> "yesterday")).status, 400)
    }

    test("the play page renders the state for the player whose corner it is") {
        val (routes, _, _, _) = fixture()

        val page = get(routes, "/matches/m-9/play", as("sub-alice"))
        assertEquals(page.status, 200)
        assertEquals(page.contentType, "text/html; charset=utf-8")
        assert(page.body.contains("<!doctype html>"))
        assert(page.body.contains("\"you\":\"Red\""), "the corner's own state should be inlined into the page")
        assert(page.body.contains("plan round 1"))
    }

    test("the play page for a stranger carries no state and offers a sign-in") {
        val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")
        val (routes, _, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))

        val page = get(routes, "/matches/m-9/play")
        assertEquals(page.status, 200)
        assert(page.body.contains("let state = null"), "a stranger's page must not carry the bout")
        assert(page.body.contains("sign in to fight"))
        assert(page.body.contains("client-1"), "the page needs the app client to start a sign-in")
    }

    test("the state, move and fighter routes refuse a caller with no corner") {
        val (routes, _, _, _) = fixture()
        assertEquals(get(routes, "/matches/m-9/state", as("sub-carol")).status, 403)
        assertEquals(get(routes, "/matches/m-9/state").status, 403)
        assertEquals(planning(routes, "sub-carol", 5, 0, 0).status, 403)
        assertEquals(building(routes, "sub-carol", average).status, 403)
    }

    test("a plan posted by a player is recorded and answered with the new state") {
        val (routes, store, _, _) = fixture()

        val answer = planning(routes, "sub-alice", 3, 1, 1)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.you, Some("Red"))
        assertEquals(state.yourPlan, Some(Protocol.PlanRequest(3, 1, 1)))
        assertEquals(state.waitingFor, List("Blue"))
        assertEquals(store.get("m-9").get.plans.map(_.allocation), List(Allocation(3, 1, 1)))
    }

    test("a refused plan answers 400 with the reason and leaves the bout alone") {
        val (routes, store, _, _) = fixture()

        val over = planning(routes, "sub-alice", 3, 3, 3)
        assertEquals(over.status, 400)
        assertEquals(
          ujson.read(over.body)("error").str,
          "a round is planned with exactly your workrate of 5; these add up to 9"
        )
        assertEquals(planning(routes, "sub-alice", 5, 0, 0).status, 200)
        assertEquals(planning(routes, "sub-alice", 0, 5, 0).status, 400)
        assertEquals(store.get("m-9").get.plans.size, 1)
    }

    test("a plan whose parts overflow an Int is a 400 over the wire, and changes nothing") {
        val (routes, store, _, _) = fixture()
        assertEquals(planning(routes, "sub-alice", Int.MaxValue, Int.MaxValue, 7).status, 400)
        assertEquals(store.get("m-9").get.plans, Nil)
    }

    test("a fighter is built over the wire, and saved to matchmaker") {
        val (routes, store, _, recorder) = fixture(blue = None)

        val page = get(routes, "/matches/m-9/play", as("sub-bob"))
        assert(page.body.contains("build your fighter"))

        val built = Fighter(3, 6, 6, 6, 4)
        val answer = building(routes, "sub-bob", built)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(
          state.corners.find(_.side == "Blue").flatMap(_.fighter),
          Some(Protocol.BuildRequest(3, 6, 6, 6, 4))
        )
        assertEquals(recorder.fighters, List(202L -> built))
        assertEquals(store.get("m-9").get.cornerOf(Side.Blue).flatMap(_.fighter), Some(built))

        assertEquals(building(routes, "sub-bob", built).status, 400)
    }

    test("a build matchmaker could not save is a 502, which the player may retry") {
        val (routes, _, _, recorder) = fixture(blue = None)
        recorder.failFighterSaves = true
        assertEquals(building(routes, "sub-bob", average).status, 502)
        recorder.failFighterSaves = false
        assertEquals(building(routes, "sub-bob", average).status, 200)
    }

    test("the second plan resolves the round over the wire, and both plans are then told") {
        val (routes, _, _, _) = fixture()
        planning(routes, "sub-alice", 5, 0, 0)

        val state = read[Protocol.StateResponse](planning(routes, "sub-bob", 0, 5, 0).body)
        assertEquals(state.round, 2)
        assertEquals(state.yourPlan, None)
        val round = state.rounds.head
        assertEquals((round.red, round.blue), (Protocol.PlanRequest(5, 0, 0), Protocol.PlanRequest(0, 5, 0)))
        assertEquals((round.redPoints, round.bluePoints), (Some(10), Some(9)))
        assertEquals(round.decision, "outworked")
    }

    test("the sign-in callback page is served when a pool is configured, and not otherwise") {
        val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")
        val (withPool, _, _, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
        val page = get(withPool, "/auth/callback")
        assertEquals(page.status, 200)
        assert(page.body.contains("oauth2/token"), "the callback page redeems the authorization code")

        val (withoutPool, _, _, _) = fixture()
        assertEquals(get(withoutPool, "/auth/callback").status, 404)
    }

    test("the public board shows that a corner has planned, and not what") {
        val (routes, _, _, _) = fixture(isPublic = true)
        planning(routes, "sub-alice", 4, 0, 1)

        val watching = read[Protocol.StateResponse](get(routes, "/matches/m-9/board/state").body)
        assertEquals(watching.you, None)
        assertEquals(watching.corners.map(_.planned), List(true, false))
        assertEquals(watching.yourPlan, None)
        assert(!get(routes, "/matches/m-9/board").body.contains("\"offense\":4"))
        assertEquals(get(routes, "/matches/m-9/board").status, 200)
    }

    test("a private bout has no public board") {
        val (routes, _, _, _) = fixture(isPublic = false)
        assertEquals(get(routes, "/matches/m-9/board").status, 403)
        assertEquals(get(routes, "/matches/m-9/board/state").status, 403)
    }

    test("an unknown match and an unknown path are both 404") {
        val (routes, _, _, _) = fixture()
        assertEquals(get(routes, "/matches/nope/status").status, 404)
        assertEquals(get(routes, "/nothing/here").status, 404)
    }

    test("a lambda event decodes to the same request the local server builds, claims included") {
        val event = ujson.Obj(
          "rawPath" -> "/matches/m-9/moves",
          "requestContext" -> ujson.Obj(
            "http" -> ujson.Obj("method" -> "POST"),
            // What the JWT authorizer writes into the event once it has verified the token.
            "authorizer" -> ujson.Obj(
              "jwt" -> ujson.Obj("claims" -> ujson.Obj("sub" -> "sub-alice", "token_use" -> "id"))
            )
          ),
          "headers" -> ujson.Obj("Content-Type" -> "application/json"),
          "body" -> """{"offense":5,"defense":0,"power":0}""",
          "isBase64Encoded" -> false
        )
        val decoded = Handler.decode(ujson.write(event))
        assertEquals(decoded.method, "POST")
        assertEquals(decoded.path, "/matches/m-9/moves")
        assertEquals(decoded.body, """{"offense":5,"defense":0,"power":0}""")
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
        val (routes, _, created, _) = fixture(matchmakerKey = Some("s3cret"))
        assertEquals(created.status, 401)
        assertEquals(routes(EngineRequest("GET", "/matches/m-9/status")).status, 401)
    }

    test("a wrong key is refused exactly as a missing one is") {
        val (routes, _, _, _) = fixture(matchmakerKey = Some("s3cret"))
        val wrong = routes(EngineRequest("GET", "/matches/m-9/status", headers = Map("x-api-key" -> "s3crea")))
        assertEquals(wrong.status, 401)
        assertEquals(wrong.body, routes(EngineRequest("GET", "/matches/m-9/status")).body)
    }

    test("the right key gets in") {
        val engine = Engine(InMemoryMatchStore(), RecordingMatchmaker(), "http://engine.test")
        val routes = Routes(engine, PlayAuth.Trusted, Some("s3cret"))
        val keyed = Map("x-api-key" -> "s3cret")
        assertEquals(
          routes(EngineRequest("POST", "/games", Map.empty, write(createRequest(matchId = "m-1")), keyed)).status,
          201
        )
        assertEquals(routes(EngineRequest("GET", "/matches/m-1/status", headers = keyed)).status, 200)
    }

    test("a player route is not protected by matchmaker's key") {
        // The key guards the two routes that are matchmaker's, and only those: a player has no key
        // and must still reach the play page.
        val (routes, _, _, _) = fixture()
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
