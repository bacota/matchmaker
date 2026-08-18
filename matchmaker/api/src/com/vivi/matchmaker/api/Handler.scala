package com.vivi.matchmaker.api

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service.{DbConfig, Services}

/** Lambda entry point, behind an API Gateway HTTP API using payload format 2.0.
  *
  * Handler string: `com.vivi.matchmaker.api.Handler::handleRequest`.
  */
class Handler extends RequestStreamHandler {

  // Initialized once per container and reused across invocations, which is the whole point of
  // pooling: a Lambda handles one request at a time, but the same container serves many.
  private lazy val services = Handler.services

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val event = String(input.readAllBytes(), StandardCharsets.UTF_8)
    // Falls back to stderr when there is no context (local runs, and any invocation the runtime
    // hands a null one), so a request is never handled with its outcome going nowhere.
    val log = (msg: String) =>
      Option(context) match {
        case Some(c) => c.getLogger.log(msg)
        case None    => System.err.println(msg)
      }

    // The request, if it could be decoded — so the catch below can still name what failed.
    var where = "undecoded event"

    val response =
      try {
        val request = ApiGateway.decodeRequest(event)
        where = s"${request.method} ${request.path}"
        val result = Router
          .dispatch(services, request, Handler.authenticator)
          .handleError { error =>
            // Router maps ServiceErrors itself; reaching here means something unexpected, so
            // the detail goes to CloudWatch and only a generic message goes to the caller.
            Errors.toResponse(error, where)
          }
          .unsafeRunSync()
        log(s"handled $where -> ${result.statusCode}")
        result
      } catch {
        // Anything that escaped the IO: a decode failure, a fatal error, a container whose
        // initialization failed. Logged with its stack trace, because a 5xx the caller cannot
        // see the reason for has to be readable here.
        case error: Throwable =>
          Errors.log(error, where)
          log(s"handled $where -> 500")
          Errors.response(500, "internal error")
      }

    output.write(ApiGateway.encodeResponse(response).getBytes(StandardCharsets.UTF_8))
    output.flush()
  }
}

object Handler {

  /** Built once per container. The pool's finalizer is deliberately dropped: the pool should
    * live exactly as long as the container, and there is no shutdown hook that could run it at
    * a useful moment anyway.
    */
  lazy val services: Services[String] =
    Services.resource[String](dbConfig(), poolSize).allocated.unsafeRunSync()._1

  /** How the caller is identified, chosen by `AUTH_MODE`.
    *
    * The terraform sets this to `gateway`, so a deployed function trusts only what an authorizer
    * in front of it established: a claim from the Cognito JWT authorizer on the player routes, or
    * the signing principal on the `AWS_IAM` routes the game engine calls back on. The default is deliberately the *other* way round: an unset variable
    * means no infrastructure was involved, and a function that fell back to trusting a header
    * would turn a terraform mistake into an open API. Failing loudly is the safe default here.
    */
  val authenticator: Authenticator = sys.env.getOrElse("AUTH_MODE", "gateway") match {
    case "gateway" => Authenticator.Gateway
    case "header"  => Authenticator.TrustedHeader
    case other     => throw new IllegalStateException(s"unknown AUTH_MODE '$other'; expected 'gateway' or 'header'")
  }

  private def poolSize: Int =
    sys.env.get("DB_POOL_SIZE").flatMap(_.toIntOption).getOrElse(Services.defaultPoolSize)

  private def required(name: String): String =
    sys.env.getOrElse(name, throw new IllegalStateException(s"$name is not set"))

  /** Assembles the database configuration from the function's environment variables.
    *
    * The credentials arrive the same way as the host and database name. That keeps the function
    * free of any AWS dependency — no SDK, no extension layer, no network call before the first
    * query — at the cost of the password being readable from the function's configuration by
    * anyone holding `lambda:GetFunction`.
    */
  private def dbConfig(): DbConfig =
    DbConfig(
      host = required("DB_HOST"),
      port = sys.env.get("DB_PORT").flatMap(_.toIntOption).getOrElse(5432),
      database = required("DB_NAME"),
      user = required("DB_USER"),
      password = Some(required("DB_PASSWORD"))
    )
}
