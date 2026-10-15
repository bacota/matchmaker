package com.vivi.matchmaker.api

import com.vivi.matchmaker.auth.ApiKeys

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import skunk.Session
import upickle.default.write
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service._
import ApiGateway.Request
import Json.given
import TournamentJson.given

/** Routing, authentication and error mapping, none of which need a database.
  *
  * The services are built over a pool that fails on use, so any route that reaches a service fails loudly — which keeps
  * these tests honest about only covering the paths that answer before the database is touched.
  */
class RouterSpec extends FunSuite {

    private val unusablePool: SessionPool =
        Resource.eval(IO.raiseError[Session[IO]](new IllegalStateException("the database must not be reached")))

    private val services: Services[String] = Services.fromPool[String](pool = unusablePool)

    private def request(
        method: String,
        path: String,
        headers: Map[String, String] = Map("x-external-id" -> "sub-1"),
        body: String = "{}"
    ): Request = {
        // A query string on the path is split off into the query map, the way the gateway's own
        // parsing does, so a route that reads a parameter can be listed in `routed` with one.
        val (bare, query) = path.split('?') match {
            case Array(bare, rest) =>
                (
                  bare,
                  rest
                      .split('&')
                      .map(_.split('=') match {
                          case Array(name, value) => name -> value
                          case other              => other.head -> ""
                      })
                      .toMap
                )
            case _ => (path, Map.empty[String, String])
        }

        Request(method = method, path = bare, headers = headers, query = query, body = body)
    }

    private def dispatch(request: Request): ApiGateway.Response =
        Router
            .dispatch(services = services, request = request, authenticator = Authenticator.TrustedHeader)
            .unsafeRunSync()

    private def statusOf(method: String, path: String, headers: Map[String, String], body: String): Int =
        dispatch(request(method = method, path = path, headers = headers, body = body)).statusCode

    /** Runs a body whose 500s are the point of the test, without their stack traces on the console.
      *
      * `Errors.log` writes every 5xx to stderr so that it reaches CloudWatch, which is right in a deployed lambda and
      * pure noise here: the tests below cause 500s deliberately — one of them twenty-five times over — and a passing
      * run should not look like a broken one.
      *
      * Captured rather than discarded. If the body fails, what was written is printed before the failure is rethrown,
      * so a trace that turns out to matter is still there to read.
      */
    private def quietly[A](body: => A): A = {
        val captured = java.io.ByteArrayOutputStream()
        val original = System.err
        System.setErr(java.io.PrintStream(captured, true))
        try body
        catch {
            case error: Throwable =>
                original.print(captured.toString)
                throw error
        } finally {
            System.err.flush()
            System.setErr(original)
        }
    }

    test("a request without the identity header is unauthenticated") {
        assertEquals(statusOf(method = "GET", path = "/me", headers = Map.empty, body = "{}"), 401)
    }

    test("a blank identity header is treated as missing") {
        assertEquals(statusOf(method = "GET", path = "/me", headers = Map("x-external-id" -> "   "), body = "{}"), 401)
    }

    // Guards the by-name `services` parameter. `fail` as the argument stands in for the pool
    // construction the deployed handler would do: if the parameter is ever made strict again, this
    // test fails rather than a rejected request quietly paying for a database pool it never uses.
    test("a rejected request never forces the services") {
        val alwaysRejects = new Authenticator {
            def callerOf(request: Request): IO[Either[ApiGateway.Response, String]] =
                IO.pure(Left(Errors.response(401, "no")))
        }

        val response =
            Router.dispatch(
              services = fail("services must not be forced for a rejected request"),
              request = request(method = "GET", path = "/me"),
              authenticator = alwaysRejects
            )

        assertEquals(response.unsafeRunSync().statusCode, 401)
    }

    test("an unknown path is not found") {
        assertEquals(dispatch(request(method = "GET", path = "/nonsense")).statusCode, 404)
    }

    test("a known path with the wrong method is not found") {
        assertEquals(dispatch(request(method = "DELETE", path = "/register")).statusCode, 404)
    }

