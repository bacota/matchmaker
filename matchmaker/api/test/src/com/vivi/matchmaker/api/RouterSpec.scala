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

/** Routing, authentication and error mapping, none of which need a database.
  *
  * The services are built over a pool that fails on use, so any route that reaches a service
  * fails loudly — which keeps these tests honest about only covering the paths that answer
  * before the database is touched.
  */
class RouterSpec extends FunSuite {

  private val unusablePool: SessionPool =
    Resource.eval(IO.raiseError[Session[IO]](new IllegalStateException("the database must not be reached")))

  private val services: Services[String] = Services.fromPool[String](unusablePool)

  private def request(
      method: String,
      path: String,
      headers: Map[String, String] = Map("x-external-id" -> "sub-1"),
      body: String = "{}"
  ): Request = Request(method, path, headers, Map.empty, body)

  private def dispatch(request: Request): ApiGateway.Response =
    Router.dispatch(services, request, Authenticator.TrustedHeader).unsafeRunSync()

  private def statusOf(method: String, path: String, headers: Map[String, String], body: String): Int =
    dispatch(request(method, path, headers, body)).statusCode

  test("a request without the identity header is unauthenticated") {
    assertEquals(statusOf("GET", "/me", Map.empty, "{}"), 401)
  }

  test("a blank identity header is treated as missing") {
    assertEquals(statusOf("GET", "/me", Map("x-external-id" -> "   "), "{}"), 401)
  }

  // Guards the by-name `services` parameter. `fail` as the argument stands in for the pool
  // construction the deployed handler would do: if the parameter is ever made strict again, this
  // test fails rather than a rejected request quietly paying for a database pool it never uses.
  test("a rejected request never forces the services") {
    val alwaysRejects = new Authenticator {
      def callerOf(request: Request): Either[ApiGateway.Response, String] = Left(Errors.response(401, "no"))
    }

    val response =
      Router.dispatch(fail("services must not be forced for a rejected request"), request("GET", "/me"), alwaysRejects)

    assertEquals(response.unsafeRunSync().statusCode, 401)
  }

  test("an unknown path is not found") {
    assertEquals(dispatch(request("GET", "/nonsense")).statusCode, 404)
  }

  test("a known path with the wrong method is not found") {
    assertEquals(dispatch(request("DELETE", "/register")).statusCode, 404)
  }

  test("a malformed request body is a bad request, before any service is called") {
    assertEquals(dispatch(request("POST", "/register", body = "not json")).statusCode, 400)
  }

  test("a body missing a required field is a bad request") {
    assertEquals(dispatch(request("POST", "/register", body = """{"wrong":"field"}""")).statusCode, 400)
  }

  test("a non-numeric game id is a bad request") {
    assertEquals(dispatch(request("GET", "/games/abc/challenges")).statusCode, 400)
  }

  test("a non-numeric character id is a bad request") {
    assertEquals(dispatch(request("PUT", "/characters/abc")).statusCode, 400)
  }

  test("a non-numeric player id in an acceptance path is a bad request") {
    assertEquals(dispatch(request("DELETE", "/challenges/1/1/acceptances/abc")).statusCode, 400)
  }

  /** Bodies are serialized from real model values rather than hand-written JSON, so that a body
    * is never accidentally invalid — which would fail the request at parsing with 400 and hide
    * whether the route matched at all.
    */
  private val gameBody = write(
    Game(GameId.unassigned, GameType.Character, "name", "description", "url", active = true, Seq.empty, Seq.empty, "secret", 2, 4)
  )

  private val challengeBody = write(
    CharacterOpenChallenge(ChallengeId(0), PlayerId(1), "message", 2.toShort, None, None, "{}", GameId(1), CharacterId(1), gameRoleId = GameRoleId(1))
  )

  private val resultsBody = write(
    Json.MatchResults(List(Json.ResultEntry(ParticipantId(1), 1, Map("points" -> ujson.Num(3)), isWinner = true)))
  )

