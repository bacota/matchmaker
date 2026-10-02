package com.vivi.engine

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
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

    /** This game's routes, over a fresh in-memory store, at `http://engine.test`, offering Play Live through `live`,
      * calling `matchmaker` back and telling the time by `now`.
      */
    protected def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live],
        matchmaker: Matchmaker,
        now: () => Instant
    ): EngineRequest => EngineResponse

    private def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live]
    ): EngineRequest => EngineResponse =
        routes(playAuth, matchmakerKey, live, RecordingMatchmaker(), () => Instant.now())

    private def routes(playAuth: PlayAuth, matchmakerKey: Option[String]): EngineRequest => EngineResponse =
        routes(playAuth, matchmakerKey, None)

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

    // ---------------------------------------------------------------------------
    // Play Live
    // ---------------------------------------------------------------------------

    /** Every push, by connection, and a connection that can be made to have gone. */
    private class RecordingChannel extends LiveChannel {
        val sent = scala.collection.mutable.ListBuffer[(String, String)]()
        var gone = Set.empty[String]
        def send(connectionId: String, message: String): Boolean =
            synchronized {
                if (gone(connectionId)) false
                else {
                    sent += connectionId -> message
                    true
                }
            }
    }

    private def liveFixture(isPublic: Boolean = true, auth: PlayAuth = PlayAuth.Trusted) = {
        val channel = RecordingChannel()
        val live = Live("ws://engine.test/live", auth, InMemorySubscriptions(), channel)
        val served = routes(PlayAuth.Trusted, None, Some(live))
        assertEquals(
          served(EngineRequest("POST", "/games", Map.empty, write(createRequest("m-9", isPublic)))).status,
          201
        )
        (served, channel)
    }

    private def connecting(routes: EngineRequest => EngineResponse, id: String, query: Map[String, String]) =
        routes(EngineRequest("CONNECT", "/live", query, connectionId = Some(id)))

    test("Play Live admits a seat of the match, and a watcher of a public one") {
        val (routes, _) = liveFixture()
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9", "as" -> "sub-alice")).status, 200)
        assertEquals(connecting(routes, "c-2", Map("match" -> "m-9", "board" -> "1")).status, 200)
    }

    /* Refused on the terms the state routes refuse on, since a connection is told when the match
     * moves — which in a private match is the players' business. */
    test("Play Live refuses a connection the state routes would refuse") {
        val (routes, _) = liveFixture(isPublic = false)
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9")).status, 401)
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9", "as" -> "sub-carol")).status, 403)
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9", "board" -> "1")).status, 403)
        assertEquals(connecting(routes, "c-1", Map("match" -> "nope", "as" -> "sub-alice")).status, 404)
        assertEquals(connecting(routes, "c-1", Map("as" -> "sub-alice")).status, 400)
    }

    test("Play Live reads a player's token from the query, where a browser has to put it") {
        // A stand-in for the token verification the deployed connection does: the bearer token is
        // who the caller is.
        val byToken = new PlayAuth {
            val login: Option[LoginConfig] = None
            def callerOf(request: EngineRequest): Either[Refusal, String] =
                request.bearerToken.toRight(Refusal.Unauthenticated("no token"))
        }
        val (routes, _) = liveFixture(auth = byToken)
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9", "token" -> "sub-alice")).status, 200)
        assertEquals(connecting(routes, "c-2", Map("match" -> "m-9")).status, 401)
    }

    test("a move is pushed to every connection watching the match, and only as the fact of a change") {
        val (routes, channel) = liveFixture()
        connecting(routes, "alice", Map("match" -> "m-9", "as" -> "sub-alice"))
        connecting(routes, "bob", Map("match" -> "m-9", "as" -> "sub-bob"))
        connecting(routes, "watcher", Map("match" -> "m-9", "board" -> "1"))

        assertEquals(moving(routes, "sub-alice").status, 200)
        assertEquals(channel.sent.map(_._1).toSet, Set("alice", "bob", "watcher"))
        // Nothing a seat would hide can go down a connection, because nothing about the match does.
        channel.sent.foreach((_, message) => assertEquals(message, """{"changed":"m-9"}"""))
    }

    test("nothing is pushed for a read, or for a move that was refused") {
        val (routes, channel) = liveFixture()
        connecting(routes, "alice", Map("match" -> "m-9", "as" -> "sub-alice"))

        assertEquals(get(routes, "/matches/m-9/state", as("sub-alice")).status, 200)
        assertEquals(moving(routes, "sub-carol").status, 403)
        assertEquals(channel.sent.toList, Nil)
    }

    test("a disconnected connection, or one the channel reports gone, is pushed to no more") {
        val (routes, channel) = liveFixture()
        connecting(routes, "alice", Map("match" -> "m-9", "as" -> "sub-alice"))
        connecting(routes, "bob", Map("match" -> "m-9", "as" -> "sub-bob"))
        assertEquals(routes(EngineRequest("DISCONNECT", "/live", connectionId = Some("alice"))).status, 200)
        channel.gone = Set("bob")

        assertEquals(moving(routes, "sub-alice").status, 200)
        assertEquals(channel.sent.toList, Nil)
    }

    test("a connection event is only ever a connection's: a plain request with its method is not found") {
        val (routes, _) = liveFixture()
        assertEquals(routes(EngineRequest("CONNECT", "/live", Map("match" -> "m-9", "as" -> "sub-alice"))).status, 404)
        // And an engine without Play Live refuses the connection itself.
        val (plain, _) = fixture()
        assertEquals(connecting(plain, "c-1", Map("match" -> "m-9", "as" -> "sub-alice")).status, 404)
    }

    test("the page offers the Play Live switch only when the engine has somewhere to connect it") {
        val (live, _) = liveFixture()
        val offered = get(live, "/matches/m-9/play", as("sub-alice")).body
        assert(offered.contains("""const liveUrl = "ws://engine.test/live";"""))
        assert(offered.contains("""<input type="checkbox" id="live-toggle""""))
        assert(offered.contains("> Play Live</label>"), "the switch is called Play Live")
        assert(offered.contains("""id="live-status" role="status" aria-live="polite""""))
        assert(get(live, "/matches/m-9/board").body.contains("""const liveUrl = "ws://engine.test/live";"""))

        val (plain, _) = fixture()
        val page = get(plain, "/matches/m-9/play", as("sub-alice")).body
        assert(page.contains("const liveUrl = null;"), "no url, no switch")
        assert(page.contains("keepCurrent(refresh,"), "without Play Live the page still keeps itself current")
    }

    /* An answer overtaken by one asked for later must not be shown over it: with Play Live on, an
     * older state shown over a newer one would stay until the minute check. Every answer the page
     * shows — its refresh and its own move's — takes a ticket for that. */
    test("the page shows its answers in the order it asked for them") {
        val (routes, _) = fixture()
        val page = get(routes, "/matches/m-9/play", as("sub-alice")).body
        assert(page.contains("function latest(ticket)"))
        val sendStarts = page.indexOf("async function send(url, init, ticket) {")
        assert(sendStarts >= 0, "send must take the caller's ticket")
        val sendBody = page.substring(sendStarts, page.indexOf("\n  }\n", sendStarts))
        // Counted outside `send`, whose own refusals are checked separately below.
        val rest = page.replace(sendBody, "")
        def count(text: String) = java.util.regex.Pattern.quote(text).r.findAllMatchIn(rest).size
        // Less the function's own definition.
        val guarded = count("latest(ticket)") - 1
        val asked = count("const ticket = ask();")
        assert(asked >= 2, s"the refresh and the move should both ask for a ticket; $asked do")
        assertEquals(guarded, asked, "every ticket asked for must be checked before its answer is shown")
        // A move's refusal, and the clearing of one, are ordered on the message line; see PlayLive.
        val moves = asked - 1
        assertEquals(count("if (!overtaken(ticket)) tell(ticket, "), moves, "every move must order its refusal")
        assertEquals(count("tell(ticket, \"\");"), moves, "every move must clear a refusal only in order")
        // And the refusals `send` answers itself — a 401, a 403, an engine it cannot reach.
        assertEquals(count(", ticket);"), asked, "every request must hand its ticket to send")
        assert(!sendBody.contains("show("), "send must write its messages in order, through tell")
        assertEquals(
          "if \\(!latest\\(ticket\\)\\) return null;".r.findAllMatchIn(sendBody).size,
          2,
          "a 401 or a 403 may change the page only in order"
        )
    }

    // ---- live matches ------------------------------------------------------------------------

    private val kickOff = Instant.parse("2026-01-01T00:00:00Z")

    /** A create request for `m-9` with matchmaker's callback urls, live with 30-second turns unless `live` says
      * otherwise.
      */
    private def calledBack(live: Option[Protocol.LiveTerms] = Some(Protocol.LiveTerms(30))) =
        createRequest("m-9", isPublic = true).copy(
          moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/moves"),
          resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/results"),
          live = live
        )

    /** A live match with 30-second turns, on a clock the test moves by the seconds it is given, calling back to a
      * recorder.
      */
    private def liveMatch(live: Option[Live] = None) = {
        val clock = AtomicReference(kickOff)
        val matchmaker = RecordingMatchmaker()
        val served = routes(PlayAuth.Trusted, None, live, matchmaker, () => clock.get)
        assertEquals(served(EngineRequest("POST", "/games", Map.empty, write(calledBack()))).status, 201)
        (served, matchmaker, (seconds: Long) => clock.updateAndGet(_.plusSeconds(seconds)): Unit)
    }

    private def statusOf(routes: EngineRequest => EngineResponse) =
        read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)

    private def stateOf(routes: EngineRequest => EngineResponse, player: String) =
        ujson.read(get(routes, "/matches/m-9/state", as(player)).body)

    test("a live match reports none of its moves to matchmaker") {
        val (routes, matchmaker, _) = liveMatch()
        assertEquals(moving(routes, "sub-alice").status, 200)
        assertEquals(matchmaker.moves, Nil)
    }

    test("a match that is not live has no clock, and reports its moves as ever") {
        val matchmaker = RecordingMatchmaker()
        val served = routes(PlayAuth.Trusted, None, None, matchmaker, () => kickOff)
        assertEquals(served(EngineRequest("POST", "/games", Map.empty, write(calledBack(live = None)))).status, 201)
        assertEquals(moving(served, "sub-alice").status, 200)
        assertEquals(matchmaker.moves.size, 1)
        assert(!stateOf(served, "sub-alice").obj.contains("clock"), "only a live match has a clock")
    }

    /** Alice's own clock, as her state shows it. */
    private def aliceClock(state: ujson.Value) = state("clock")("seats").arr.find(_("participantId").num == 1)

    test("a live match's state carries a player's clock, counting down from when they opened the board") {
        val (routes, _, advance) = liveMatch()
        advance(100)
        // The first read is the player opening the board, and starts their clock.
        stateOf(routes, "sub-alice")
        advance(12)
        val clock = stateOf(routes, "sub-alice")("clock")
        assertEquals(clock("limitSeconds").num, 30.0)
        assertEquals(clock("kind").str, "PER_TURN")
        assertEquals(aliceClock(stateOf(routes, "sub-alice")).map(_("remainingMillis").num), Some(18000.0))
        assertEquals(clock("timedOut").arr.toList, Nil)
    }

    test("a player's clock does not start until they open the board, whoever else has") {
        val (routes, matchmaker, advance) = liveMatch()
        // Nobody has opened it: an hour goes by, and nobody runs out.
        advance(3600)
        assertEquals(statusOf(routes).completed, false)

        stateOf(routes, "sub-alice")
        advance(29)
        val waiting = stateOf(routes, "sub-alice")
        assertEquals(waiting("completed").bool, false)
        // Bob has not opened it, so if he is being waited on too, his clock has not started.
        val bob = waiting("clock")("seats").arr.find(_("participantId").num == 2)
        assert(bob.forall(_.obj.get("remainingMillis").forall(_.isNull)), s"bob's clock has started: $bob")
        assertEquals(matchmaker.results, Nil)

        advance(1)
        val ended = stateOf(routes, "sub-alice")
        assertEquals(ended("completed").bool, true)
        assertEquals(ended("clock")("timedOut").arr.map(_.num.toLong).toList, List(1L))
        assertEquals(statusOf(routes).completed, true)

        // Reported by the read that found it, and by nothing after; bob, never on the clock, wins.
        assertEquals(matchmaker.results.size, 1)
        val reported = matchmaker.results.head._2.results
        assert(reported.forall(_.forfeit), "every seat of a match a clock ended is a forfeit")
        assertEquals(reported.filter(_.isWinner).map(_.participantId), List(2L))
        assertEquals(matchmaker.moves, Nil)
    }

    test("a move made after its turn ran out is refused, and the forfeit recorded in its place") {
        val (routes, matchmaker, advance) = liveMatch()
        stateOf(routes, "sub-alice")
        advance(31)
        assertEquals(moving(routes, "sub-alice").status, 409)
        assertEquals(statusOf(routes).completed, true)
        assertEquals(matchmaker.results.size, 1)
        assert(matchmaker.results.head._2.results.forall(_.forfeit))
        assertEquals(statusOf(routes).turns, Nil, "the late move was not made")
    }

    test("a player opening the board, and a forfeit found by a read, are each pushed to the watchers once") {
        val channel = RecordingChannel()
        val (routes, _, advance) =
            liveMatch(Some(Live("ws://engine.test/live", PlayAuth.Trusted, InMemorySubscriptions(), channel)))
        assertEquals(connecting(routes, "c-1", Map("match" -> "m-9", "as" -> "sub-bob")).status, 200)
        val changed = "c-1" -> """{"changed":"m-9"}"""

        // Alice's clock starting is news to bob, whose page shows it.
        stateOf(routes, "sub-alice")
        assertEquals(channel.sent.toList, List(changed))
        stateOf(routes, "sub-alice")
        assertEquals(channel.sent.size, 1, "only the first opening starts a clock")

        advance(30)
        statusOf(routes)
        assertEquals(channel.sent.toList, List(changed, changed))
        // A read of a match already ended changes nothing, and tells nobody anything.
        stateOf(routes, "sub-alice")
        assertEquals(channel.sent.size, 2)
    }

    test("a live match needs a time limit of at least a second, of a kind the engine knows") {
        val served = routes(PlayAuth.Trusted, None)
        def creating(terms: Protocol.LiveTerms) =
            served(EngineRequest("POST", "/games", Map.empty, write(calledBack(live = Some(terms))))).status
        assertEquals(creating(Protocol.LiveTerms(0)), 400)
        assertEquals(creating(Protocol.LiveTerms(30, "HOURGLASS")), 400)
    }

    // ---- live matches on a chess clock -------------------------------------------------------

    /** A live match on a 30-second chess clock: each player's budget for the whole match. */
    private def chessMatch() = {
        val clock = AtomicReference(kickOff)
        val matchmaker = RecordingMatchmaker()
        val served = routes(PlayAuth.Trusted, None, None, matchmaker, () => clock.get)
        val create = calledBack(live = Some(Protocol.LiveTerms(30, "TOTAL")))
        assertEquals(served(EngineRequest("POST", "/games", Map.empty, write(create))).status, 201)
        (served, matchmaker, (seconds: Long) => clock.updateAndGet(_.plusSeconds(seconds)): Unit)
    }

    test("under a chess clock a move spends the mover's budget, which is shown while it is not running") {
        val (routes, _, advance) = chessMatch()
        stateOf(routes, "sub-alice")
        advance(10)
        assertEquals(moving(routes, "sub-alice").status, 200)

        val clock = stateOf(routes, "sub-alice")("clock")
        assertEquals(clock("kind").str, "TOTAL")
        // Every seat is shown under a chess clock; alice's has 20 of her 30 seconds left, and is not running,
        // since after her first move the match is waiting on bob.
        val alice = aliceClock(stateOf(routes, "sub-alice")).get
        assertEquals(alice("remainingMillis").num, 20000.0)
        assertEquals(alice("running").bool, false)
        assertEquals(clock("seats").arr.map(_("participantId").num.toLong).toSet, Set(1L, 2L))
    }

    test("under a chess clock a player who runs out of budget forfeits") {
        val (routes, matchmaker, advance) = chessMatch()
        stateOf(routes, "sub-alice")
        advance(30)
        val ended = stateOf(routes, "sub-alice")
        assertEquals(ended("completed").bool, true)
        assertEquals(ended("clock")("timedOut").arr.map(_.num.toLong).toList, List(1L))
        assert(matchmaker.results.head._2.results.forall(_.forfeit))
    }

    test("the play page counts a live match's turn down, without announcing every second") {
        val (routes, _, _) = liveMatch()
        val page = get(routes, "/matches/m-9/play", as("sub-alice")).body
        assert(page.contains("""<p id="turn-clock" role="timer" aria-live="off" hidden>"""))
        assert(page.contains("function turnClock(refresh)"))
        assert(page.contains("const showClock = turnClock(refresh);"))
        assert(page.contains("showClock(state && state.clock, "), "render must hand the clock to the timer")
    }
}
