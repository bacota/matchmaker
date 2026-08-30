package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success, Try}
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit, URLSearchParams}

/** Sign-in against the Cognito user pools API, with the hosted pages kept for sign-up and reset.
  *
  * Two ways in, and the split is deliberate:
  *
  *   - *Signing in* is done here, by `SignIn` driving `CognitoIdp.initiateUserAuth`. That is what
  *     buys the thing managed login will not do: with `EMAIL_OTP` enabled on the pool, managed
  *     login puts the emailed code forward and there is no setting that reorders it. Naming
  *     `PREFERRED_CHALLENGE` ourselves asks for the password first and leaves the code as a
  *     second option.
  *   - *Signing up* and *resetting a password* still redirect to the hosted pages
  *     (`hostedSignUp`, `hostedForgotPassword`), which come back through the authorization code
  *     grant below. Those flows are several screens each and Cognito already has them.
  *
  * The cost of the first half is that the password is typed into this application rather than
  * into Cognito's own page, so script running on this origin could read it — not merely steal a
  * session, as before. What holds that line is that this page loads no third-party script; a CSP
  * on the distribution is what would keep it held.
  *
  * What either route ends with is an ID token, which every API call carries and which API
  * Gateway's JWT authorizer verifies.
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
  private val AccessKey = "matchmaker.accessToken"
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

  /** The current access token, if there is one that has not expired.
    *
    * Distinct from the ID token, and not interchangeable with it: the API carries the ID token,
    * and the Cognito user pools API accepts only this one for the account operations. It is
    * stored under its own key, and a session that predates that key simply has none — which is
    * what `freshAccessToken` refreshes to obtain.
    */
  private def accessToken: Option[String] =
    Option(dom.window.sessionStorage.getItem(AccessKey)).filter(unexpired)

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
      case None        => refreshed().map(_ => idToken)
    }

  /** An access token that is good to send to the Cognito user pools API, refreshing first if
    * there is not one — which includes a session established before access tokens were stored at
    * all, where the ID token is still perfectly good and this is simply absent.
    *
    * `None` means the account operations cannot be offered: there is nothing left to refresh
    * with, or the refresh came back without one.
    */
  def freshAccessToken(): Future[Option[String]] =
    accessToken match {
      case some @ Some(_) => Future.successful(some)
      case None           => refreshed().map(_ => accessToken)
    }

  /** Redeems the refresh token, at most one redemption at a time — several requests hitting an
    * expired token at once must share one attempt rather than race to spend the token each.
    * The result is read back out of storage by the caller, which is what makes this usable for
    * either token.
    */
  private def refreshed(): Future[Option[String]] =
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

  /** Exchanges the refresh token for a new ID token.
    *
    * Through `REFRESH_TOKEN_AUTH` on the user pools API, not the hosted UI's `/oauth2/token`: a
    * refresh token minted by `InitiateAuth` was not issued against an OAuth grant and the token
    * endpoint will not redeem it. Tokens obtained from the hosted sign-up flow *would* refresh at
    * either, so using the IdP call for both is what keeps one code path here.
    *
    * `None`, with the stored session cleared, means the session is genuinely over (see
    * `endsSession`). A failed future means this attempt did not get through and the session is
    * still intact. Cognito does not return a new refresh token here; one is stored if it does.
    */
  private def refresh(token: String): Future[Option[String]] =
    CognitoIdp
      .refresh(token)
      .map { tokens =>
        storeTokens(tokens)
        Some(tokens.idToken)
      }
      .recoverWith {
        case error if endsSession(error) =>
          clearSession()
          Future.successful(None)
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
    // Cognito answers all three with NotAuthorizedException, and the user pool client has
    // `prevent_user_existence_errors`, so it is also what a deleted user comes back as.
    case CognitoIdp.IdpError("NotAuthorizedException", _) => true
    // Cognito understood the request and refused it for some other reason — a disabled user, say.
    // Retrying will not change the answer.
    case _: CognitoIdp.IdpError => true
    // A 200 with no usable token in it. Retrying would loop, so this ends the session too.
    case _: MalformedTokenResponse => true
    // IdpUnavailable and anything else: this attempt did not get through. The session survives.
    case _ => false
  }

  /** Sends the browser to one of the hosted pages. Does not return: the page navigates away.
    *
    * Only sign-up and password reset go through here now — signing in is `SignIn`, on this page.
    * Both come back to `redirectUri` with an authorization code, so both need PKCE set up first,
    * and both land in `completeSignIn` on the way back: a player who has just confirmed a sign-up
    * or set a new password arrives already signed in, which is the point of sending them to a
    * page that can do that rather than back to the sign-in form.
    */
  def hostedSignUp(): Future[Unit] = hosted("signup")

  def hostedForgotPassword(): Future[Unit] = hosted("forgotPassword")

  private def hosted(page: String): Future[Unit] = {
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

      dom.window.location.href = s"${Config.current.hostedLoginUrl}/$page?${query.toString}"
    }
  }

  /** Records the tokens from a completed `InitiateAuth` run. */
  def storeTokens(tokens: CognitoIdp.Tokens): Unit = {
    dom.window.sessionStorage.setItem(TokenKey, tokens.idToken)
    tokens.accessToken.foreach(token => dom.window.sessionStorage.setItem(AccessKey, token))
    // Absent on a refresh, where the token already stored is still the right one.
    tokens.refreshToken.foreach(token => dom.window.sessionStorage.setItem(RefreshKey, token))
  }

  /** Ends the session, here and at Cognito.
    *
    * Revoking is what makes this more than clearing storage: the ID token stays valid until it
    * expires whatever this page does, but revoking the refresh token stops the session being
    * extended past that. It is fire-and-forget — a revoke that does not get through must not
    * leave the user still apparently signed in on a page they asked to leave — so the local
    * session is cleared first and the failure is swallowed.
    *
    * There is no redirect to the hosted `/logout` any more. That existed to end the *hosted UI's*
    * own browser session, and signing in here never creates one. It is still worth a thought for
    * a player who used the hosted sign-up page: that visit did leave a Cognito session cookie,
    * and it is what `hostedSignUp` would silently reuse. It expires on its own, and reaching it
    * requires clicking sign-up again, so it is left alone rather than redirecting every sign-out
    * away from the page.
    */
  def signOut(): Unit = {
    val token = refreshToken
    clearSession()
    token.foreach(t => CognitoIdp.revoke(t).failed.foreach(_ => ()))
  }

  def clearSession(): Unit = {
    dom.window.sessionStorage.removeItem(TokenKey)
    dom.window.sessionStorage.removeItem(AccessKey)
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
    Try(ujson.read(body)("access_token").str).foreach(token => dom.window.sessionStorage.setItem(AccessKey, token))
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
