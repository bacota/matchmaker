package com.vivi.matchmaker.api

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service._
import ApiGateway.Request

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
    Router.dispatch(services, request).unsafeRunSync()

  private def statusOf(method: String, path: String, headers: Map[String, String], body: String): Int =
    dispatch(request(method, path, headers, body)).statusCode

  test("a request without the identity header is unauthenticated") {
    assertEquals(statusOf("GET", "/me", Map.empty, "{}"), 401)
  }

  test("a blank identity header is treated as missing") {
    assertEquals(statusOf("GET", "/me", Map("x-external-id" -> "   "), "{}"), 401)
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
    assertEquals(dispatch(request("DELETE", "/challenges/1/acceptances/abc")).statusCode, 400)
  }

  test("every routed endpoint reaches a service rather than falling through to 404") {
    // A route that matched will hit the unusable pool and come back 500; one that did not match
    // comes back 404. So a 500 here is exactly the evidence that the route is wired up.
    val routed = List(
      ("POST", "/register", """{"nickname":"tester"}"""),
      ("GET", "/me", "{}"),
      ("GET", "/me/matches", "{}"),
      ("GET", "/me/matches/due", "{}"),
      ("GET", "/me/matches/completed", "{}"),
      ("GET", "/games", "{}"),
      ("GET", "/games/1/challenges", "{}"),
      ("POST", "/games/1/characters", """{"name":"n","description":"d","externalId":"sub-1"}"""),
      ("PUT", "/characters/1", """{"name":"n","description":"d","externalId":"sub-1"}"""),
      ("PUT", "/characters/1/state", """{"state":"s"}"""),
      ("POST", "/challenges/1/acceptances", """{"characterId":1}"""),
      ("DELETE", "/challenges/1", "{}"),
      ("DELETE", "/challenges/1/acceptances/2", "{}")
    )

    routed.foreach { case (method, path, body) =>
      assertEquals(statusOf(method, path, Map("x-external-id" -> "sub-1"), body), 500, s"$method $path")
    }
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
