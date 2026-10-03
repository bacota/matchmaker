package com.vivi.engine

import java.net.URI
import java.time.{Clock, Instant, ZoneOffset}
import munit.FunSuite
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity

/** Signatures, checked against ones made independently by botocore — AWS's own Python signer — for the same request,
  * credentials and instant. A signature API Gateway does not agree with is a 403 at every call, and nothing short of a
  * deployed call shows it otherwise.
  */
class SignedHttpSpec extends FunSuite {

    private val identity = AwsCredentialsIdentity.create("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val http =
        SignedHttp(None, "us-east-1", clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC))

    test("a Play Live push is signed with its path encoded twice, as API Gateway's @connections checks it") {
        // A connection id ends in "=", which the url carries as "%3D": the character that made every push a 403
        // while the signer was told to encode the path only once.
        val signed = http.sign(
          "POST",
          URI.create("https://abc123.execute-api.us-east-1.amazonaws.com/live/@connections/gcVF_C7ZSQAYKEiNEA%3D"),
          Map("content-type" -> "application/json"),
          """{"changed":"m-1"}""",
          "execute-api",
          identity
        )
        assertEquals(
          signed("Authorization"),
          "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261002/us-east-1/execute-api/aws4_request, " +
              "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, " +
              "Signature=297984e9f7bc5ebfb4f480faf28cc661e711c0428074964b166c60f3b21002b8"
        )
    }
}