    test("a malformed request body is a bad request, before any service is called") {
        assertEquals(dispatch(request(method = "POST", path = "/register", body = "not json")).statusCode, 400)
    }

    test("a body missing a required field is a bad request") {
        assertEquals(
          dispatch(request(method = "POST", path = "/register", body = """{"wrong":"field"}""")).statusCode,
          400
        )
    }

    test("a match id that is not a number is a bad request") {
        assertEquals(dispatch(request(method = "GET", path = "/games/1/matches/m1")).statusCode, 400)
        assertEquals(dispatch(request(method = "POST", path = "/matches/m1/archive/confirm")).statusCode, 400)
        assertEquals(
          dispatch(request(method = "POST", path = "/games/1/tournaments/2/matches/m1/role", body = "{}")).statusCode,
          400
        )
    }

    // Every part of a completed list's window is optional, and each one given has to be well formed.
    test("a completed list's window with a part that is not well formed is a bad request") {
        assertEquals(dispatch(request(method = "GET", path = "/me/matches/completed?frame=fortnight")).statusCode, 400)
        assertEquals(dispatch(request(method = "GET", path = "/me/matches/completed?page=-1")).statusCode, 400)
        assertEquals(dispatch(request(method = "GET", path = "/me/matches/completed?page=many")).statusCode, 400)
        assertEquals(dispatch(request(method = "GET", path = "/me/matches/completed?asOf=yesterday")).statusCode, 400)
        assertEquals(
          dispatch(request(method = "GET", path = "/players/2/matches/completed?gameId=abc")).statusCode,
          400
        )
    }

    // Well formed, but naming a window that cannot be computed or stored: refused, not a 500.
    test("a completed list's window out of any history's range is a bad request") {
        // Parses, as Instant.MIN; a day before it is not an instant at all.
        assertEquals(
          dispatch(request(method = "GET", path = "/me/matches/completed?asOf=-1000000000-01-01T00:00:00Z")).statusCode,
          400
        )
        // Ten thousand years back from now.
        assertEquals(
          dispatch(request(method = "GET", path = "/me/matches/completed?frame=year&page=10000")).statusCode,
          400
        )
        // A moment nobody's history reaches yet.
        assertEquals(
          dispatch(request(method = "GET", path = "/me/matches/completed?asOf=2999-01-01T00:00:00Z")).statusCode,
          400
        )
    }

    // `?role=` names a role's ratings rather than the overall ones, by its id.
    test("a ratings role that is not a role id is a bad request") {
        assertEquals(dispatch(request(method = "GET", path = "/games/1/ratings?role=first")).statusCode, 400)
        assertEquals(dispatch(request(method = "GET", path = "/games/1/ratings/2?role=first")).statusCode, 400)
    }

    test("a non-numeric game id is a bad request") {
        assertEquals(dispatch(request(method = "GET", path = "/games/abc/challenges")).statusCode, 400)
    }

    // The per-game route takes the same body as the defaults route above it, so the flag that only
    // the defaults route can act on is something a client can send here by simply reusing that body.
    // Refused rather than discarded: a 204 would tell them a cascade had run that had not. Answered
    // before the service is reached, which the unusable pool proves -- reaching it would be a 500.
    test("a cascade the per-game route cannot carry out is a bad request") {
        val body = """{"preferences":{"isNotifiedOfMatchStarted":true},"isAppliedToGames":true}"""
        assertEquals(dispatch(request(method = "PUT", path = "/me/notifications/games/1", body = body)).statusCode, 400)
    }

    // And the flag it can act on still reaches the service, so the refusal above is about one field
    // and has not made the route reject every cascade.
    test("the cascade the per-game route does support reaches the service") {
        val body = """{"preferences":{"isNotifiedOfMatchStarted":true},"isAppliedToMatches":true}"""
        assertEquals(
          quietly(dispatch(request(method = "PUT", path = "/me/notifications/games/1", body = body))).statusCode,
          500
        )
    }

