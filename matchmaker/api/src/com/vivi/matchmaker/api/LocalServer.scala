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
  }

  private def authMode: String = env("AUTH_MODE").getOrElse("header")

  /** Local runs default to trusting the header, which is also all that exists today. When Cognito
    * lands, `AUTH_MODE=cognito` selects in-process token verification against the dev user pool's
    * public JWKS — see `Authenticator`.
    */
  private def authenticator: Authenticator = authMode match {
    case "header" => Authenticator.TrustedHeader
    case other =>
      throw new IllegalArgumentException(s"unknown AUTH_MODE '$other'; only 'header' exists so far")
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
