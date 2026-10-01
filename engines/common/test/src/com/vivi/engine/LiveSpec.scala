package com.vivi.engine

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue, TimeUnit}
import munit.FunSuite

/** Play Live's own parts: the pushing, the connection events as Lambda delivers them, the deployed configuration, and
  * the local WebSocket server spoken to by a real WebSocket client. What the routes admit and push is
  * `RoutesContract`'s, for every engine.
  */
class LiveSpec extends FunSuite {

    private class Channel(gone: Set[String] = Set.empty, failing: Set[String] = Set.empty) extends LiveChannel {
        val sent = scala.collection.mutable.ListBuffer[String]()
        def send(connectionId: String, message: String): Boolean =
            if (failing(connectionId)) throw AwsError("the management api is down")
            else if (gone(connectionId)) false
            else {
                sent += connectionId
                true
            }
    }

    test("a change is pushed to the match's watchers and nobody else's, and a gone connection is forgotten") {
        val subscriptions = InMemorySubscriptions()
        val channel = Channel(gone = Set("c-gone"))
        val live = Live("ws://x", PlayAuth.Trusted, subscriptions, channel)
        List("c-1" -> "m-1", "c-gone" -> "m-1", "c-other" -> "m-2").foreach((c, m) =>
            live.subscribe(Subscription(c, m))
        )

        live.changed("m-1")
        assertEquals(channel.sent.toList, List("c-1"))
        assertEquals(subscriptions.watching("m-1").map(_.connectionId), List("c-1"))
        assertEquals(subscriptions.watching("m-2").map(_.connectionId), List("c-other"))
    }

    /* The move a push reports is already committed; a failure to report it must not become a 500 for
     * a move that stands, nor stop the other watchers hearing of it. */
    test("a push that fails is logged, and neither fails the caller nor stops the others") {
        val channel = Channel(failing = Set("c-1"))
        val live = Live("ws://x", PlayAuth.Trusted, InMemorySubscriptions(), channel)
        live.subscribe(Subscription("c-1", "m-1"))
        live.subscribe(Subscription("c-2", "m-1"))

        live.changed("m-1")
        assertEquals(channel.sent.toList, List("c-2"))
    }

    /* Pushed inside the request that made the move, so a stalled connection must not hold that
     * request: its answer would otherwise be a timeout for a move that stands. */
    test("a push that never answers is abandoned at the deadline, and the others are still sent") {
        val delivered = java.util.concurrent.ConcurrentLinkedQueue[String]()
        val stalled = new LiveChannel {
            def send(connectionId: String, message: String): Boolean = {
                if (connectionId == "c-stalled") Thread.sleep(60000)
                delivered.add(connectionId)
                true
            }
        }
        val live = Live("ws://x", PlayAuth.Trusted, InMemorySubscriptions(), stalled, java.time.Duration.ofMillis(300))
        (1 to 20).foreach(i => live.subscribe(Subscription(s"c-$i", "m-1")))
        live.subscribe(Subscription("c-stalled", "m-1"))

        val started = System.nanoTime()
        live.changed("m-1")
        val took = java.time.Duration.ofNanos(System.nanoTime() - started)

        assert(took.toMillis < 2000, s"took $took")
        assertEquals(delivered.size, 20)
        assert(!delivered.contains("c-stalled"))
    }

    test("a WebSocket API's connection events decode to the requests the local server builds") {
        def event(eventType: String, query: ujson.Value = ujson.Null) =
            ujson.Obj(
              "requestContext" -> ujson.Obj(
                "routeKey" -> s"$$${eventType.toLowerCase}",
                "eventType" -> eventType,
                "connectionId" -> "L0SM9cOFvHcCIhw="
              ),
              "queryStringParameters" -> query,
              "isBase64Encoded" -> false
            )

        val connect = LambdaEvent.decode(ujson.write(event("CONNECT", ujson.Obj("match" -> "m-9", "token" -> "t"))))
        assertEquals(connect.method, "CONNECT")
        assertEquals(connect.path, "/live")
        assertEquals(connect.query, Map("match" -> "m-9", "token" -> "t"))
        assertEquals(connect.connectionId, Some("L0SM9cOFvHcCIhw="))

        val disconnect = LambdaEvent.decode(ujson.write(event("DISCONNECT")))
        assertEquals((disconnect.method, disconnect.connectionId), ("DISCONNECT", Some("L0SM9cOFvHcCIhw=")))
    }

    test("an HTTP event has no connection id, whatever it says") {
        val event =
            ujson.Obj("rawPath" -> "/live", "requestContext" -> ujson.Obj("http" -> ujson.Obj("method" -> "GET")))
        assertEquals(LambdaEvent.decode(ujson.write(event)).connectionId, None)
    }

    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    private val deployed = Map(
      "AWS_LAMBDA_FUNCTION_NAME" -> "rps-dev",
      "COGNITO_ISSUER" -> "https://cognito-idp.us-east-1.amazonaws.com/pool",
      "COGNITO_CLIENT_ID" -> "client-1",
      "HOSTED_LOGIN_URL" -> "https://login.test",
      "LIVE_URL" -> "wss://live.test/live",
      "LIVE_ENDPOINT" -> "https://live.test/live",
      "LIVE_TABLE" -> "rps-dev-connections"
    )