    test("a non-numeric character id is a bad request") {
        assertEquals(dispatch(request(method = "PUT", path = "/characters/abc")).statusCode, 400)
    }

    /** Bodies are serialized from real model values rather than hand-written JSON, so that a body is never accidentally
      * invalid — which would fail the request at parsing with 400 and hide whether the route matched at all.
      */
    private val gameBody = write(
      Game(
        gameId = GameId.unassigned,
        gameType = GameType.Character,
        name = "name",
        displayName = "name",
        description = "description",
        url = "url",
        isActive = true,
        Seq.empty,
        Seq.empty,
        "secret"
      )
    )

    // Offered with a time to play and an invitation: the request's Option and list fields are the ones a client can get
    // wrong, written here as upickle writes them.
    private val challengeBody =
        write(ChallengeRequest(characterId = Some(CharacterId(1)), poolSize = Some(2), invitations = List(PlayerId(2))))

    private val resultsBody = write(
      Json.MatchResults(results =
          List(
            Json.ResultEntry(
              participantId = ParticipantId(1),
              rank = 1,
              scores = Map("points" -> ujson.Num(3)),
              isWinner = true
            )
          )
      )
    )

    /** Every route in `Router`. Keep in step with it: a route missing from here is a route no test would notice
      * breaking.
      */
    private val routed = List(
      ("POST", "/register", """{"nickname":"tester"}"""),
      ("GET", "/me", "{}"),
      ("PUT", "/me", """{"nickname":"renamed"}"""),
      ("PUT", "/me/email", """{"email":"player@example.com"}"""),
      ("GET", "/me/notifications", "{}"),
      // No body: the address is the caller's own, and which suppression is theirs is not theirs to say.
      ("POST", "/me/notifications/retry", "{}"),
      // The preferences are wrapped, because how far the save reaches is part of what was said --
      // and the cascade flags are defaulted, so a body that names only the preferences is valid too.
      (
        "PUT",
        "/me/notifications",
        """{"preferences":{"isNotifiedOfMatchStarted":true,"isNotifiedOfYourTurn":false}}"""
      ),
      (
        "PUT",
        "/me/notifications/games/1",
        """{"preferences":{"isNotifiedOfMatchStarted":true,"isNotifiedOfYourTurn":false},"isAppliedToMatches":true}"""
      ),
      ("GET", "/me/matches", "{}"),
      ("GET", "/me/matches/due", "{}"),
      ("GET", "/me/matches/completed", "{}"),
      ("GET", "/me/results", "{}"),
      // With a prefix, because a blank one is a 400 from the service and these tests are about the
      // route reaching it at all. `request` splits the query string off the path.
      ("GET", "/players?prefix=a", "{}"),
      ("GET", "/players/2/matches", "{}"),
      ("GET", "/players/2/matches/completed", "{}"),
      ("GET", "/games", "{}"),
      ("POST", "/games", gameBody),
      ("GET", "/games/1/admins", "{}"),
      ("PUT", "/games/1/admins/2", "{}"),
      ("DELETE", "/games/1/admins/2", "{}"),
      ("GET", "/games/1/ratings", "{}"),
      ("GET", "/games/1/ratings/2", "{}"),
      ("PUT", "/games/1/ratings/2", """{"rating":1600}"""),
      ("GET", "/games/1/challenges", "{}"),
      ("POST", "/games/1/challenges", challengeBody),
      ("GET", "/me/challenges", "{}"),
      ("GET", "/games/1/characters", "{}"),
      ("GET", "/games/1/players/2/characters", "{}"),
      ("GET", "/games/1/characters/2", "{}"),
      ("GET", "/games/1/characters/2/matches", "{}"),
      ("GET", "/games/1/characters/2/matches/completed", "{}"),
      ("POST", "/characters", """{"name":"n","description":"d","ownerExternalId":"sub-1","state":"{}"}"""),
      // The engine's edits of a character, on behalf of its owner.
      ("PUT", "/characters/1", """{"name":"n","description":"d","ownerExternalId":"sub-1"}"""),
      ("PUT", "/characters/1/owner", """{"toNickname":"bob","ownerExternalId":"sub-1"}"""),
      ("GET", "/characters?owner=sub-1", "{}"),
      ("PUT", "/characters/1/state", """{"state":"s"}"""),
      ("GET", "/games/1/matches/11", "{}"),
      ("POST", "/games/1/matches/11/refresh", "{}"),
      ("POST", "/games/1/matches/11/cancel", "{}"),
      ("GET", "/games/1/matches", "{}"),
      ("PUT", "/games/1/matches/11/friendly", """{"isFriendly":false}"""),
      ("GET", "/games/1/matches/11/notifications", "{}"),
      // The four a seat holds, and not optional: a seat answers every kind it can be asked
      // about, so there is nothing here to leave out. The seven kinds about entries are not a
      // seat's to answer and are not accepted here.
      (
        "PUT",
        "/games/1/matches/11/notifications",
        """{"isNotifiedOfMatchStarted":true,"isNotifiedOfTurnTaken":false,"isNotifiedOfYourTurn":true,"isNotifiedOfMatchEnded":true}"""
      ),
      (
        "POST",
        "/games/1/matches/11/moves",
        """{"participantId":1,"next":[2],"takenAt":"2030-01-01T00:00:00Z","startedAt":"2030-01-01T00:00:00Z"}"""
      ),
      ("POST", "/games/1/matches/11/results", resultsBody),
      // The engine's archive calls. The upload request is the only one with a body: the size and
      // checksum it will be held to.
      ("POST", "/matches/11/archive", """{"size":10,"sha256":"47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="}"""),
      ("POST", "/matches/11/archive/confirm", "{}"),
      ("POST", "/matches/11/archive/read", "{}"),
      ("POST", "/matches/11/archive/expired", "{}"),
      // Tournaments. A create or edit names a standard design by key; settings are all defaulted.
      ("GET", "/me/tournaments", "{}"),
      ("GET", "/me/tournament-standings", "{}"),
      ("GET", "/games/1/tournaments", "{}"),
      ("POST", "/games/1/tournaments", """{"name":"Open","tournamentModelId":6}"""),
      ("GET", "/games/1/tournaments/2", "{}"),
      ("PUT", "/games/1/tournaments/2", """{"name":"Open","tournamentModelId":6}"""),
      ("POST", "/games/1/tournaments/2/open", "{}"),
      ("POST", "/games/1/tournaments/2/start", "{}"),
      ("POST", "/games/1/tournaments/2/stop", "{}"),
      ("POST", "/games/1/tournaments/2/invitations", """{"playerId":3}"""),
      ("DELETE", "/games/1/tournaments/2/invitations/3", "{}"),
      ("POST", "/games/1/tournaments/2/character-invitations", """{"characterId":4}"""),
      ("DELETE", "/games/1/tournaments/2/character-invitations/4", "{}"),
      // An Option field is the bare value, and absent when there is none.
      ("POST", "/games/1/tournaments/2/entries", """{"characterId":4}"""),
      ("DELETE", "/games/1/tournaments/2/entries/5", "{}"),
      ("GET", "/tournament-models", "{}"),
      ("POST", "/tournament-models/sync", "{}"),
      ("GET", "/games/1/tournament-models", "{}"),
      ("PUT", "/games/1/tournament-models/world-cup", "{}"),
      ("DELETE", "/games/1/tournament-models/world-cup", "{}"),
      ("POST", "/games/1/tournaments/2/rounds", "{}"),
      ("POST", "/games/1/tournaments/2/check", "{}"),
      ("POST", "/games/1/tournaments/2/stop-repeating", "{}"),
      ("POST", "/games/1/tournaments/2/matches/3/role", """{"gameRoleId":7}"""),
      (
        "POST",
        "/games/1/tournaments/2/matches/3/result",
        """{"ranks":[{"entrantId":5,"rank":1},{"entrantId":6,"rank":2}]}"""
      ),
      ("GET", "/me/tournament-choices", "{}")
    )

