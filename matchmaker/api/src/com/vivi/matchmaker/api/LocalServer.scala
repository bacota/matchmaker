package com.vivi.matchmaker.api

import java.io.OutputStream
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service.{DbConfig, Services}
import ApiGateway.{Request, Response}

/** Runs the API on a local port, against a local Postgres, with no AWS involved.
  *
  * Everything below API Gateway is the deployed code: the same `Router`, the same services, the
  * same SQL. Only the two things API Gateway and Lambda provide are replaced — the transport, by
  * the JDK's own HTTP server, and the caller's identity, by the `Authenticator` chosen here.
  *
  * What it therefore does not check is the deployment itself: the handler string, the payload
  * format, cold-start behaviour. `RouterSpec` and `ApiGatewaySpec` cover the payload translation;
  * the rest only a real deploy will tell you about.
  *
  * {{{
  * mill matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer
  * curl localhost:8080/games -H 'X-External-Id: sub-1'
  * }}}
  */
object LocalServer {

  def main(args: Array[String]): Unit = {
    val port = env("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val poolSize = env("DB_POOL_SIZE").flatMap(_.toIntOption).getOrElse(Services.defaultPoolSize)
    val config = dbConfig()

    // Unlike the Lambda, this process has a shutdown, so the pool's finalizer is kept and run.
    val (services, release) = Services.resource[String](config, poolSize).allocated.unsafeRunSync()

    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/", new Dispatcher(services, authenticator))
    // The default executor runs requests one at a time on the accepting thread, which would make
    // a connection pool pointless. Sized to the pool, since that is the real limit.
    server.setExecutor(Executors.newFixedThreadPool(poolSize))

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop(0)
      release.unsafeRunSync()
    }))

    server.start()
    println(s"matchmaker listening on http://localhost:$port")
    println(s"  database  ${config.user}@${config.host}:${config.port}/${config.database}")
    println(s"  auth      $authMode")
    println(s"  cors      ${allowedOrigins.mkString(", ")}")
  }

  /** Origins the browser UI may call this from.
    *
    * The UI is served by a separate static server, so every call it makes is cross-origin. Only
    * loopback origins are allowed and only ones listed here: this process trusts an unverified
    * header for identity, so anything that can call it can be anyone, and a permissive `*` would
    * hand that to any page the developer happens to have open.
    */
  private lazy val allowedOrigins: Set[String] =
    env("LOCAL_CORS_ORIGINS")
      .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(Set("http://localhost:5173", "http://127.0.0.1:5173", s"http://localhost:$uiPort"))

  private def uiPort: Int = env("PORT").flatMap(_.toIntOption).getOrElse(8080)

  private def authMode: String = env("AUTH_MODE").getOrElse("header")

  /** Local runs trust the header. `gateway` is not offered here on purpose: it reads claims that
    * only API Gateway's authorizer can put there, so locally it would authenticate nobody.
    * Verifying a real dev-pool token in-process is `Authenticator.VerifiedToken`, which does not
    * exist yet.
    */
  private def authenticator: Authenticator = authMode match {
    case "header" => Authenticator.TrustedHeader
    case other =>
      throw new IllegalArgumentException(s"unknown AUTH_MODE '$other'; only 'header' works locally")
  }

  /** Credentials come straight from the environment here, where the Lambda reads them from
    * Secrets Manager. The defaults are the local development database the tests already use, so
    * that a checkout with Postgres running needs no configuration at all.
    */
  private def dbConfig(): DbConfig = DbConfig(
    host = env("DB_HOST").getOrElse("localhost"),
    port = env("DB_PORT").flatMap(_.toIntOption).getOrElse(5432),
    database = env("DB_NAME").getOrElse("matchmaker"),
    user = env("DB_USER").getOrElse("matchmaker"),
    password = Some(env("DB_PASSWORD").getOrElse("matchmaker"))
  )

  private def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)

  private class Dispatcher(services: Services[String], authenticator: Authenticator) extends HttpHandler {

    def handle(exchange: HttpExchange): Unit =
      try {
        val origin = Option(exchange.getRequestHeaders.getFirst("Origin")).filter(allowedOrigins.contains)
        origin.foreach(allowed => applyCorsHeaders(exchange, allowed))

        // The browser's preflight. It carries no body and must be answered without reaching the
        // router: it has no identity header, so it would come back 401 and the browser would
        // report only an opaque CORS failure.
        if (exchange.getRequestMethod.equalsIgnoreCase("OPTIONS")) {
          exchange.sendResponseHeaders(204, -1)
          return
        }

        val response =
          try
            Router
              .dispatch(services, requestOf(exchange), authenticator)
              .handleError { error =>
                // Router maps ServiceErrors itself, so anything arriving here is unexpected and
                // worth seeing in full on the console — this is a development server.
                error.printStackTrace()
                Errors.toResponse(error)
              }
              .unsafeRunSync()
          catch {
            case error: Throwable =>
              error.printStackTrace()
              Errors.response(500, "internal error")
          }

        send(exchange, response)
      } finally exchange.close()

    /** Echoes back the one origin that matched rather than `*`, which is both what a credentialed
      * request requires and a smaller thing to get wrong. Set before the status line, since the
      * JDK server writes headers when `sendResponseHeaders` is called.
      */
    private def applyCorsHeaders(exchange: HttpExchange, origin: String): Unit = {
      val headers = exchange.getResponseHeaders
      headers.set("Access-Control-Allow-Origin", origin)
      headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
      headers.set("Access-Control-Allow-Headers", "content-type, x-external-id, authorization")
      headers.set("Access-Control-Max-Age", "600")
      // Two different origins can get two different answers from this URL, so caches must not
      // serve one to the other.
      headers.set("Vary", "Origin")
    }

    private def requestOf(exchange: HttpExchange): Request = {
      val uri = exchange.getRequestURI

      // Lowercased to match API Gateway's payload v2, so that a lookup working in one place works
      // in the other. Multi-valued headers keep their first value, as ApiGateway.Request models.
      val headers = exchange.getRequestHeaders.asScala.flatMap { case (name, values) =>
        values.asScala.headOption.map(name.toLowerCase -> _)
      }.toMap

      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

      Request(exchange.getRequestMethod, uri.getPath, headers, queryOf(uri.getRawQuery), body)
    }

    private def queryOf(raw: String): Map[String, String] =
      Option(raw).filter(_.nonEmpty).fold(Map.empty[String, String]) { query =>
        query
          .split('&')
          .iterator
          .filter(_.nonEmpty)
          .map { pair =>
            val (name, value) = pair.span(_ != '=')
            decode(name) -> decode(value.drop(1))
          }
          .toMap
      }

    private def decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private def send(exchange: HttpExchange, response: Response): Unit = {
      val body = response.body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      // A 204 and an empty body must be declared as "no body" rather than a zero-length one,
      // which the JDK server reads as "chunked, length unknown".
      exchange.sendResponseHeaders(response.statusCode, if (body.isEmpty) -1 else body.length.toLong)
      if (body.nonEmpty) writeBody(exchange.getResponseBody, body)
    }

    private def writeBody(out: OutputStream, body: Array[Byte]): Unit =
      try out.write(body)
      finally out.close()
  }
}
