package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite
import org.scalajs.dom.URLSearchParams
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.{GameId, WebUrl}

/** Handing the session to a game engine's page by a one-time code in the url's fragment — `Auth.handOff`
  * (design/sign-in-handoff.md).
  *
  * Worth pinning down because both ways of getting it wrong are quiet: a credential put in a url, where the browser's
  * history keeps it, or a session left out when there was one to give, which only shows up as a player asked to sign in
  * twice. The engine's half — reading the code back out — is `SignInHandOffSpec` in engines.common, which reads the
  * same name.
  */
class HandOffSpec extends FunSuite {

    private val url = "https://engine.example/matches/m-1/play"
    private val game = GameId(7)

    /** A refresh that must not happen: the test fails if it is asked for. */
    private val noRefresh: () => Future[Option[String]] = () => Future.failed(new AssertionError("refreshed"))

    /** Makes codes as matchmaker would, keeping what it was asked for. */
    private class Codes(code: String = "c0de+/=") {
        var asked: List[Json.HandOffRequest] = Nil
        val create: Json.HandOffRequest => Future[Json.HandOff] = request => {
            asked = asked :+ request
            Future.successful(Json.HandOff(code = code, expiresAt = java.time.Instant.parse("2030-01-01T00:00:00Z")))
        }
    }

    /** A code that must not be asked for. */
    private val noCode: Json.HandOffRequest => Future[Json.HandOff] =
        _ => Future.failed(new AssertionError("a code was asked for"))

    /** The parameters in a handed url's fragment, read back the way the engine reads them. */
    private def handed(result: String): URLSearchParams = {
        assert(result.startsWith(url + "#"), result)
        new URLSearchParams(result.substring(url.length + 1))
    }

    test("a code is asked for with the game's id and the session, and only the code goes in the url") {
        val codes = Codes()
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = codes.create
        ).map { handedUrl =>
            assertEquals(
              codes.asked,
              List(Json.HandOffRequest(gameId = game, idToken = "id-1", refreshToken = Some("r-1")))
            )
            val params = handed(handedUrl)
            // Encoded, and read back exactly as it was made.
            assertEquals(params.get(Auth.HandedCode), "c0de+/=")
            assert(!handedUrl.contains("id-1"), handedUrl)
            assert(!handedUrl.contains("r-1"), handedUrl)
            assert(!handedUrl.contains("idToken"), handedUrl)
        }
    }

    test("the name is the one the engines read") {
        assertEquals(Auth.HandedCode, "handoff")
    }

    test("a url with a query keeps it, and the code goes after it") {
        val withQuery = url + "?as=alice"
        Auth.handOff(
          url = withQuery,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => None,
          fresh = noRefresh,
          create = Codes().create
        ).map(handedUrl => assert(handedUrl.startsWith(withQuery + "#handoff="), handedUrl))
    }

    test("a url that already has a fragment is left alone, and no code is made for it") {
        val own = url + "#section"
        Auth.handOff(
          url = own,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = noCode
        ).map(assertEquals(_, own))
    }

    test("a plain-http url is opened as it is, and no code is made for it") {
        val plain = "http://engine.example/matches/m-1/play"
        Auth.handOff(
          url = plain,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = noCode
        ).map(assertEquals(_, plain))
    }

    test("an http url to this machine, as local runs use, is handed a code") {
        val local = "http://localhost:8081/matches/m-1/play"
        Auth.handOff(
          url = local,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = Codes().create
        ).map(handedUrl => assert(handedUrl.startsWith(local + "#handoff="), handedUrl))
    }

    // `WebUrl.isSecure` ignores case with `toLowerCase` and a `(?i)` regex, which Scala.js compiles to JavaScript's
    // own: this pins it down in the browser build, where the server's `WebUrlSpec` does not run.
    test("the scheme and host are matched whatever their case, as on the server") {
        val upper = "HTTP://LOCALHOST:8081/matches/m-1/play"
        Auth.handOff(
          url = upper,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = Codes().create
        ).map { handedUrl =>
            assert(handedUrl.startsWith(upper + "#handoff="), handedUrl)
            assert(WebUrl.isSecure("HTTPS://engine.example/"))
            assert(!WebUrl.isSecure("HTTP://ENGINE.EXAMPLE/"))
            assert(!WebUrl.isSecure("HTTP://LOCALHOST.EVIL.EXAMPLE/"))
        }
    }

    // Also what a refresh Cognito refused ends with: `freshIdToken` has cleared the session by then.
    test("with no session at all, the url is opened as it is") {
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = true,
          current = None,
          refresh = () => None,
          fresh = () => Future.successful(None),
          create = noCode
        ).map(assertEquals(_, url))
    }

    test("an expired ID token is refreshed first, and the new one handed over") {
        var stored: Option[String] = Some("r-old")
        val refreshing = () => { stored = Some("r-new"); Future.successful(Some("id-new")) }
        val codes = Codes()
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = true,
          current = None,
          refresh = () => stored,
          fresh = refreshing,
          create = codes.create
        ).map { _ =>
            // Read after the refresh, so a token it stored is the one handed over.
            assertEquals(codes.asked.map(r => (r.idToken, r.refreshToken)), List(("id-new", Some("r-new"))))
        }
    }

    test("a refresh that fails opens the url without a session rather than not at all") {
        val failing = () => Future.failed[Option[String]](new RuntimeException("Cognito unreachable"))
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = true,
          current = None,
          refresh = () => Some("r-1"),
          fresh = failing,
          create = noCode
        ).map(assertEquals(_, url))
    }

    test("a code that cannot be made opens the url as it is") {
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = _ => Future.failed(ApiError(503, "unavailable"))
        ).map(assertEquals(_, url))
    }

    test("an untrusted game's code is asked for with the ID token alone") {
        val codes = Codes()
        Auth.handOff(
          url = url,
          gameId = game,
          isTrusted = false,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh,
          create = codes.create
        ).map(_ => assertEquals(codes.asked.map(_.refreshToken), List(None)))
    }
}
