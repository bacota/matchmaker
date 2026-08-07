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
