package com.vivi.matchmaker.api

import java.util.Base64
import munit.FunSuite

class ApiGatewaySpec extends FunSuite {

  /** Shaped like a real payload-v2 event, including fields the decoder ignores, so that the
    * decoder is exercised against extra keys rather than a minimal hand-made object.
    */
  private def event(
      method: String = "POST",
      path: String = "/register",
      headers: String = """"x-external-id": "sub-1", "content-type": "application/json"""",
      body: String = """"{\"nickname\":\"tester\"}"""",
      isBase64Encoded: Boolean = false,
      query: String = """"queryStringParameters": {"activeOnly": "true"},"""
  ): String =
    s"""{
       |  "version": "2.0",
       |  "routeKey": "$$default",
       |  "rawPath": "$path",
       |  "rawQueryString": "",
       |  "headers": {$headers},
       |  $query
       |  "requestContext": {
       |    "accountId": "123456789012",
       |    "apiId": "abcdef",
       |    "http": {
       |      "method": "$method",
       |      "path": "$path",
       |      "protocol": "HTTP/1.1",
       |      "sourceIp": "192.0.2.1",
       |      "userAgent": "curl/8.0"
       |    },
       |    "requestId": "id",
       |    "stage": "$$default"
       |  },
       |  "body": $body,
       |  "isBase64Encoded": $isBase64Encoded
       |}""".stripMargin

  test("decodes method, path, body and query from a payload-v2 event") {
    val request = ApiGateway.decodeRequest(event())
    assertEquals(request.method, "POST")
    assertEquals(request.path, "/register")
    assertEquals(request.body, """{"nickname":"tester"}""")
    assertEquals(request.query.get("activeOnly"), Some("true"))
  }

  test("header lookup is case-insensitive, since payload v2 lowercases header names") {
    val request = ApiGateway.decodeRequest(event())
    assertEquals(request.header("X-External-Id"), Some("sub-1"))
    assertEquals(request.header("x-external-id"), Some("sub-1"))
  }

  test("decodes a base64-encoded body") {
    val encoded = Base64.getEncoder.encodeToString("""{"nickname":"tester"}""".getBytes("UTF-8"))
    val request = ApiGateway.decodeRequest(event(body = s""""$encoded"""", isBase64Encoded = true))
    assertEquals(request.body, """{"nickname":"tester"}""")
  }

  test("tolerates an event with no body, headers or query") {
    val request = ApiGateway.decodeRequest(
      """{"rawPath": "/games", "requestContext": {"http": {"method": "GET"}}}"""
    )
    assertEquals(request.method, "GET")
    assertEquals(request.body, "")
    assertEquals(request.headers, Map.empty[String, String])
    assertEquals(request.query, Map.empty[String, String])
  }

  /** What a JWT authorizer adds to the request context, in the shape API Gateway sends it: every
    * claim a string, except the array-valued ones.
    */
  private val authorizedEvent =
    """{
      |  "rawPath": "/me",
      |  "requestContext": {
      |    "http": {"method": "GET"},
      |    "authorizer": {
      |      "jwt": {
      |        "claims": {
      |          "sub": "8f14e45f-ceea-467a-9a1b-1f2c3d4e5f60",
      |          "email": "player@example.com",
      |          "email_verified": "true",
      |          "token_use": "id",
      |          "cognito:groups": ["admins"],
      |          "exp": "1767225600"
      |        },
      |        "scopes": null
      |      }
      |    }
      |  }
      |}""".stripMargin

  test("decodes the claims a JWT authorizer put in the request context") {
    val request = ApiGateway.decodeRequest(authorizedEvent)
    assertEquals(request.claim("sub"), Some("8f14e45f-ceea-467a-9a1b-1f2c3d4e5f60"))
    assertEquals(request.claim("email"), Some("player@example.com"))
  }

  test("an array-valued claim is dropped rather than mangled into a string") {
    // cognito:groups arrives as a JSON array. Nothing reads it yet; what matters is that its
    // presence does not fail the decode of the claims beside it.
    val request = ApiGateway.decodeRequest(authorizedEvent)
    assertEquals(request.claim("cognito:groups"), None)
    assertEquals(request.claim("exp"), Some("1767225600"))
  }

  test("an event with no authorizer decodes to no claims") {
    // The local server and any unauthenticated route land here, so this must be empty rather
    // than throwing — GatewayClaims turns it into a 401.
    assertEquals(ApiGateway.decodeRequest(event()).claims, Map.empty[String, String])
  }

  /** What an AWS_IAM-authorized route adds instead, in the shape API Gateway sends it: the
    * caller's assumed-role session, not the role itself.
    */
  private val iamAuthorizedEvent =
    """{
      |  "rawPath": "/games/1/matches/m1/results",
      |  "requestContext": {
      |    "http": {"method": "POST"},
      |    "authorizer": {
      |      "iam": {
      |        "accessKey": "ASIAEXAMPLE",
      |        "accountId": "123456789012",
      |        "callerId": "AROAEXAMPLEID:engine-session",
      |        "cognitoIdentity": null,
      |        "principalOrgId": null,
      |        "userArn": "arn:aws:sts::123456789012:assumed-role/game-engine/engine-session",
      |        "userId": "AROAEXAMPLEID:engine-session"
      |      }
      |    }
      |  }
      |}""".stripMargin

  test("decodes the principal an IAM authorizer put in the request context") {
    val request = ApiGateway.decodeRequest(iamAuthorizedEvent)
    assertEquals(request.iam.map(_.userArn), Some("arn:aws:sts::123456789012:assumed-role/game-engine/engine-session"))
    assertEquals(request.iam.flatMap(_.accountId), Some("123456789012"))
    assertEquals(request.claims, Map.empty[String, String])
  }

  // The session part of an assumed-role ARN changes on every assumption, so it cannot be what a
  // game's externalId is compared against — the role is what stays put.
  test("an assumed-role principal is normalized to the role that was assumed") {
    assertEquals(
      ApiGateway.decodeRequest(iamAuthorizedEvent).iam.map(_.roleArn),
      Some("arn:aws:iam::123456789012:role/game-engine")
    )
  }

  test("a principal that is not an assumed role is left as it arrived") {
    val principal = ApiGateway.IamPrincipal("arn:aws:iam::123456789012:user/engine", None, None)
    assertEquals(principal.roleArn, "arn:aws:iam::123456789012:user/engine")
  }

  test("an event with no authorizer decodes to no principal") {
    assertEquals(ApiGateway.decodeRequest(event()).iam, None)
  }

  test("splits the path into non-empty segments") {
    assertEquals(ApiGateway.decodeRequest(event(path = "/games/7/characters")).segments, List("games", "7", "characters"))
    assertEquals(ApiGateway.decodeRequest(event(path = "/")).segments, Nil)
  }

  test("encodes a response as a payload-v2 result with a JSON content type") {
    val encoded = ujson.read(ApiGateway.encodeResponse(ApiGateway.Response(201, """{"ok":true}""")))
    assertEquals(encoded("statusCode").num.toInt, 201)
    assertEquals(encoded("body").str, """{"ok":true}""")
    assertEquals(encoded("headers")("content-type").str, "application/json")
    assertEquals(encoded("isBase64Encoded").bool, false)
  }
}