    test("Play Live is configured by LIVE_URL, and not offered without it") {
        assertEquals(EngineConfig.live(deployed.get, "https://engine.test").map(_.url), Some("wss://live.test/live"))
        assertEquals(EngineConfig.live((deployed - "LIVE_URL").get, "https://engine.test"), None)
        intercept[IllegalStateException](EngineConfig.live((deployed - "LIVE_TABLE").get, "https://engine.test"))
    }

    /* There is no JWT authorizer in front of a WebSocket connection, so trusting the claims there
     * would trust an empty set of them. The token is verified by the function instead. */
    test("deployed, a connection's token is verified by the engine rather than read from claims") {
        assert(EngineConfig.playAuth(deployed.get, "https://engine.test").isInstanceOf[PlayAuth.GatewayClaims])
        assert(EngineConfig.liveAuth(deployed.get, "https://engine.test").isInstanceOf[PlayAuth.VerifiedToken])
        // Locally the play routes' own auth already does its own checking, and is used as it is.
        assertEquals(EngineConfig.liveAuth(Map.empty[String, String].get, "http://localhost"), PlayAuth.Trusted)
    }

    // ---------------------------------------------------------------------------
    // The local server, over a real socket
    // ---------------------------------------------------------------------------

    private class Listener extends WebSocket.Listener {
        val messages = LinkedBlockingQueue[String]()
        override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
            messages.put(data.toString)
            ws.request(1)
            null
        }
    }

    private def withServer(test: (LocalLiveServer, LinkedBlockingQueue[EngineRequest]) => Unit): Unit = {
        val server = LocalLiveServer(0)
        val seen = LinkedBlockingQueue[EngineRequest]()
        server.serve { request =>
            seen.put(request)
            if (request.method == "CONNECT" && request.query.get("as").contains("sub-alice")) EngineResponse(200, "{}")
            else if (request.method == "CONNECT") EngineResponse(403, """{"error":"not yours"}""")
            else EngineResponse(200, "{}")
        }
        try test(server, seen)
        finally server.close()
    }

    private def open(server: LocalLiveServer, query: String, listener: WebSocket.Listener = Listener()) =
        HttpClient
            .newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create(s"${server.url}?$query"), listener)
            .get(5, TimeUnit.SECONDS)

    test("the local server admits a connection the routes admit, and pushes down it") {
        withServer { (server, seen) =>
            val listener = Listener()
            val ws = open(server, "match=m-9&as=sub-alice", listener)

            val connect = seen.poll(5, TimeUnit.SECONDS)
            assertEquals(connect.method, "CONNECT")
            assertEquals(connect.query, Map("match" -> "m-9", "as" -> "sub-alice"))
            val id = connect.connectionId.get

            assert(server.send(id, """{"changed":"m-9"}"""))
            assertEquals(listener.messages.poll(5, TimeUnit.SECONDS), """{"changed":"m-9"}""")

            // The page's keep-alive is read and ignored, and the connection stays up for it.
            ws.sendText("""{"action":"ping"}""", true).get(5, TimeUnit.SECONDS)
            assert(server.send(id, "again"))
            assertEquals(listener.messages.poll(5, TimeUnit.SECONDS), "again")

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
            val disconnect = seen.poll(5, TimeUnit.SECONDS)
            assertEquals((disconnect.method, disconnect.connectionId), ("DISCONNECT", Some(id)))
            assert(!server.send(id, "after"), "a closed connection is reported gone")
        }
    }

    test("the local server refuses a connection the routes refuse") {
        withServer { (server, seen) =>
            val refused = intercept[java.util.concurrent.ExecutionException](open(server, "match=m-9&as=sub-carol"))
            assert(refused.getCause.isInstanceOf[java.net.http.WebSocketHandshakeException], refused.getCause.toString)
            assertEquals(seen.poll(5, TimeUnit.SECONDS).method, "CONNECT")
            // Never admitted, so never disconnected either.
            assertEquals(seen.poll(200, TimeUnit.MILLISECONDS), null)
        }
    }

    test("a large push is framed with an extended length") {
        withServer { (server, seen) =>
            val listener = Listener()
            open(server, "as=sub-alice", listener)
            val id = seen.poll(5, TimeUnit.SECONDS).connectionId.get
            val big = "x" * 70000
            assert(server.send(id, big))
            // The JDK client may hand a large message over in parts.
            val received = StringBuilder()
            while (received.length < big.length) received ++= listener.messages.poll(5, TimeUnit.SECONDS)
            assertEquals(received.toString, big)
        }
    }

    test("the accept key is RFC 6455's worked example") {
        assertEquals(LocalLiveServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
    }
}
