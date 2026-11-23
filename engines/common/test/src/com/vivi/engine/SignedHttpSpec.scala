package com.vivi.engine

import java.net.URI
import java.time.{Clock, Instant, ZoneOffset}
import munit.FunSuite
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity

/** Signatures, checked against ones made independently by botocore — AWS's own Python signer — for the same request,
  * credentials and instant. A signature API Gateway does not agree with is a 403 at every call, and nothing short of a
  * deployed call shows it otherwise.
  */
class SignedHttpSpec extends FunSuite {

    private val identity = AwsCredentialsIdentity.create("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val http =
        SignedHttp(
          credentials = None,
          region = "us-east-1",
          clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
        )

    test("a Play Live push is signed with its path encoded twice, as API Gateway's @connections checks it") {
        // A connection id ends in "=", which the url carries as "%3D": the character that made every push a 403
        // while the signer was told to encode the path only once.
        val signed = http.sign(
          method = "POST",
          uri =
              URI.create("https://abc123.execute-api.us-east-1.amazonaws.com/live/@connections/gcVF_C7ZSQAYKEiNEA%3D"),
          headers = Map("content-type" -> "application/json"),
          body = """{"changed":"m-1"}""".getBytes("UTF-8"),
          service = "execute-api",
          identity = identity
        )
        assertEquals(
          signed("Authorization"),
          "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261002/us-east-1/execute-api/aws4_request, " +
              "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, " +
              "Signature=297984e9f7bc5ebfb4f480faf28cc661e711c0428074964b166c60f3b21002b8"
        )
    }

    test("an S3 put is signed with its key encoded once and its payload's hash, as botocore signs it") {
        val signed = http.sign(
          method = "PUT",
          uri = URI.create("https://games-bucket.s3.us-east-1.amazonaws.com/" + S3.encodeKey("games/a b/start.vsav")),
          headers = Map("content-type" -> "application/octet-stream"),
          body = "hello".getBytes("UTF-8"),
          service = "s3",
          identity = identity
        )
        assertEquals(
          signed("x-amz-content-sha256"),
          "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        )
        assertEquals(
          signed("Authorization"),
          "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261002/us-east-1/s3/aws4_request, " +
              "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, " +
              "Signature=9bbf7b88ac22dee69a2f2eb958dcaf6efbd1929c3bbd294318379cd6b38f3f58"
        )
    }

    test("a presigned S3 GET carries botocore's signature in its query") {
        val presigning = SignedHttp(
          credentials = Some(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(identity.accessKeyId, identity.secretAccessKey))
          ),
          region = "us-east-1",
          clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
        )
        val url = S3(presigning, "us-east-1").presignedGet(
          bucket = "games-bucket",
          key = "games/g1/final.vlog",
          expiresIn = java.time.Duration.ofSeconds(900)
        )
        assert(url.startsWith("https://games-bucket.s3.us-east-1.amazonaws.com/games/g1/final.vlog?"), url)
        val query = url.dropWhile(_ != '?').drop(1).split('&').map(_.split("=", 2)).map(kv => kv(0) -> kv(1)).toMap
        assertEquals(query("X-Amz-Expires"), "900")
        assertEquals(query("X-Amz-SignedHeaders"), "host")
        assertEquals(query("X-Amz-Signature"), "30e1936db50b888c2be088e8c21469b01527965a3bfcc80336bab726637aef61")
    }

    test("an S3 bucket with a dot in its name is addressed path style, which S3's certificate covers") {
        val s3 = S3(http, "us-east-1")
        assertEquals(s3.url("games-bucket", "a b"), "https://games-bucket.s3.us-east-1.amazonaws.com/a%20b")
        assertEquals(
          s3.url("site.example.com", "modules/index.json"),
          "https://s3.us-east-1.amazonaws.com/site.example.com/modules/index.json"
        )
    }

    test("an S3 key keeps its slashes and unreserved characters and encodes every other byte") {
        assertEquals(S3.encodeKey("modules/ab~c_d-e.f/x y+z=é"), "modules/ab~c_d-e.f/x%20y%2Bz%3D%C3%A9")
    }
}
