package com.vivi.matchmaker.engine

import java.net.URI
import java.time.Instant
import munit.FunSuite

/** Checks what this wrapper is responsible for: the headers it puts on a request, and the
  * options it hands the SDK's signer.
  *
  * The algorithm itself is the SDK's and is not re-tested here — asserting AWS's own published
  * `aws4_testsuite` vectors against AWS's own signer would only be testing that the dependency
  * resolved. What does need testing is that this call site drives it the way API Gateway
  * requires, which is what the encoded-path case below is for.
  */
class SigV4Spec extends FunSuite {

  private val credentials =
    AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", None)

  private val signedAt = Instant.parse("2015-08-30T12:36:00Z")

  private def sign(url: String, headers: Map[String, String] = Map.empty, body: String = "", creds: AwsCredentials = credentials) =
    SigV4.sign("GET", URI.create(url), headers, body, creds, "us-east-1", "execute-api", signedAt)

  test("a signed request carries the headers API Gateway checks") {
    val headers = sign("https://example.amazonaws.com/")

    assert(
      headers("Authorization").startsWith(
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/execute-api/aws4_request, "
      )
    )
    assert(headers("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    assertEquals(headers("X-Amz-Date"), "20150830T123600Z")
    assertEquals(headers("Host"), "example.amazonaws.com")
  }

  test("a header on the request is signed but not returned again") {
    val headers = SigV4.sign(
      "POST",
      URI.create("https://example.amazonaws.com/games"),
      Map("content-type" -> "application/json"),
      """{"players":2}""",
      credentials,
      "us-east-1",
      "execute-api",
      signedAt
    )

    assert(headers("Authorization").contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"))
    // Returning it would leave the caller to merge two copies of a header the signature covers.
    assert(!headers.contains("content-type"))
  }

  // Temporary credentials are what Lambda actually has, and their token is both signed and sent
  // — an unsigned token would be ignored, and an unsent one would leave the role unidentified.
  test("a session token is both signed and returned") {
    val headers = sign("https://example.amazonaws.com/", creds = credentials.copy(sessionToken = Some("session-token")))

    assertEquals(headers("X-Amz-Security-Token"), "session-token")
    assert(headers("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token"))
  }

  /* The signer's default is to encode the path a second time before signing it, which is right
   * for every service but `execute-api` — see the properties SigV4.sign sets. Nothing detects
   * that on a path with nothing to encode, so this pins one that has: the signature below was
   * produced with those properties set, and removing either of them changes it. */
  test("the path is signed as sent, not encoded a second time") {
    val headers = sign("https://example.amazonaws.com/games/7/matches%20and%20more/")

    assert(
      headers("Authorization").endsWith(
        "Signature=62b9b98a748170d6715f3eeb573334a8861c37c196f7d0c5158ab85b8d016458"
      ),
      headers("Authorization")
    )
  }

  test("credentials come from the environment when they are set") {
    val env = Map(
      "AWS_ACCESS_KEY_ID" -> "id",
      "AWS_SECRET_ACCESS_KEY" -> "secret",
      "AWS_SESSION_TOKEN" -> "token"
    )
    assertEquals(
      AwsCredentials.fromEnvironment(env.get),
      Some(AwsCredentials("id", "secret", Some("token")))
    )
    assertEquals(AwsCredentials.fromEnvironment(_ => None), None)
  }
}
