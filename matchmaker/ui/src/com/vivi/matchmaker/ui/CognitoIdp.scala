package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success, Try}
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit}

/** The bits of the Cognito user pools API this UI calls directly.
  *
  * Not an SDK: the three operations used here are plain JSON POSTs to one endpoint, and pulling in
  * the AWS SDK to make them would be far more machinery than the calls are worth.
  *
  * None of them are SigV4 signed. `InitiateAuth`, `RespondToAuthChallenge` and `RevokeToken` are
  * how a caller with no credentials obtains some; the account operations — `ChangePassword`,
  * `UpdateUserAttributes`, `VerifyUserAttribute` — authorize with the user's own access token
  * instead, which is why they need one rather than the ID token every API call carries.
  * The app client is public (`generate_secret = false` in the terraform), so there is no
  * `SECRET_HASH` to compute either, which is what makes this reachable from a browser at all.
  *
  * The wire format is AWS's JSON 1.1 protocol: the operation is named in `X-Amz-Target` rather
  * than in the path, and a failure is an HTTP 400 whose body carries the exception type.
  */
object CognitoIdp {

  private def endpoint: String = s"https://cognito-idp.${Config.current.region}.amazonaws.com/"

  /** What a successful authentication returns. `refreshToken` is absent when refreshing: that
    * grant reuses the refresh token already held rather than issuing a new one.
    *
    * `accessToken` is the one the *user pools API* accepts, and is what the account operations
    * below are authorized with — an ID token is not accepted there, whatever its claims say. It
    * is an `Option` because a response that omitted it would still be a usable sign-in; what
    * depends on it is the account menu, not the session.
    */
  case class Tokens(idToken: String, refreshToken: Option[String], accessToken: Option[String] = None)

  /** Cognito wants something more from the caller before it will issue tokens.
    *
    * `session` is opaque and single-use — it must be echoed back on the response, and a new one
    * comes back if that response is itself challenged.
    *
    * `parameters` carries whatever the particular challenge needs the user to be told. For
    * `EMAIL_OTP` that is `CODE_DELIVERY_DESTINATION`, the masked address the code went to, which
    * is worth showing so the user knows which mailbox to open.
    */
  case class Challenge(name: String, session: String, parameters: Map[String, String]) {

    /** The masked destination an OTP was delivered to, when the challenge is a delivered code. */
    def deliveredTo: Option[String] = parameters.get("CODE_DELIVERY_DESTINATION")

    /** The factors offered by a `SELECT_CHALLENGE`, in the order Cognito listed them. */
    def availableChallenges: Seq[String] = parameters.get("AVAILABLE_CHALLENGES").toSeq.flatMap(_.split(','))
  }

  /** Either the run is finished and there are tokens, or Cognito wants another step. */
  enum AuthOutcome {
    case Authenticated(tokens: Tokens)
    case Challenged(challenge: Challenge)
  }

  /** A rejection from Cognito, keeping the `__type` so callers can tell "wrong password" from
    * "this user is not confirmed" — which need entirely different things said to the user.
    *
    * `message` is Cognito's own wording. It is shown as-is: these strings are written to be read
    * by end users, and paraphrasing them here would mean maintaining a second copy of Cognito's
    * error catalogue.
    */
  case class IdpError(errorType: String, message: String) extends Exception(s"$errorType: $message")

  /** The request never got an answer, or got one that was not JSON — a dropped connection, a 5xx,
    * a proxy error page. Distinct from `IdpError` because it says nothing about the credentials
    * and is worth retrying.
    */
  case class IdpUnavailable(detail: String) extends Exception(s"could not reach Cognito: $detail")

  /** Starts a sign-in with the choice-based flow, naming the factor to try first.
    *
    * `USER_AUTH` is the flow that lets the *client* decide what to ask for, which is the whole
    * reason this page exists rather than a redirect to managed login: managed login enables every
    * factor the pool allows and puts the passwordless one forward, and there is no setting that
    * reorders it.
    *
    * Passing `PASSWORD` here alongside the password itself resolves in one round trip when the
    * password is right. Passing `EMAIL_OTP` (with no password) comes back as a challenge, having
    * mailed a code.
    */
  def initiateUserAuth(
      username: String,
      preferredChallenge: String,
      password: Option[String]
  ): Future[AuthOutcome] = {
    val parameters = Map("USERNAME" -> username, "PREFERRED_CHALLENGE" -> preferredChallenge) ++
      password.map("PASSWORD" -> _)

    call(
      "InitiateAuth",
      ujson.Obj(
        "AuthFlow" -> "USER_AUTH",
        "ClientId" -> Config.current.clientId,
        "AuthParameters" -> ujson.Obj.from(parameters.view.mapValues(ujson.Str(_)))
      )
    ).flatMap(body => Future.fromTry(outcomeOf(body)))
  }

  /** Answers an outstanding challenge. The session comes from the challenge being answered. */
  def respondToChallenge(
      challengeName: String,
      session: String,
      responses: Map[String, String]
  ): Future[AuthOutcome] =
    call(
      "RespondToAuthChallenge",
      ujson.Obj(
        "ChallengeName" -> challengeName,
        "ClientId" -> Config.current.clientId,
        "Session" -> session,
        "ChallengeResponses" -> ujson.Obj.from(responses.view.mapValues(ujson.Str(_)))
      )
    ).flatMap(body => Future.fromTry(outcomeOf(body)))

