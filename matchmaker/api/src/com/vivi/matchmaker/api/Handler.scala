package com.vivi.matchmaker.api

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service.{DbConfig, Services}
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

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

    val response =
      try {
        val request = ApiGateway.decodeRequest(event)
        Router
          .dispatch(services, request)
          .handleError { error =>
            // Router maps ServiceErrors itself; reaching here means something unexpected, so
            // the detail goes to CloudWatch and only a generic message goes to the caller.
            Option(context).foreach(_.getLogger.log(s"unhandled error: $error"))
            Errors.toResponse(error)
          }
          .unsafeRunSync()
      } catch {
        case error: Throwable =>
          Option(context).foreach(_.getLogger.log(s"could not handle event: $error"))
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

  private def poolSize: Int =
    sys.env.get("DB_POOL_SIZE").flatMap(_.toIntOption).getOrElse(Services.defaultPoolSize)

  private def required(name: String): String =
    sys.env.getOrElse(name, throw new IllegalStateException(s"$name is not set"))

  /** Assembles the database configuration from environment variables, with the credentials read
    * from the Secrets Manager secret named by `DB_SECRET_NAME` so they never appear in the
    * function's environment.
    */
  private def dbConfig(): DbConfig = {
    val (user, password) = credentials(required("DB_SECRET_NAME"))
    DbConfig(
      host = required("DB_HOST"),
      port = sys.env.get("DB_PORT").flatMap(_.toIntOption).getOrElse(5432),
      database = required("DB_NAME"),
      user = user,
      password = Some(password)
    )
  }

  /** Reads the standard RDS secret shape, `{"username": ..., "password": ...}`. */
  private def credentials(secretName: String): (String, String) = {
    val client = SecretsManagerClient.create()
    try {
      val secret = client.getSecretValue(GetSecretValueRequest.builder().secretId(secretName).build()).secretString()
      val parsed = ujson.read(secret)
      (parsed("username").str, parsed("password").str)
    } finally client.close()
  }
}
