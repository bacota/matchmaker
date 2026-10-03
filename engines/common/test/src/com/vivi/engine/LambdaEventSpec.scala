package com.vivi.engine

import munit.FunSuite

/** The API Gateway event, in and out — the only part of a request that differs between Lambda and the local server. */
class LambdaEventSpec extends FunSuite {

    test("a lambda event decodes to the same request the local server builds, claims included") {
        val event = ujson.Obj(
          "rawPath" -> "/matches/m-9/moves",
          "requestContext" -> ujson.Obj(
            "http" -> ujson.Obj("method" -> "POST"),
            // What the JWT authorizer writes into the event once it has verified the token.
            "authorizer" -> ujson.Obj(
              "jwt" -> ujson.Obj("claims" -> ujson.Obj("sub" -> "sub-alice", "token_use" -> "id"))
            )
          ),
          "headers" -> ujson.Obj("Content-Type" -> "application/json"),
          "body" -> """{"cell":4}""",
          "isBase64Encoded" -> false
        )
        val decoded = LambdaEvent.decode(ujson.write(event))
        assertEquals(decoded.method, "POST")
        assertEquals(decoded.path, "/matches/m-9/moves")
        assertEquals(decoded.body, """{"cell":4}""")
        assertEquals(decoded.claims.get("sub"), Some("sub-alice"))
        // Lowercased on the way in, since payload v2 does and a lookup for "Authorization" must match.
        assertEquals(decoded.headers.get("content-type"), Some("application/json"))

        val encoded = ujson.read(LambdaEvent.encode(EngineResponse(201, """{"ok":true}""")))
        assertEquals(encoded("statusCode").num, 201.0)
        assertEquals(encoded("body").str, """{"ok":true}""")
    }

    test("a response's own headers go out beside its content type") {
        val encoded = ujson.read(
          LambdaEvent.encode(EngineResponse(304, "", headers = Map("etag" -> "\"t\"", "cache-control" -> "no-cache")))
        )
        assertEquals(encoded("statusCode").num, 304.0)
        assertEquals(encoded("headers")("etag").str, "\"t\"")
        assertEquals(encoded("headers")("cache-control").str, "no-cache")
        assertEquals(encoded("headers")("content-type").str, "application/json")
    }
}