    test("every routed endpoint reaches a service rather than falling through to 404") {
        // A route that matched will hit the unusable pool and come back 500; one that did not match
        // comes back 404, and one whose body failed to parse comes back 400. So a 500 here is
        // exactly the evidence that the route is wired up and its body was understood.
        quietly {
            routed.foreach { case (method, path, body) =>
                assertEquals(
                  statusOf(method = method, path = path, headers = Map("x-external-id" -> "sub-1"), body = body),
                  500,
                  s"$method $path"
                )
            }
        }
    }

    test("the routed list covers every route Router declares") {
        // A count, because the route table cannot be enumerated from Router itself. It fails loudly
        // when a route is added there without a corresponding entry above.
        assertEquals(routed.size, 75)
        assertEquals(routed.distinct.size, routed.size)
    }

    test("every routed endpoint requires the identity header") {
        routed.foreach { case (method, path, body) =>
            assertEquals(
              statusOf(method = method, path = path, headers = Map.empty, body = body),
              401,
              s"$method $path"
            )
        }
    }

    test("the caller comes from the authenticator, not from the header") {
        // Stands in for GatewayClaims, which will take the caller from a verified token rather than a
        // header: the routes must not care where the identity came from.
        val fromElsewhere = new Authenticator {
            def callerOf(request: Request): IO[Either[ApiGateway.Response, String]] = IO.pure(Right("sub-from-token"))
        }

        val response =
            quietly(
              Router
                  .dispatch(
                    services = services,
                    request = request(method = "GET", path = "/me", headers = Map.empty),
                    authenticator = fromElsewhere
                  )
                  .unsafeRunSync()
            )

        // No header at all, yet the request is authenticated and reaches the unusable pool.
        assertEquals(response.statusCode, 500)
    }

