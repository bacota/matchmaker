package com.vivi.matchmaker.engine

import cats.effect.unsafe.implicits.global
import java.net.http.HttpClient
import munit.FunSuite

/** What the client does about signing, which is the difference between a call that works and a
  * bare `403 Forbidden` with nothing in it to act on.
  */
class HttpGameEngineClientSpec extends FunSuite {

  private val credentials = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", None)

  /* No HttpClient calls are made in these tests: each one is refused before the request is sent,
   * which is the behaviour being tested. */
  private def client(creds: Option[AwsCredentials]) =
    new HttpGameEngineClient(() => creds, region = "us-east-1", httpClient = HttpClient.newHttpClient())

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

  test("a call to an AWS-hosted engine with no credentials is refused before it is sent") {
    val outcome = client(None)
      .createGame("https://abc123.execute-api.us-east-1.amazonaws.com/games", request)
      .attempt
      .unsafeRunSync()

    val error = outcome.swap.getOrElse(fail("the unsigned call was not refused"))
    // The point of the message: an unsigned request is answered by API Gateway with a 403 that
    // says only "Forbidden", so the cause has to be named here or it is named nowhere.
    assert(clue(error.getMessage).contains("no AWS credentials"))
    assert(error.getMessage.contains("AWS_ACCESS_KEY_ID"))
  }

  test("a call to an engine that is not on AWS is left unsigned, because a local stub needs none") {
    // Nothing is listening on this port; reaching a connection failure is what shows the request
    // was attempted rather than refused for want of credentials.
    val outcome = client(None).createGame("http://localhost:1/games", request).attempt.unsafeRunSync()

    val error = outcome.swap.getOrElse(fail("the call unexpectedly succeeded"))
    assert(!error.getMessage.contains("no AWS credentials"))
    assert(clue(error.getMessage).contains("failed"))
  }

  test("credentials are read for each request, not once for the client") {
    var reads = 0
    val counting = new HttpGameEngineClient(
      () => { reads += 1; Some(credentials) },
      region = "us-east-1",
      httpClient = HttpClient.newHttpClient()
    )
    // Both fail to connect; what matters is that each attempt asked for credentials again. The
    // execution role's are temporary and rotated in place, so a copy kept by the client goes
    // stale while the client stays alive.
    counting.createGame("http://localhost:1/games", request).attempt.unsafeRunSync()
    counting.createGame("http://localhost:1/games", request).attempt.unsafeRunSync()
    assertEquals(reads, 2)
  }
}
