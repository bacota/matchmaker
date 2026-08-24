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
  *
  * `credentials` is a function, and is called once per request rather than once per client. The
  * execution role's credentials are temporary: the runtime rotates them before they expire by
  * rewriting the environment variables in place, so a copy read when the client was built goes
  * stale while the client itself stays alive. A signature made with expired credentials is not a
  * weaker signature, it is a rejected one — API Gateway answers 403 — and the execution
  * environment it happens in keeps answering 403 until it is recycled.
  */
class HttpGameEngineClient(
    credentials: () => Option[AwsCredentials],
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
      val signed = credentials() match {
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

      // An engine hosted on AWS is behind an AWS_IAM-authorized route, which answers an unsigned
      // request with a bare 403 that names no cause. Sending one is never going to work, so it is
      // refused here, where what is actually missing can be said. The check is on the host rather
      // than on signing in general because an unsigned call to a local stub engine is legitimate
      // — that is the whole reason signing is conditional.
      val mustBeSigned = Option(uri.getHost).exists(_.endsWith(".amazonaws.com"))

      IO.raiseWhen(mustBeSigned && signed.isEmpty)(
        GameEngineError(
          s"$method $url cannot be signed: no AWS credentials in the environment " +
            "(AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY). An AWS_IAM-authorized route answers an " +
            "unsigned request with 403 Forbidden."
        )
      ) *>
        IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
          .handleErrorWith(e => IO.raiseError(GameEngineError(s"$method $url failed: ${e.getMessage}", e)))
          .flatMap { response =>
            if (response.statusCode >= 200 && response.statusCode < 300) IO.pure(response.body)
            else
              // Whether the request was signed is the first thing anyone asks of a 403 from
              // API Gateway, and the answer is not otherwise recoverable from the response.
              IO.raiseError(
                GameEngineError(
                  s"$method $url returned ${response.statusCode}: ${response.body} " +
                    s"(request was ${if (signed.isEmpty) "unsigned" else s"signed as ${signed.keys.toList.sorted.mkString(", ")}"})"
                )
              )
          }
    }
}

object HttpGameEngineClient {

  /** A client configured from the environment: the execution role's credentials and the region
    * Lambda is running in, both of which the runtime sets. The region is fixed for the life of
    * the execution environment; the credentials are re-read for every request, since the runtime
    * rotates them in place.
    */
  def fromEnvironment(env: String => Option[String] = k => Option(System.getenv(k))): HttpGameEngineClient =
    new HttpGameEngineClient(
      credentials = () => AwsCredentials.fromEnvironment(env),
      region = env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
    )
}
