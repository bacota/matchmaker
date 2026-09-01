package com.vivi.matchmaker.engine

import cats.effect.IO
import com.vivi.matchmaker.auth.ApiKeys
import upickle.default.{read, write}
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import EngineJson.given

/** The real client: JSON over HTTPS, authenticated with the engine's API key.
  *
  * `java.net.http` rather than a library, because this module is deployed as a Lambda whose cold
  * start is proportional to the size of its jar, and the JDK's own client costs nothing to add.
  *
  * The key is looked up by the host of the url being called, which is all this client knows about
  * the engine it is talking to — see [[ApiKeys]]. A host with no key configured is called without
  * one, which is what makes a local stub engine work with no setup at all; a *deployed* engine
  * with no key would answer 401, so the missing key is reported here instead, where what is
  * actually missing can be said.
  *
  * `keys` is a function, and is called once per request rather than once per client, so that
  * rotating a key is a matter of changing the function's environment rather than rebuilding
  * everything that holds a client.
  */
class HttpGameEngineClient(
    keys: () => ApiKeys,
    timeout: Duration = Duration.ofSeconds(10),
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
) extends GameEngineClient {

  def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
    send("POST", gameUrl, Some(write(request))).map(parse[CreateGameResponse](gameUrl, _))

  def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] = {
    val url = withSince(statusUrl, since)
    send("GET", url, None).map(parse[GameStatusResponse](url, _))
  }

  /* `since` goes on the query string rather than in a body, because this is a GET and the engine
   * is free to ignore it: one that does not report turns answers the same either way. Appended
   * with the right separator, since the status url is the engine's own and may already carry a
   * query of its own. */
  private def withSince(statusUrl: String, since: Option[Instant]): String =
    since.fold(statusUrl) { at =>
      val separator = if (statusUrl.contains("?")) "&" else "?"
      val encoded = URLEncoder.encode(at.toString, StandardCharsets.UTF_8)
      s"$statusUrl${separator}since=$encoded"
    }

  private def parse[A: upickle.default.Reader](url: String, body: String): A =
    try read[A](body)
    catch {
      case e: Exception => throw GameEngineError(s"unreadable response from game engine at $url: ${e.getMessage}", e)
    }

  private def send(method: String, url: String, body: Option[String]): IO[String] =
    IO(URI.create(url)).flatMap { uri =>
      val host = Option(uri.getHost).getOrElse("")
      val key = keys().keyFor(host)

      val headers =
        body.map(_ => "content-type" -> "application/json").toMap ++ key.map(ApiKeys.Header -> _)

      val builder = HttpRequest.newBuilder(uri).timeout(timeout)
      headers.foreach((name, value) => builder.header(name, value))

      val request = body match {
        case Some(payload) => builder.method(method, HttpRequest.BodyPublishers.ofString(payload)).build()
        case None          => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
      }

      // A deployed engine refuses a keyless call with a 401 that names no cause, and sending one
      // is never going to work. The check is on the host rather than on the key in general
      // because a keyless call to a local stub engine is legitimate — that is the whole reason
      // the key is optional.
      val mustBeKeyed = host.endsWith(".amazonaws.com")

      IO.raiseWhen(mustBeKeyed && key.isEmpty)(
        GameEngineError(
          s"$method $url has no API key: nothing in GAME_ENGINE_API_KEYS is filed under '$host'. " +
            "A deployed game engine answers an unauthenticated request with 401."
        )
      ) *>
        IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
          .handleErrorWith(e => IO.raiseError(GameEngineError(s"$method $url failed: ${e.getMessage}", e)))
          .flatMap { response =>
            if (response.statusCode >= 200 && response.statusCode < 300) IO.pure(response.body)
            else
              // Whether a key was sent is the first thing anyone asks of a 401 or 403, and the
              // answer is not otherwise recoverable from the response. The key itself is of
              // course not logged.
              IO.raiseError(
                GameEngineError(
                  s"$method $url returned ${response.statusCode}: ${response.body} " +
                    s"(request was ${if (key.isEmpty) "unauthenticated" else s"sent with the API key for $host"})"
                )
              )
          }
    }
}

object HttpGameEngineClient {

  /** A client configured from the environment.
    *
    * `GAME_ENGINE_API_KEYS` is `host=key` per engine — see [[ApiKeys]]. It is re-read for every
    * request rather than parsed once, so that a rotated key takes effect without the execution
    * environment having to be recycled.
    */
  def fromEnvironment(env: String => Option[String] = k => Option(System.getenv(k))): HttpGameEngineClient =
    new HttpGameEngineClient(keys = () => ApiKeys.parse(env("GAME_ENGINE_API_KEYS")))
}
