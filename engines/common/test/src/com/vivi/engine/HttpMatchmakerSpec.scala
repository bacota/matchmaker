package com.vivi.engine

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import com.sun.net.httpserver.HttpServer
import munit.FunSuite
import upickle.default.read
import Protocol.given

/** The one call an engine makes to matchmaker whose answer it reads: registering a character it has built. Served on a
  * loopback port, so the request really goes over HTTP.
  */
class HttpMatchmakerSpec extends FunSuite {

    private val request = Protocol.RegisterCharacterRequest("Iron Mike", "a slugger", "sub-1", """{"strength":9}""")

    /** What the server saw: method, path, the API key header and the body. */
    private case class Seen(method: String, path: String, apiKey: Option[String], body: String)

    private def withMatchmaker(status: Int, answer: String)(f: (HttpMatchmaker, String, () => Option[Seen]) => Unit) = {
        @volatile var seen: Option[Seen] = None
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/",
          exchange => {
              val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
              seen = Some(
                Seen(
                  exchange.getRequestMethod,
                  exchange.getRequestURI.getPath,
                  Option(exchange.getRequestHeaders.getFirst("x-api-key")),
                  body
                )
              )
              val bytes = answer.getBytes(UTF_8)
              exchange.sendResponseHeaders(status, bytes.length.toLong)
              exchange.getResponseBody.write(bytes)
              exchange.close()
          }
        )
        server.start()
        try
            f(
              HttpMatchmaker(SignedHttp(None, "us-east-1"), Some("the-key"), None),
              s"http://127.0.0.1:${server.getAddress.getPort}/",
              () => seen
            )
        finally server.stop(0)
    }

    test("a registered character is posted to /characters with the key, and answered with matchmaker's id") {
        val character =
            """{"characterId":42,"gameId":3,"name":"Iron Mike","description":"a slugger","state":"{}","playerId":7}"""
        withMatchmaker(201, character) { (matchmaker, url, seen) =>
            assertEquals(matchmaker.registerCharacter(url, request), 42L)
            val s = seen().get
            assertEquals((s.method, s.path, s.apiKey), ("POST", "/characters", Some("the-key")))
            assertEquals(read[Protocol.RegisterCharacterRequest](s.body), request)
        }
    }

    test("matchmaker turning the character down is a refusal carrying its reason") {
        withMatchmaker(404, """{"error":"no player with externalId 'sub-1'"}""") { (matchmaker, url, _) =>
            val refusal = intercept[MatchmakerRefusal](matchmaker.registerCharacter(url, request))
            assertEquals(refusal, MatchmakerRefusal(404, "no player with externalId 'sub-1'"))
        }
    }

    test("matchmaker failing is not a refusal: nothing was decided about the character") {
        withMatchmaker(500, """{"error":"internal error"}""") { (matchmaker, url, _) =>
            intercept[AwsError](matchmaker.registerCharacter(url, request))
        }
    }

    test("a player's characters are asked for by owner, encoded, and read from matchmaker's characters") {
        val listed =
            """[{"characterId":5,"gameId":3,"name":"Iron Mike","description":"d","state":"{}","playerId":7}]"""
        withMatchmaker(200, listed) { (matchmaker, url, seen) =>
            assertEquals(
              matchmaker.listCharacters(url, "sub 1+x"),
              List(Protocol.OwnedCharacter(5L, "Iron Mike", "d", "{}"))
            )
            val s = seen().get
            assertEquals((s.method, s.path, s.apiKey), ("GET", "/characters", Some("the-key")))
        }
    }

    test("an edit is put to the character, a transfer to its owner, and a character not the owner's is refused") {
        withMatchmaker(200, "{}") { (matchmaker, url, seen) =>
            val edit = Protocol.EditCharacterRequest("Kid Dynamite", "fast", "sub-1")
            matchmaker.editCharacter(url, 5L, edit)
            val s = seen().get
            assertEquals((s.method, s.path), ("PUT", "/characters/5"))
            assertEquals(read[Protocol.EditCharacterRequest](s.body), edit)
        }
        withMatchmaker(200, "{}") { (matchmaker, url, seen) =>
            val transfer = Protocol.TransferCharacterRequest("bob", "sub-1")
            matchmaker.transferCharacter(url, 5L, transfer)
            val s = seen().get
            assertEquals((s.method, s.path), ("PUT", "/characters/5/owner"))
            assertEquals(read[Protocol.TransferCharacterRequest](s.body), transfer)
        }
        withMatchmaker(404, """{"error":"no character with id 5"}""") { (matchmaker, url, _) =>
            val refusal = intercept[MatchmakerRefusal](
              matchmaker.editCharacter(url, 5L, Protocol.EditCharacterRequest("x", "", "sub-2"))
            )
            assertEquals(refusal.status, 404)
        }
    }
}