  /** Every route in `Router`. Keep in step with it: a route missing from here is a route no test
    * would notice breaking.
    */
  private val routed = List(
    ("POST", "/register", """{"nickname":"tester"}"""),
    ("GET", "/me", "{}"),
    ("GET", "/me/acceptances", "{}"),
    ("GET", "/me/matches", "{}"),
    ("GET", "/me/matches/due", "{}"),
    ("GET", "/me/matches/completed", "{}"),
    ("GET", "/games", "{}"),
    ("POST", "/games", gameBody),
    ("GET", "/games/1/challenges", "{}"),
    ("GET", "/games/1/characters", "{}"),
    ("POST", "/games/1/characters", """{"name":"n","description":"d","externalId":"sub-1"}"""),
    ("PUT", "/characters/1", """{"name":"n","description":"d","externalId":"sub-1"}"""),
    ("PUT", "/characters/1/state", """{"state":"s"}"""),
    ("POST", "/challenges", challengeBody),
    ("DELETE", "/challenges/1/1", "{}"),
    ("POST", "/challenges/1/1/acceptances", """{"characterId":1,"gameRoleId":1}"""),
    ("DELETE", "/challenges/1/1/acceptances/2", "{}"),
    ("POST", "/challenges/1/1/start", "{}"),
    ("GET", "/games/1/matches/m1", "{}"),
    ("POST", "/games/1/matches/m1/refresh", "{}"),
    ("POST", "/games/1/matches/m1/moves", """{"participantId":1,"next":[2],"prevMoveAt":"2030-01-01T00:00:00Z"}"""),
    ("POST", "/games/1/matches/m1/results", resultsBody)
  )

  test("every routed endpoint reaches a service rather than falling through to 404") {
    // A route that matched will hit the unusable pool and come back 500; one that did not match
    // comes back 404, and one whose body failed to parse comes back 400. So a 500 here is
    // exactly the evidence that the route is wired up and its body was understood.
    routed.foreach { case (method, path, body) =>
      assertEquals(statusOf(method, path, Map("x-external-id" -> "sub-1"), body), 500, s"$method $path")
    }
  }

  test("the routed list covers every route Router declares") {
    // A count, because the route table cannot be enumerated from Router itself. It fails loudly
    // when a route is added there without a corresponding entry above.
    assertEquals(routed.size, 22)
    assertEquals(routed.distinct.size, routed.size)
  }

  test("every routed endpoint requires the identity header") {
    routed.foreach { case (method, path, body) =>
      assertEquals(statusOf(method, path, Map.empty, body), 401, s"$method $path")
    }
  }

  test("the caller comes from the authenticator, not from the header") {
    // Stands in for GatewayClaims, which will take the caller from a verified token rather than a
    // header: the routes must not care where the identity came from.
    val fromElsewhere = new Authenticator {
      def callerOf(request: Request): Either[ApiGateway.Response, String] = Right("sub-from-token")
    }

    val response = Router.dispatch(services, request("GET", "/me", headers = Map.empty), fromElsewhere).unsafeRunSync()

    // No header at all, yet the request is authenticated and reaches the unusable pool.
    assertEquals(response.statusCode, 500)
  }

  test("an authenticator's rejection is returned as it wrote it") {
    val alwaysExpired = new Authenticator {
      def callerOf(request: Request): Either[ApiGateway.Response, String] =
        Left(Errors.response(401, "token has expired"))
    }

    val response = Router.dispatch(services, request("GET", "/me"), alwaysExpired).unsafeRunSync()

    assertEquals(response.statusCode, 401)
    assert(response.body.contains("token has expired"), response.body)
  }

  // The game engine's callbacks carry the key matchmaker and that engine share. The key names
  // the engine, because matchmaker holds a different one for each — that name is the game's
  // externalId, which is what the services authorize a game-authorized caller by.
  private val keys = () => ApiKeys(Map("tictactoe" -> "s3cret"))

