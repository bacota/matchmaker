package com.vivi.matchmaker.engine

import cats.effect.unsafe.implicits.global
import java.net.http.HttpClient
import munit.FunSuite

/** What the client does about the API key, which is the difference between a call that works and a bare `401` with
  * nothing in it to act on.
  */
class HttpGameEngineClientSpec extends FunSuite {

    private val client = new HttpGameEngineClient(httpClient = HttpClient.newHttpClient())

    private val request = CreateGameRequest(
      matchId = "m1",
      gameName = "game",
      isPublic = false,
      parameters = Map.empty,
      settings = "{}",
      timeLimitSeconds = None,
      players = Nil,
      moveCallbackUrl = None,
      resultsCallbackUrl = None
    )

    test("a call to a deployed engine with no key is refused before it is sent") {
        val outcome = client
            .createGame("https://abc123.execute-api.us-east-1.amazonaws.com/games", None, request)
            .attempt
            .unsafeRunSync()

        val error = outcome.swap.getOrElse(fail("the unauthenticated call was not refused"))
        // The point of the message: the engine answers a keyless request with a 401 that names no
        // cause, so the cause has to be named here or it is named nowhere.
        assert(clue(error.getMessage).contains("no API key"))
        assert(error.getMessage.contains("admin form"))
        assert(clue(error.getMessage).contains("abc123.execute-api.us-east-1.amazonaws.com"))
    }

    test("a call to an engine that is not on AWS is made without a key, because a local stub needs none") {
        // Nothing is listening on this port; reaching a connection failure is what shows the request
        // was attempted rather than refused for want of a key.
        val outcome = client.createGame("http://localhost:1/games", None, request).attempt.unsafeRunSync()

        val error = outcome.swap.getOrElse(fail("the call unexpectedly succeeded"))
        assert(!error.getMessage.contains("no API key"))
        assert(clue(error.getMessage).contains("failed"))
    }

    test("the key is never in the message of a failure") {
        val outcome = client
            .createGame("http://localhost:1/games", Some("s3cret"), request)
            .attempt
            .unsafeRunSync()

        val error = outcome.swap.getOrElse(fail("the call unexpectedly succeeded"))
        assert(!clue(error.getMessage).contains("s3cret"))
    }
}
