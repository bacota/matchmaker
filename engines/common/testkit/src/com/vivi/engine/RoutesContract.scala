package com.vivi.engine

import munit.FunSuite
import upickle.default.{read, write}
import Protocol.given

/** The routes every engine serves, checked the same way for each: what [[EngineRoutes]] promises, whatever the game.
  *
  * An engine's tests extend this with its own game — a create request, a first move, and the two things its play page
  * says in its own words — and keep in their own `RoutesSpec` only what is their game's: what a move does, what the
  * state shows, and any route only that game has.
  *
  * Every match here is `m-9`, between `sub-alice` (participant 1) and `sub-bob` (participant 2), driven with the
  * trusted play auth of the local zero-setup mode, where a caller names themselves with `?as=`. What the play routes do
  * with an identity — find the seat, refuse if there is none — is the same whichever mode established it, and the modes
  * themselves are `PlayAuthSpec`'s.
  */
abstract class RoutesContract extends FunSuite {

    /** This game's routes, over a fresh in-memory store, at `http://engine.test`. */
    protected def routes(playAuth: PlayAuth, matchmakerKey: Option[String]): EngineRequest => EngineResponse

    /** A create request for match `matchId` seating `sub-alice` as participant 1 and `sub-bob` as participant 2, with
      * whatever else a match of this game needs to be played from its first move.
      */
    protected def createRequest(matchId: String, isPublic: Boolean): Protocol.CreateGameRequest

    /** A body for `POST /matches/{id}/moves` that is a legal first move for `sub-alice`. */
    protected def firstMove: String

    /** The seat `sub-alice` is given, as this game's play state names it. */
    protected def aliceSeat: String

    /** What a stranger's play page asks them to do, in this game's words. */
    protected def signInPrompt: String

    private val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback", "us-east-1")

    private def fixture(
        isPublic: Boolean = true,
        playAuth: PlayAuth = PlayAuth.Trusted,
        matchmakerKey: Option[String] = None
    ) = {
        val served = routes(playAuth, matchmakerKey)
        val created = served(EngineRequest("POST", "/games", Map.empty, write(createRequest("m-9", isPublic))))
        (served, created)
    }

    private def get(routes: EngineRequest => EngineResponse, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest("GET", path, query))

    private def as(player: String) = Map("as" -> player)

    private def moving(routes: EngineRequest => EngineResponse, player: String) =
        routes(EngineRequest("POST", "/matches/m-9/moves", as(player), firstMove))

    test("POST /games creates a match and answers 201 with the urls") {
        val (routes, created) = fixture()
        assertEquals(created.status, 201)
        val response = read[Protocol.CreateGameResponse](created.body)
        assertEquals(response.statusUrl, "http://engine.test/matches/m-9/status")
        assertEquals(response.playUrl, "http://engine.test/matches/m-9/play")
        assertEquals(response.publicUrl, Some("http://engine.test/matches/m-9/board"))
        assertEquals(get(routes, "/matches/m-9/status").status, 200)
    }

    test("POST /games with a body that is not a create request is a 400, not a 500") {
        val (routes, _) = fixture()
        val answer = routes(EngineRequest("POST", "/games", Map.empty, """{"nonsense":true}"""))
        assertEquals(answer.status, 400)
        assert(ujson.read(answer.body).obj.contains("error"))
    }

    test("GET /matches/:id/status passes `since` through, and refuses one it cannot read") {
        val (routes, _) = fixture()
        assertEquals(moving(routes, "sub-alice").status, 200)

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

    test("the play page renders the state for the player whose seat it is") {
        val (routes, _) = fixture()

        val page = get(routes, "/matches/m-9/play", as("sub-alice"))
        assertEquals(page.status, 200)
        assertEquals(page.contentType, "text/html; charset=utf-8")
        assert(page.body.contains("<!doctype html>"))
        // What changes while the page is idle is announced: the status as it moves on, and a refusal.
        assert(page.body.contains("""<p id="status" role="status" aria-live="polite">"""))
        assert(page.body.contains("""<div id="error" role="alert">"""))
        assert(page.body.contains(s"\"you\":\"$aliceSeat\""), "the seat's own state should be inlined into the page")
    }

    /* The page is served to anyone, signed in or not: it is a shell that offers a sign-in and then
     * fetches the state, so a player following the url from matchmaker gets somewhere to sign in
     * rather than a bare 401. It must not carry the match with it, which is what this checks. */
    test("the play page for a stranger carries no state and offers a sign-in") {
        val (routes, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))

        val page = get(routes, "/matches/m-9/play")
        assertEquals(page.status, 200)
        assert(page.body.contains("let state = null"), "a stranger's page must not carry the match")
        assert(page.body.contains(signInPrompt))
        assert(page.body.contains("client-1"), "the page needs the app client to start a sign-in")
    }

