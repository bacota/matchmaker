package com.vivi.matchmaker.api

import java.io.{InputStream, OutputStream}
import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
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

    val response =
      try {
        val request = ApiGateway.decodeRequest(event)
        Router
          .dispatch(services, request, Handler.authenticator)
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

  /** How the caller is identified. This becomes `Authenticator.GatewayClaims` once the API has a
    * Cognito JWT authorizer in front of it; until then the header is taken on trust, which is
    * only acceptable while nothing is publicly reachable.
    */
  val authenticator: Authenticator = Authenticator.TrustedHeader

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

  /** Fetches the credentials from the AWS Parameters and Secrets Lambda Extension, which serves
    * Secrets Manager over `localhost` and caches the result for the container's lifetime.
    *
    * This is why the AWS SDK is not a dependency: the SDK brings Netty and Apache HttpClient —
    * around 8 MB, and their initialization — for one call per cold start, where the JDK's own
    * HTTP client and a layer will do. Authorization still comes from the function's execution
    * role, so the `secretsmanager:GetSecretValue` grant in the terraform is still required.
    *
    * The secret is expected in the standard RDS shape, `{"username": ..., "password": ...}`.
    */
  private def credentials(secretName: String): (String, String) = {
    val port = sys.env.get("PARAMETERS_SECRETS_EXTENSION_HTTP_PORT").flatMap(_.toIntOption).getOrElse(2773)
    val uri = URI.create(
      s"http://localhost:$port/secretsmanager/get?secretId=${URLEncoder.encode(secretName, StandardCharsets.UTF_8)}"
    )

    val request = HttpRequest
      .newBuilder(uri)
      // The extension authenticates callers with the function's own session token, so that other
      // processes in the sandbox cannot read secrets through it.
      .header("X-Aws-Parameters-Secrets-Token", required("AWS_SESSION_TOKEN"))
      .GET()
      .build()

    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200)
      throw new IllegalStateException(
        s"secrets extension returned ${response.statusCode()} for '$secretName'; is the layer attached?"
      )

    // The extension mirrors the GetSecretValue response, so the secret itself is JSON in a string.
    val parsed = ujson.read(ujson.read(response.body())("SecretString").str)
    (parsed("username").str, parsed("password").str)
  }
}