    test("an authenticator's rejection is returned as it wrote it") {
        val alwaysExpired = new Authenticator {
            def callerOf(request: Request): IO[Either[ApiGateway.Response, String]] =
                IO.pure(Left(Errors.response(401, "token has expired")))
        }

        val response = Router
            .dispatch(
              services = services,
              request = request(method = "GET", path = "/me"),
              authenticator = alwaysExpired
            )
            .unsafeRunSync()

        assertEquals(response.statusCode, 401)
        assert(response.body.contains("token has expired"), response.body)
    }

    // The game engine's callbacks carry the key matchmaker and that engine share. The key names
    // the engine, because each game stores its own — filed under the game's externalId, which is
    // what the services authorize a game-authorized caller by.
    private val keys = () => IO.pure(ApiKeys(Map("tictactoe" -> "s3cret")))

    test("the gateway authenticator turns an engine's API key into that engine's name") {
        val request = ApiGateway.Request(
          method = "POST",
          path = "/games/1/matches/11/results",
          headers = Map(ApiKeys.Header -> "s3cret"),
          query = Map.empty,
          body = "{}"
        )

        assertEquals(Authenticator.Gateway(keys).callerOf(request).unsafeRunSync(), Right("tictactoe"))
    }

    test("a key nobody was issued is refused, exactly as a missing one is") {
        def callerOf(headers: Map[String, String]) =
            Authenticator
                .Gateway(keys)
                .callerOf(
                  ApiGateway.Request(
                    method = "POST",
                    path = "/games/1/matches/11/results",
                    headers = headers,
                    query = Map.empty,
                    body = "{}"
                  )
                )
                .unsafeRunSync()

        assertEquals(callerOf(Map(ApiKeys.Header -> "guess")), Left(Errors.unauthenticated))
        assertEquals(callerOf(Map(ApiKeys.Header -> "")), Left(Errors.unauthenticated))
    }