    test("the state and move routes refuse a caller with no seat") {
        val (routes, _) = fixture()
        assertEquals(get(routes, "/matches/m-9/state", as("sub-carol")).status, 403)
        assertEquals(moving(routes, "sub-carol").status, 403)
    }

    /* The page tells these apart: a 401 drops the session and offers a sign-in, which can help; a
     * 403 keeps it and says there is no seat here, which a sign-in cannot change. */
    test("a caller who cannot be identified is refused with 401, not 403") {
        val (routes, _) = fixture()
        assertEquals(get(routes, "/matches/m-9/state").status, 401)
        assertEquals(routes(EngineRequest("POST", "/matches/m-9/moves", body = firstMove)).status, 401)

        // Deployed: no claims means no authorizer vouched for anyone; a stranger's claims are a 403.
        val (deployed, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
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

    test("the sign-in callback page is served when a pool is configured, and not otherwise") {
        val (withPool, _) = fixture(playAuth = PlayAuth.GatewayClaims(Some(login)))
        val page = get(withPool, "/auth/callback")
        assertEquals(page.status, 200)
        assert(page.body.contains("oauth2/token"), "the callback page redeems the authorization code")
        // And says so when it cannot reach the token endpoint, rather than "signing in…" for ever.
        assert(page.body.contains("could not be reached"), "an unreachable token endpoint must end in a failure")
        // Its status changes from "signing in…" to a failure with nothing pressed, so it is announced.
        assert(page.body.contains("""<p id="error" role="status" aria-live="polite">"""))
        // Laid out for the phone it is most likely opened on, not shrunk from a desktop width.
        assert(page.body.contains("""<meta name="viewport" content="width=device-width, initial-scale=1">"""))

        val (withoutPool, _) = fixture()
        assertEquals(get(withoutPool, "/auth/callback").status, 404)
    }

    test("the public board is readable by anyone, and shows no seat as its own") {
        val (routes, _) = fixture(isPublic = true)
        assertEquals(get(routes, "/matches/m-9/board").status, 200)
        val watching = ujson.read(get(routes, "/matches/m-9/board/state").body)
        assert(watching.obj.get("you").forall(_.isNull), "nobody's seat is the public board's")
    }

    test("a private match has no public board") {
        val (routes, _) = fixture(isPublic = false)
        assertEquals(get(routes, "/matches/m-9/board").status, 403)
        assertEquals(get(routes, "/matches/m-9/board/state").status, 403)
    }

    test("an unknown match and an unknown path are both 404") {
        val (routes, _) = fixture()
        assertEquals(get(routes, "/matches/nope/status").status, 404)
        assertEquals(get(routes, "/nothing/here").status, 404)
    }

    // ---------------------------------------------------------------------------
    // Matchmaker's own routes
    // ---------------------------------------------------------------------------

    test("with a key configured, matchmaker's routes need it") {
        // The fixture's own create call is made with no key, so it is the refusal being asserted.
        val (routes, created) = fixture(matchmakerKey = Some("s3cret"))
        assertEquals(created.status, 401)
        assertEquals(routes(EngineRequest("GET", "/matches/m-9/status")).status, 401)
    }

    test("a wrong key is refused exactly as a missing one is") {
        val (routes, _) = fixture(matchmakerKey = Some("s3cret"))
        val wrong = routes(EngineRequest("GET", "/matches/m-9/status", headers = Map("x-api-key" -> "s3crea")))
        assertEquals(wrong.status, 401)
        assertEquals(wrong.body, routes(EngineRequest("GET", "/matches/m-9/status")).body)
    }

    test("the right key gets in") {
        val keyed = Map("x-api-key" -> "s3cret")
        val served = routes(PlayAuth.Trusted, Some("s3cret"))
        assertEquals(
          served(
            EngineRequest("POST", "/games", Map.empty, write(createRequest("m-1", isPublic = true)), keyed)
          ).status,
          201
        )
        assertEquals(served(EngineRequest("GET", "/matches/m-1/status", headers = keyed)).status, 200)
    }

    test("a player route is not protected by matchmaker's key") {
        // The key guards the two routes that are matchmaker's, and only those: a player has no key
        // and must still reach the play page, their state and their moves. So the match is created
        // with the key, as matchmaker would, and then played without it.
        val served = routes(PlayAuth.Trusted, Some("s3cret"))
        val keyed = Map("x-api-key" -> "s3cret")
        val created =
            served(EngineRequest("POST", "/games", Map.empty, write(createRequest("m-9", isPublic = true)), keyed))
        assertEquals(created.status, 201)

        assertEquals(get(served, "/matches/m-9/play", as("sub-alice")).status, 200)
        assertEquals(get(served, "/matches/m-9/state", as("sub-alice")).status, 200)
        assertEquals(moving(served, "sub-alice").status, 200)
        assertEquals(get(served, "/health").status, 200)
    }
}
