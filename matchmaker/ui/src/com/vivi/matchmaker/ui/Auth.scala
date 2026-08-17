package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success, Try}
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit, URLSearchParams}

/** Cognito hosted login, authorization code grant with PKCE.
  *
  * The password is typed into Cognito's own pages, never into this application — which is the
  * reason to use hosted login rather than a form here. What comes back is an ID token, which
  * every API call carries and which API Gateway's JWT authorizer verifies.
  *
  * The tokens are kept in `sessionStorage`, not `localStorage`: they are cleared when the tab
  * closes, and are not shared with other tabs. Neither is proof against XSS — script running on
  * this origin can read either — so the protections that remain are that this page loads no
  * third-party script and that the session dies with the tab.
  *
  * The refresh token is stored alongside the ID token so a session outlives the ID token's hour
  * (see `freshIdToken`). That is a deliberate trade: it lengthens what a successful XSS could do
  * with what it steals, in exchange for not signing players out mid-game.
  */
object Auth {

  private val TokenKey = "matchmaker.idToken"
  private val RefreshKey = "matchmaker.refreshToken"
  private val VerifierKey = "matchmaker.pkceVerifier"
  private val StateKey = "matchmaker.authState"

  /** The current ID token, if there is one that has not expired.
    *
    * Expiry is checked here as well as by the gateway, so that the UI shows a sign-in button
    * rather than firing a request that is certain to come back 401.
    */
  def idToken: Option[String] =
    Option(dom.window.sessionStorage.getItem(TokenKey)).filter(unexpired)

  /** A session exists when there is a usable ID token, or a refresh token that can obtain one.
    * Used for rendering; `freshIdToken` is what actually establishes whether the refresh works.
    */
  def isSignedIn: Boolean = idToken.isDefined || refreshToken.isDefined

  private def refreshToken: Option[String] =
    Option(dom.window.sessionStorage.getItem(RefreshKey)).filter(_.nonEmpty)

  /** At most one refresh is in flight: several requests hitting an expired token at once must
    * redeem the refresh token once between them, not race to redeem it each.
    */
  private var refreshing: Option[Future[Option[String]]] = None

  /** An ID token that is good to send, refreshing first if the current one has run out.
    *
    * `None` means there is no session left — either nothing was stored, or the refresh token was
    * rejected, in which case the stored session has already been cleared. A failed future means
    * the refresh could not be attempted (Cognito unreachable, a 5xx); the session survives and
    * the caller's request fails instead.
    */
  def freshIdToken(): Future[Option[String]] =
    idToken match {
      case Some(token) => Future.successful(Some(token))
      case None =>
        refreshToken match {
          case None => Future.successful(None)
          case Some(token) =>
            refreshing.getOrElse {
              val attempt = refresh(token)
              refreshing = Some(attempt)
              attempt.onComplete(_ => refreshing = None)
              attempt
            }
        }
    }

  /** Exchanges the refresh token for a new ID token.
    *
    * `None`, with the stored session cleared, means the session is genuinely over (see
    * `endsSession`). A failed future means this attempt did not get through and the session is
    * still intact. Cognito does not normally return a new refresh token here; one is stored if
    * it does.
    */
  private def refresh(token: String): Future[Option[String]] = {
    val form = new URLSearchParams()
    form.set("grant_type", "refresh_token")
    form.set("client_id", Config.current.clientId)
    form.set("refresh_token", token)

    postToken(form)
      .flatMap { body =>
        // A 200 carrying no id_token is as much a dead end as a rejection: returning None while
        // leaving the refresh token stored would refresh again on the next call, forever, and
        // send every request unauthenticated in between.
        Future.fromTry(idTokenOf(body)).map { fresh =>
          store(body)
          Some(fresh)
        }
      }
      .recoverWith {
        case error if endsSession(error) =>
          clearSession()
          Future.successful(None)
      }
  }

  /** Whether a failed refresh means the session itself is over, rather than that this attempt did
    * not get through.
    *
    * The distinction matters: clearing the session on a dropped connection or a Cognito 5xx would
    * sign a player out mid-game over something that would have worked a second later. Those
    * failures are left to propagate, so the call that triggered the refresh fails and can be
    * retried with the session intact.
    */
  private def endsSession(error: Throwable): Boolean = error match {
    // The refresh token is expired, revoked, or signed out elsewhere. Nothing to retry with.
    case TokenError(400, body) => Try(ujson.read(body)("error").str).toOption.contains("invalid_grant")
    // A 200 with no usable token in it. Retrying would loop, so this ends the session too.
    case _: MalformedTokenResponse => true
    case _                         => false
  }

  /** Sends the browser to the hosted UI. Does not return: the page navigates away.
    *
    * `/login` rather than `/oauth2/authorize` so the user lands on the sign-in page with a
    * sign-up link, which is what self-registration needs.
    */
  def signIn(): Future[Unit] = {
    val verifier = Pkce.newVerifier()
    val state = Pkce.newState()

    Pkce.challengeFor(verifier).map { challenge =>
      // Stored before navigating, and read back when Cognito redirects here. sessionStorage
      // survives the redirect; a field on this object would not.
      dom.window.sessionStorage.setItem(VerifierKey, verifier)
      dom.window.sessionStorage.setItem(StateKey, state)

      val query = new URLSearchParams()
      query.set("client_id", Config.current.clientId)
      query.set("response_type", "code")
      query.set("scope", "openid email profile")
      query.set("redirect_uri", Config.current.redirectUri)
      query.set("code_challenge", challenge)
      query.set("code_challenge_method", "S256")
      query.set("state", state)

      dom.window.location.href = s"${Config.current.hostedLoginUrl}/login?${query.toString}"
    }
  }

