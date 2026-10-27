package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success
import munit.FunSuite
import org.scalajs.dom.URLSearchParams

/** Handing the session to a game engine's page in the url fragment — `Auth.handOff`.
  *
  * Worth pinning down because both ways of getting it wrong are quiet: a credential put where it should not be (in a
  * url that already has a fragment of its own, or mangled by encoding so that the engine stores something else), or a
  * session left out when there was one to give, which only shows up as a player asked to sign in twice. The engine's
  * half — reading it back out — is `SignInHandOffSpec` in engines.common, which reads the same two names.
  */
class HandOffSpec extends FunSuite {

    private val url = "https://engine.example/matches/m-1/play"

    /** A refresh that must not happen: the test fails if it is asked for. */
    private val noRefresh: () => Future[Option[String]] = () => Future.failed(new AssertionError("refreshed"))

    /** The parameters in a handed url's fragment, read back the way the engine reads them. */
    private def handed(result: String): URLSearchParams = {
        assert(result.startsWith(url + "#"), result)
        new URLSearchParams(result.substring(url.length + 1))
    }

    test("a good ID token is handed over with the refresh token, and needs no wait") {
        val result = Auth.handOff(
          url = url,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh
        )
        // Completed already, which is what lets the caller open the window inside the click.
        result.value match {
            case Some(Success(handedUrl)) =>
                val params = handed(handedUrl)
                assertEquals(params.get(Auth.HandedIdToken), "id-1")
                assertEquals(params.get(Auth.HandedRefreshToken), "r-1")
            case other => fail(s"not completed at once: $other")
        }
    }

    test("the names are the ones the engines read") {
        assertEquals(Auth.HandedIdToken, "idToken")
        assertEquals(Auth.HandedRefreshToken, "refreshToken")
    }

    // A refresh token is opaque and may hold anything base64 can: `+` read back as a space, or an
    // unescaped `&` or `=`, would hand over a different token from the one held.
    test("tokens are encoded so the engine reads back exactly what was held") {
        val id = "eyJ+a/b=.c_d-e"
        val refresh = "x&y=z+w/v==%20 q"
        val result = Auth.handOff(
          url = url,
          isTrusted = true,
          current = Some(id),
          refresh = () => Some(refresh),
          fresh = noRefresh
        )
        result.map { handedUrl =>
            val params = handed(handedUrl)
            assertEquals(params.get(Auth.HandedIdToken), id)
            assertEquals(params.get(Auth.HandedRefreshToken), refresh)
            assert(!handedUrl.contains(" "), handedUrl)
        }
    }

    test("a url with a query keeps it, and the session goes after it") {
        val withQuery = url + "?as=alice"
        Auth.handOff(url = withQuery, isTrusted = true, current = Some("id-1"), refresh = () => None, fresh = noRefresh)
            .map { handedUrl =>
                assert(handedUrl.startsWith(withQuery + "#"), handedUrl)
            }
    }

    test("a url that already has a fragment is left alone, and nothing is refreshed for it") {
        val own = url + "#section"
        Auth.handOff(
          url = own,
          isTrusted = true,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh
        ).map(assertEquals(_, own))
    }

    // Also what a refresh Cognito refused ends with: `freshIdToken` has cleared the session by then.
    test("with no session at all, the url is opened as it is") {
        Auth.handOff(
          url = url,
          isTrusted = true,
          current = None,
          refresh = () => None,
          fresh = () => Future.successful(None)
        ).map(assertEquals(_, url))
    }

    test("an expired ID token is refreshed first, and the new one handed over") {
        var stored: Option[String] = Some("r-old")
        val refreshing = () => { stored = Some("r-new"); Future.successful(Some("id-new")) }
        Auth.handOff(url = url, isTrusted = true, current = None, refresh = () => stored, fresh = refreshing).map {
            handedUrl =>
                val params = handed(handedUrl)
                assertEquals(params.get(Auth.HandedIdToken), "id-new")
                // Read after the refresh, so a token it stored is the one handed over.
                assertEquals(params.get(Auth.HandedRefreshToken), "r-new")
        }
    }

    test("a refresh that fails opens the url without a session rather than not at all") {
        val failing = () => Future.failed[Option[String]](new RuntimeException("Cognito unreachable"))
        Auth.handOff(url = url, isTrusted = true, current = None, refresh = () => Some("r-1"), fresh = failing)
            .map(assertEquals(_, url))
    }

    test("an untrusted game's engine is handed the ID token alone") {
        Auth.handOff(
          url = url,
          isTrusted = false,
          current = Some("id-1"),
          refresh = () => Some("r-1"),
          fresh = noRefresh
        ).map { handedUrl =>
            val params = handed(handedUrl)
            assertEquals(params.get(Auth.HandedIdToken), "id-1")
            assertEquals(params.get(Auth.HandedRefreshToken), null)
        }
    }

    test("an untrusted game's engine is handed only the new ID token after a refresh") {
        val refreshing = () => Future.successful(Some("id-new"))
        Auth.handOff(url = url, isTrusted = false, current = None, refresh = () => Some("r-1"), fresh = refreshing)
            .map { handedUrl =>
                val params = handed(handedUrl)
                assertEquals(params.get(Auth.HandedIdToken), "id-new")
                assertEquals(params.get(Auth.HandedRefreshToken), null)
            }
    }

    test("with no refresh token, only the ID token is handed over") {
        Auth.handOff(url = url, isTrusted = true, current = Some("id-1"), refresh = () => None, fresh = noRefresh).map {
            handedUrl =>
                val params = handed(handedUrl)
                assertEquals(params.get(Auth.HandedIdToken), "id-1")
                assertEquals(params.get(Auth.HandedRefreshToken), null)
        }
    }
}
