package com.vivi.matchmaker.engine

import cats.effect.IO
import upickle.default.{read, write}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import EngineJson.given

/** The real client: JSON over HTTPS, signed with SigV4 when credentials are available.
  *
  * `java.net.http` rather than a library, for the same reason `SigV4` is hand-rolled — this
  * module is deployed as a Lambda whose cold start is proportional to the size of its jar, and
  * the JDK's own client costs nothing to add.
  *
  * Signing is conditional on credentials being present, which on Lambda they always are. The
  * conditional exists for running against a game engine that is not behind `AWS_IAM` (a local
  * stub during development, say), where there is nothing to sign with and nothing that would
  * check a signature. It is not a fallback that could silently weaken a production call: outside
  * that case the credentials come from the execution role and are always set, and an unsigned
  * request to an IAM-authorized route is rejected by API Gateway rather than quietly allowed.
  */
class HttpGameEngineClient(
    credentials: Option[AwsCredentials],
    region: String,
    timeout: Duration = Duration.ofSeconds(10),
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
) extends GameEngineClient {

  def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
    send("POST", gameUrl, Some(write(request))).map(parse[CreateGameResponse](gameUrl, _))

  def status(statusUrl: String): IO[GameStatusResponse] =
    send("GET", statusUrl, None).map(parse[GameStatusResponse](statusUrl, _))

  private def parse[A: upickle.default.Reader](url: String, body: String): A =
    try read[A](body)
    catch {
      case e: Exception => throw GameEngineError(s"unreadable response from game engine at $url: ${e.getMessage}", e)
    }

  private def send(method: String, url: String, body: Option[String]): IO[String] =
    IO(URI.create(url)).flatMap { uri =>
      val payload = body.getOrElse("")
      val contentHeaders = body.map(_ => Map("content-type" -> "application/json")).getOrElse(Map.empty)

      // Every header the signature covers has to go onto the request exactly as it was signed,
      // so the signed set and the sent set are built from the same map.
      val signed = credentials match {
        case Some(creds) => SigV4.sign(method, uri, contentHeaders, payload, creds, region)
        case None        => Map.empty[String, String]
      }

      val builder = HttpRequest.newBuilder(uri).timeout(timeout)
      (contentHeaders ++ signed)
        // Host is set by the JDK client itself and is restricted — it may not be set by hand,
        // and its value is the one already signed above.
        .filterNot((name, _) => name.equalsIgnoreCase("host"))
        .foreach((name, value) => builder.header(name, value))

      val request = body match {
        case Some(payload) => builder.method(method, HttpRequest.BodyPublishers.ofString(payload)).build()
        case None          => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
      }

      IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
        .handleErrorWith(e => IO.raiseError(GameEngineError(s"$method $url failed: ${e.getMessage}", e)))
        .flatMap { response =>
          if (response.statusCode >= 200 && response.statusCode < 300) IO.pure(response.body)
          else IO.raiseError(GameEngineError(s"$method $url returned ${response.statusCode}: ${response.body}"))
        }
    }
}

object HttpGameEngineClient {

  /** A client configured from the environment: the execution role's credentials and the region
    * Lambda is running in, both of which the runtime sets.
    */
  def fromEnvironment(env: String => Option[String] = k => Option(System.getenv(k))): HttpGameEngineClient =
    new HttpGameEngineClient(
      credentials = AwsCredentials.fromEnvironment(env),
      region = env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
    )
}
