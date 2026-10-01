package com.vivi.engine

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

/** Runs an engine on a local port, with matches in memory and no AWS involved.
  *
  * The point of it is the loop it closes: matchmaker's own `LocalServer` on one port, an engine on another, a `game`
  * row whose `url` is the engine's `/games` and whose `external_id` is what `GAME_EXTERNAL_ID` is set to here, and all
  * four exchanges of `interaction-design.txt` happen for real over HTTP — with both sides' authentication in their
  * local mode. Each engine's own `LocalServer` is the entry point, and says how to start it.
  */
object LocalEngineServer {

    /** Serves `routes` on `PORT`, or `defaultPort`, until the process is stopped.
      *
      * @param title
      *   what the startup banner calls the engine
      * @param defaultPort
      *   each engine's own, so that all of them can run against one matchmaker at once — which is the only way to see
      *   several games in one player's lists
      * @param routes
      *   the engine's routes, given the base url the outside world reaches it on
      */
    def run(title: String, defaultPort: Int)(routes: String => EngineRequest => EngineResponse): Unit = {
        val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(defaultPort)
        val baseUrl = sys.env.getOrElse("BASE_URL", s"http://localhost:$port").stripSuffix("/")
        serve(port, baseUrl, title, routes(baseUrl))
    }

    private def serve(port: Int, baseUrl: String, title: String, routes: EngineRequest => EngineResponse): Unit = {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/", Dispatcher(routes))
        server.setExecutor(Executors.newFixedThreadPool(4))
        server.start()

        println(s"$title engine listening on http://localhost:$port")
        println(s"  create    POST http://localhost:$port/games")
        println(s"  callbacks ${
                if (sys.env.get("MATCHMAKER_OFFLINE").contains("true")) "printed here (offline)"
                else "posted to the urls matchmaker sends"
            }")
        println(
          s"  identity  ${sys.env.getOrElse("GAME_EXTERNAL_ID", "<GAME_EXTERNAL_ID not set: matchmaker will refuse the callbacks>")}"
        )

        EngineConfig.playAuth(sys.env.get, baseUrl) match {
            case _: PlayAuth.VerifiedToken =>
                println(
                  s"  players   sign in against ${sys.env.getOrElse("HOSTED_LOGIN_URL", "?")}, tokens verified against ${sys.env
                          .getOrElse("COGNITO_ISSUER", "?")}"
                )
            case PlayAuth.Trusted =>
                println("  players   TRUSTED: anyone may play any seat by naming it (?as=<cognito sub>).")
                println(
                  "            Set COGNITO_ISSUER, COGNITO_CLIENT_ID and HOSTED_LOGIN_URL to use the real sign-in."
                )
            case _ =>
                println("  players   claims from an API Gateway JWT authorizer, which is not in front of this process")
        }
    }

    private class Dispatcher(routes: EngineRequest => EngineResponse) extends HttpHandler {
        def handle(exchange: HttpExchange): Unit =
            try {
                val uri = exchange.getRequestURI
                val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
                val headers = exchange.getRequestHeaders.asScala.view
                    .map((name, values) => name.toLowerCase -> values.asScala.mkString(","))
                    .toMap

                val response = routes(
                  EngineRequest(exchange.getRequestMethod, uri.getPath, queryOf(Option(uri.getRawQuery)), body, headers)
                )

                val bytes = response.body.getBytes(UTF_8)
                exchange.getResponseHeaders.add("content-type", response.contentType)
                exchange.sendResponseHeaders(response.status, bytes.length.toLong)
                exchange.getResponseBody.write(bytes)
            } catch {
                case e: Throwable =>
                    Log.failure(e, s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath}")
                    val bytes = s"""{"error":"${e.getClass.getSimpleName}"}""".getBytes(UTF_8)
                    exchange.sendResponseHeaders(500, bytes.length.toLong)
                    exchange.getResponseBody.write(bytes)
            } finally exchange.close()

        private def queryOf(raw: Option[String]): Map[String, String] =
            raw.filter(_.nonEmpty)
                .toList
                .flatMap(_.split("&"))
                .flatMap(_.split("=", 2) match {
                    case Array(k, v) => Some(URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v, UTF_8))
                    case Array(k)    => Some(URLDecoder.decode(k, UTF_8) -> "")
                    case _           => None
                })
                .toMap
    }
}