  /** Exchanges a refresh token for a fresh ID token.
    *
    * Deliberately not the hosted UI's `/oauth2/token` endpoint. A refresh token minted by
    * `InitiateAuth` was never issued against an OAuth grant, and the token endpoint will not
    * redeem it — which fails silently at the first expiry rather than at sign-in, so it is worth
    * being explicit about.
    */
  def refresh(refreshToken: String): Future[Tokens] =
    call(
      "InitiateAuth",
      ujson.Obj(
        "AuthFlow" -> "REFRESH_TOKEN_AUTH",
        "ClientId" -> Config.current.clientId,
        "AuthParameters" -> ujson.Obj("REFRESH_TOKEN" -> refreshToken)
      )
    ).flatMap { body =>
      Future.fromTry(outcomeOf(body).flatMap {
        case AuthOutcome.Authenticated(tokens) => Success(tokens)
        // A refresh cannot be challenged. If one somehow is, there is no user in front of this
        // call to answer it — it is a dead end, and saying so beats hanging on a challenge that
        // nothing will ever respond to.
        case AuthOutcome.Challenged(challenge) =>
          Failure(IdpError("UnexpectedChallenge", s"refresh was challenged with ${challenge.name}"))
      })
    }

  /** Revokes a refresh token, and with it every access token issued from the same sign-in.
    *
    * This is what makes signing out mean something. Without it the tokens in this tab are dropped
    * but remain valid until they expire, so anything that captured one keeps working.
    */
  def revoke(refreshToken: String): Future[Unit] =
    call("RevokeToken", ujson.Obj("Token" -> refreshToken, "ClientId" -> Config.current.clientId)).map(_ => ())

  /** Changes the signed-in user's password, given the current one.
    *
    * Cognito checks `PreviousPassword` itself, which is what makes this safe to offer to a
    * session that may have been left open: knowing the old password is required, so a stolen
    * token alone cannot lock the owner out.
    */
  def changePassword(accessToken: String, previous: String, proposed: String): Future[Unit] =
    call(
      "ChangePassword",
      ujson.Obj("AccessToken" -> accessToken, "PreviousPassword" -> previous, "ProposedPassword" -> proposed)
    ).map(_ => ())

  /** Asks Cognito to change the signed-in user's email address.
    *
    * The change is not complete when this returns. The pool auto-verifies email, so Cognito mails
    * a code to the *new* address and leaves the attribute unverified until `verifyEmail` quotes
    * it back — which is the point: it proves the address before it becomes the thing the account
    * signs in with. The masked destination comes back so the user knows which mailbox to open.
    */
  def updateEmail(accessToken: String, email: String): Future[Option[String]] =
    call(
      "UpdateUserAttributes",
      ujson.Obj(
        "AccessToken" -> accessToken,
        "UserAttributes" -> ujson.Arr(ujson.Obj("Name" -> "email", "Value" -> email))
      )
    ).map { body =>
      Try(ujson.read(body)("CodeDeliveryDetailsList").arr.head("Destination").str).toOption
    }

  /** Completes an email change with the code Cognito mailed to the new address. */
  def verifyEmail(accessToken: String, code: String): Future[Unit] =
    call(
      "VerifyUserAttribute",
      ujson.Obj("AccessToken" -> accessToken, "AttributeName" -> "email", "Code" -> code)
    ).map(_ => ())

  /** Reads an `InitiateAuth` or `RespondToAuthChallenge` response body.
    *
    * Exposed for the tests: this parsing is the part with edge cases in it, and it is pure.
    */
  def outcomeOf(body: String): Try[AuthOutcome] = Try {
    val json = ujson.read(body)

    json.obj.get("AuthenticationResult") match {
      case Some(result) =>
        AuthOutcome.Authenticated(
          Tokens(
            idToken = result("IdToken").str,
            refreshToken = result.obj.get("RefreshToken").map(_.str),
            accessToken = result.obj.get("AccessToken").map(_.str)
          )
        )
      case None =>
        AuthOutcome.Challenged(
          Challenge(
            name = json("ChallengeName").str,
            // Every challenge carries one, and without it there is nothing to respond with, so a
            // missing `Session` is a parse failure rather than a challenge we cannot answer later.
            session = json("Session").str,
            parameters = json.obj
              .get("ChallengeParameters")
              .map(_.obj.view.mapValues(_.str).toMap)
              .getOrElse(Map.empty)
          )
        )
    }
  }

  /** Reads an error body. Cognito names the exception in `__type`, sometimes as a bare name and
    * sometimes qualified with a namespace (`com.amazonaws...#NotAuthorizedException`); only the
    * last segment is meaningful, so callers can match on it without knowing which form arrived.
    */
  private[ui] def errorOf(status: Int, body: String): Throwable =
    Try {
      val json = ujson.read(body)
      val errorType = json.obj
        .get("__type")
        .map(_.str)
        .map(t => t.substring(t.lastIndexOf('#') + 1))
        .getOrElse("UnknownError")
      // The field is `message` in this protocol, but `Message` appears in the wild too.
      val message = json.obj.get("message").orElse(json.obj.get("Message")).map(_.str).getOrElse(body)
      IdpError(errorType, message)
    }.getOrElse(IdpUnavailable(s"HTTP $status: $body"))

  private def call(operation: String, payload: ujson.Obj): Future[String] = {
    val init = new RequestInit {}
    init.method = HttpMethod.POST
    init.headers = js.Dictionary(
      "content-type" -> "application/x-amz-json-1.1",
      "x-amz-target" -> s"AWSCognitoIdentityProviderService.$operation"
    )
    init.body = ujson.write(payload)

    dom
      .fetch(endpoint, init)
      .toFuture
      .flatMap(response => response.text().toFuture.map(body => (response.status, body)))
      .recoverWith { case error => Future.failed(IdpUnavailable(error.getMessage)) }
      .flatMap {
        case (status, body) if status >= 200 && status < 300 => Future.successful(body)
        case (status, body)                                  => Future.failed(errorOf(status, body))
      }
  }
}