  test("the gateway authenticator turns an engine's API key into that engine's name") {
    val request = ApiGateway.Request(
      "POST",
      "/games/1/matches/m1/results",
      Map(ApiKeys.Header -> "s3cret"),
      Map.empty,
      "{}"
    )

    assertEquals(Authenticator.Gateway(keys).callerOf(request), Right("tictactoe"))
  }

  test("a key nobody was issued is refused, exactly as a missing one is") {
    def callerOf(headers: Map[String, String]) =
      Authenticator.Gateway(keys).callerOf(ApiGateway.Request("POST", "/games/1/matches/m1/results", headers, Map.empty, "{}"))

    assertEquals(callerOf(Map(ApiKeys.Header -> "guess")), Left(Errors.unauthenticated))
    assertEquals(callerOf(Map(ApiKeys.Header -> "")), Left(Errors.unauthenticated))
  }

  test("the gateway authenticator prefers a verified token over anything else on the request") {
    val request = ApiGateway.Request(
      "GET",
      "/me",
      // Both a spoofable identity header and a real key: neither displaces the claim, so an
      // engine holding a valid key cannot present itself as a player.
      Map(ApiGateway.ExternalIdHeader.toLowerCase -> "spoofed", ApiKeys.Header -> "s3cret"),
      Map.empty,
      "{}",
      claims = Map("sub" -> "sub-from-token")
    )

    assertEquals(Authenticator.Gateway(keys).callerOf(request), Right("sub-from-token"))
  }

  test("a request the gateway authenticated in neither way is unauthenticated") {
    val request = ApiGateway.Request("GET", "/me", Map(ApiGateway.ExternalIdHeader.toLowerCase -> "sub-1"), Map.empty, "{}")
    assertEquals(Authenticator.Gateway(keys).callerOf(request).map(identity), Left(Errors.unauthenticatedToken))
  }

  test("the gateway authenticator takes the caller from the verified sub claim") {
    val request = Request("GET", "/me", Map.empty, Map.empty, "{}", Map("sub" -> "sub-from-token", "email" -> "a@b.c"))

    assertEquals(Authenticator.GatewayClaims.callerOf(request), Right("sub-from-token"))
  }

  test("the gateway authenticator ignores the identity header entirely") {
    // The header is the local mode's mechanism. Were it honoured deployed, anyone could set it
    // and become any player, which is exactly what the JWT authorizer is there to prevent.
    val request = Request("GET", "/me", Map("x-external-id" -> "someone-else"), Map.empty, "{}")

    assert(Authenticator.GatewayClaims.callerOf(request).isLeft)
  }

  test("a request with no claims is unauthenticated under the gateway authenticator") {
    val response = Router
      .dispatch(services, request("GET", "/me", headers = Map.empty), Authenticator.GatewayClaims)
      .unsafeRunSync()

    assertEquals(response.statusCode, 401)
  }

  test("service errors map onto the statuses the API promises") {
    assertEquals(Errors.statusFor(ValidationError("x")), 400)
    assertEquals(Errors.statusFor(UnauthorizedError("x")), 403)
    assertEquals(Errors.statusFor(NotFoundError("x")), 404)
    assertEquals(Errors.statusFor(ConflictError("x")), 409)
    assertEquals(Errors.statusFor(new RuntimeException("x")), 500)
  }

  test("an infrastructure failure does not leak its message to the caller") {
    val response = Errors.toResponse(new RuntimeException("connection to 10.0.0.1 refused"))
    assertEquals(response.statusCode, 500)
    assert(!response.body.contains("10.0.0.1"), response.body)
  }

  test("a service error does explain itself to the caller") {
    val response = Errors.toResponse(ConflictError("nickname is already registered"))
    assertEquals(response.statusCode, 409)
    assert(response.body.contains("nickname is already registered"), response.body)
  }
}
