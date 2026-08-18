package com.vivi.matchmaker.engine

import java.net.URI
import java.time.Instant
import munit.FunSuite

/** Checks the signer against AWS's own published `aws4_testsuite` vectors.
  *
  * The point of testing against a vector rather than against itself: a signature is only useful
  * if API Gateway computes the same one, and nothing local can tell us whether it does. These
  * inputs and outputs come from AWS, so agreeing with them is evidence the algorithm is right
  * rather than merely self-consistent.
  */
class SigV4Spec extends FunSuite {

  private val credentials =
    AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", None)

  private val signedAt = Instant.parse("2015-08-30T12:36:00Z")

  test("get-vanilla matches the published signature") {
    val headers = SigV4.sign(
      method = "GET",
      uri = URI.create("https://example.amazonaws.com/"),
      headers = Map.empty,
      body = "",
      credentials = credentials,
      region = "us-east-1",
      service = "service",
      now = signedAt
    )

    assertEquals(
      headers("Authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=host;x-amz-date, " +
        "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
    )
    assertEquals(headers("X-Amz-Date"), "20150830T123600Z")
    assertEquals(headers("Host"), "example.amazonaws.com")
  }

  test("get-vanilla-query-order-key-case matches the published signature") {
    val headers = SigV4.sign(
      method = "GET",
      uri = URI.create("https://example.amazonaws.com/?Param2=value2&Param1=value1"),
      headers = Map.empty,
      body = "",
      credentials = credentials,
      region = "us-east-1",
      service = "service",
      now = signedAt
    )

    assertEquals(
      headers("Authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=host;x-amz-date, " +
        "Signature=b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"
    )
  }

  test("post-x-www-form-urlencoded signs the body it sends") {
    val headers = SigV4.sign(
      method = "POST",
      uri = URI.create("https://example.amazonaws.com/"),
      headers = Map("content-type" -> "application/x-www-form-urlencoded"),
      body = "Param1=value1",
      credentials = credentials,
      region = "us-east-1",
      service = "service",
      now = signedAt
    )

    assertEquals(
      headers("Authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=content-type;host;x-amz-date, " +
        "Signature=ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a"
    )
  }

  // Temporary credentials are what Lambda actually has, and their token is both signed and sent
  // — an unsigned token would be ignored, and an unsent one would leave the role unidentified.
  test("a session token is both signed and returned") {
    val headers = SigV4.sign(
      method = "GET",
      uri = URI.create("https://example.amazonaws.com/"),
      headers = Map.empty,
      body = "",
      credentials = credentials.copy(sessionToken = Some("session-token")),
      region = "us-east-1",
      service = "service",
      now = signedAt
    )

    assertEquals(headers("X-Amz-Security-Token"), "session-token")
    assert(headers("Authorization").contains("SignedHeaders=host;x-amz-date;x-amz-security-token"))
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