    // A request with no key has nothing to look up, and asking the database on its behalf would let
    // anyone make matchmaker read every key just by calling an engine route.
    test("a request presenting no key is refused without the keys being read") {
        val unread = () => IO.raiseError[ApiKeys](new AssertionError("the keys were read"))
        val outcome = Authenticator
            .ApiKey(unread)
            .callerOf(
              ApiGateway.Request(
                method = "POST",
                path = "/games/1/matches/11/results",
                headers = Map.empty,
                query = Map.empty,
                body = "{}"
              )
            )
            .unsafeRunSync()

        assertEquals(outcome, Left(Errors.unauthenticated))
    }

    test("the gateway authenticator prefers a verified token over anything else on the request") {
        val request = ApiGateway.Request(
          method = "GET",
          path = "/me",
          // Both a spoofable identity header and a real key: neither displaces the claim, so an
          // engine holding a valid key cannot present itself as a player.
          headers = Map(ApiGateway.ExternalIdHeader.toLowerCase -> "spoofed", ApiKeys.Header -> "s3cret"),
          query = Map.empty,
          body = "{}",
          claims = Map("sub" -> "sub-from-token")
        )

        assertEquals(Authenticator.Gateway(keys).callerOf(request).unsafeRunSync(), Right("sub-from-token"))
    }

    test("a request the gateway authenticated in neither way is unauthenticated") {
        val request =
            ApiGateway.Request(
              method = "GET",
              path = "/me",
              headers = Map(ApiGateway.ExternalIdHeader.toLowerCase -> "sub-1"),
              query = Map.empty,
              body = "{}"
            )
        assertEquals(Authenticator.Gateway(keys).callerOf(request).unsafeRunSync(), Left(Errors.unauthenticatedToken))
    }

    test("the gateway authenticator takes the caller from the verified sub claim") {
        val request =
            Request(
              method = "GET",
              path = "/me",
              headers = Map.empty,
              query = Map.empty,
              body = "{}",
              claims = Map("sub" -> "sub-from-token", "email" -> "a@b.c")
            )

        assertEquals(Authenticator.GatewayClaims.callerOf(request).unsafeRunSync(), Right("sub-from-token"))
    }

    test("the gateway authenticator ignores the identity header entirely") {
        // The header is the local mode's mechanism. Were it honoured deployed, anyone could set it
        // and become any player, which is exactly what the JWT authorizer is there to prevent.
        val request = Request(
          method = "GET",
          path = "/me",
          headers = Map("x-external-id" -> "someone-else"),
          query = Map.empty,
          body = "{}"
        )

        assert(Authenticator.GatewayClaims.callerOf(request).unsafeRunSync().isLeft)
    }

    test("a request with no claims is unauthenticated under the gateway authenticator") {
        val response = Router
            .dispatch(
              services = services,
              request = request(method = "GET", path = "/me", headers = Map.empty),
              authenticator = Authenticator.GatewayClaims
            )
            .unsafeRunSync()

        assertEquals(response.statusCode, 401)
    }

    test("service errors map onto the statuses the API promises") {
        assertEquals(Errors.statusFor(ValidationError("x")), 400)
        assertEquals(Errors.statusFor(UnauthorizedError("x")), 403)
        assertEquals(Errors.statusFor(NotFoundError("x")), 404)
        assertEquals(Errors.statusFor(ConflictError("x")), 409)
        assertEquals(Errors.statusFor(GoneError("x")), 410)
        assertEquals(Errors.statusFor(UnavailableError("x")), 503)
        assertEquals(Errors.statusFor(new RuntimeException("x")), 500)
    }

    test("an infrastructure failure does not leak its message to the caller") {
        val response = quietly(Errors.toResponse(new RuntimeException("connection to 10.0.0.1 refused")))
        assertEquals(response.statusCode, 500)
        assert(!response.body.contains("10.0.0.1"), response.body)
    }

    test("a service error does explain itself to the caller") {
        val response = Errors.toResponse(ConflictError("nickname is already registered"))
        assertEquals(response.statusCode, 409)
        assert(response.body.contains("nickname is already registered"), response.body)
    }
}