  def signOut(): Unit = {
    clearSession()

    val query = new URLSearchParams()
    query.set("client_id", Config.current.clientId)
    query.set("logout_uri", Config.current.redirectUri)

    // Ends the Cognito session too. Clearing only this tab's storage would leave the hosted UI
    // signed in, so the next sign-in would silently reuse the same account.
    dom.window.location.href = s"${Config.current.hostedLoginUrl}/logout?${query.toString}"
  }

  def clearSession(): Unit = {
    dom.window.sessionStorage.removeItem(TokenKey)
    dom.window.sessionStorage.removeItem(RefreshKey)
    dom.window.sessionStorage.removeItem(VerifierKey)
    dom.window.sessionStorage.removeItem(StateKey)
  }

  /** Completes a sign-in if this page load is Cognito's redirect back.
    *
    * Returns `Some` only when a code was present, so the caller can tell "just signed in" from
    * "ordinary page load". Errors from Cognito arrive as `?error=` on the same redirect and are
    * surfaced rather than ignored — an unusable login that reports nothing is the worst outcome.
    */
  def completeSignIn(): Option[Future[Unit]] = {
    val params = new URLSearchParams(dom.window.location.search)

    Option(params.get("error")) match {
      case Some(error) =>
        val description = Option(params.get("error_description")).getOrElse(error)
        stripQuery()
        Some(Future.failed(new IllegalStateException(s"hosted login failed: $description")))

      case None =>
        Option(params.get("code")).map { code =>
          val returnedState = Option(params.get("state"))
          val expectedState = Option(dom.window.sessionStorage.getItem(StateKey))
          val verifier = Option(dom.window.sessionStorage.getItem(VerifierKey))

          // The code is removed from the address bar whatever happens next, so that a reload does
          // not attempt to redeem an already-redeemed code and so that it stays out of history.
          stripQuery()

          if (returnedState.isEmpty || returnedState != expectedState)
            Future.failed(new IllegalStateException("sign-in state did not match; ignoring this callback"))
          else
            verifier match {
              case None =>
                Future.failed(new IllegalStateException("no PKCE verifier for this callback; start sign-in again"))
              case Some(v) => exchange(code, v)
            }
        }
    }
  }

  /** Redeems the authorization code for tokens, presenting the PKCE verifier.
    *
    * No client secret and so no `Authorization` header: this is a public client, which is exactly
    * the case PKCE exists for.
    */
  private def exchange(code: String, verifier: String): Future[Unit] = {
    val form = new URLSearchParams()
    form.set("grant_type", "authorization_code")
    form.set("client_id", Config.current.clientId)
    form.set("code", code)
    form.set("redirect_uri", Config.current.redirectUri)
    form.set("code_verifier", verifier)

    postToken(form).transform { result =>
      // Whether it worked or not, the verifier is single-use.
      dom.window.sessionStorage.removeItem(VerifierKey)
      dom.window.sessionStorage.removeItem(StateKey)
      result.flatMap(body => idTokenOf(body).map(_ => store(body)))
    }
  }

  /** POSTs a form to Cognito's token endpoint, failing the future on a non-200.
    *
    * No client secret and so no `Authorization` header: this is a public client.
    */
  private def postToken(form: URLSearchParams): Future[String] = {
    val init = new RequestInit {}
    init.method = HttpMethod.POST
    init.headers = js.Dictionary("content-type" -> "application/x-www-form-urlencoded")
    init.body = form.toString

    dom
      .fetch(s"${Config.current.hostedLoginUrl}/oauth2/token", init)
      .toFuture
      .flatMap(response => response.text().toFuture.map(body => (response.status, body)))
      .flatMap {
        case (200, body) => Future.successful(body)
        case (status, body) => Future.failed(TokenError(status, body))
      }
  }

  /** Stores whichever tokens a token response carried. A refresh grant returns no refresh token
    * of its own, so the one already stored is left in place rather than cleared.
    */
  private def store(body: String): Unit = {
    idTokenOf(body).foreach(token => dom.window.sessionStorage.setItem(TokenKey, token))
    Try(ujson.read(body)("refresh_token").str).foreach(token => dom.window.sessionStorage.setItem(RefreshKey, token))
  }

  private def idTokenOf(body: String): Try[String] =
    Try(ujson.read(body)("id_token").str).recoverWith { case _ =>
      Failure(MalformedTokenResponse())
    }

  /** A non-200 from the token endpoint, keeping the status and body so the caller can tell a
    * rejected token from an unreachable Cognito.
    */
  private case class TokenError(status: Int, body: String)
      extends Exception(s"token request failed ($status): $body")

  private case class MalformedTokenResponse() extends Exception("token response carried no id_token")

  /** True when the token's `exp` is still in the future, with a small margin so that a token
    * about to expire is not sent on a request that will outlive it.
    *
    * The signature is deliberately not checked. Nothing here could act on the result — the
    * gateway is what decides whether a token is good, and it does verify the signature. This only
    * decides whether to bother asking.
    */
  private def unexpired(token: String): Boolean =
    expiryOf(token) match {
      case Success(expiry) => expiry - 30 > js.Date.now() / 1000
      case Failure(_)      => false
    }

  private def expiryOf(token: String): Try[Double] = Try {
    val payload = token.split('.')(1)
    // JWTs are base64url; atob wants standard base64, and the padding is optional there.
    val decoded = dom.window.atob(payload.replace("-", "+").replace("_", "/"))
    ujson.read(decoded)("exp").num
  }

  /** Drops the query string without reloading, leaving the address bar clean. */
  private def stripQuery(): Unit =
    dom.window.history.replaceState((), "", dom.window.location.pathname)
}
