package com.vivi.tictactoe

/** Establishes which Cognito user is making a play request.
  *
  * The seat a player may move in is their own, and what says whose seat is theirs is the
  * `cognitoId` matchmaker sent when it created the game. So the whole of authorization on the
  * play routes is: verify who is calling, then find the seat with that id.
  *
  * Three implementations for the three situations, and an interface because which one is right is
  * a property of the deployment rather than of the game — the same argument matchmaker's own
  * `Authenticator` makes.
  */
trait PlayAuth {
  def callerOf(request: EngineRequest): Either[Refusal, String]

  /** Whether the browser should be offered a sign-in. False for [[PlayAuth.Trusted]], where there
    * is no login to start and a button pointing at one would be a dead end.
    */
  def login: Option[LoginConfig]
}

/** What the board page needs to run the hosted-login flow: the same pool, the same app client and
  * the same redirect handling as matchmaker's own UI.
  */
case class LoginConfig(hostedLoginUrl: String, clientId: String, redirectUri: String)

object PlayAuth {

  /** Deployed. The claims API Gateway's JWT authorizer already verified — signature, expiry,
    * issuer and audience — so this only reads `sub`.
    *
    * A request with no claims is not admitted: it means no authorizer ran, which is a route
    * misconfigured rather than a player to trust.
    */
  class GatewayClaims(val login: Option[LoginConfig]) extends PlayAuth {
    def callerOf(request: EngineRequest): Either[Refusal, String] =
      request.claims.get("sub").filter(_.nonEmpty).toRight(Refusal.NotYours("sign in to play"))
  }

  /** Local, against a real user pool. Verifies the bearer token itself — see [[JwtVerifier]].
    *
    * This is what makes a local engine exercise the deployed login flow rather than an imitation
    * of it: the page signs in against the real hosted UI, and the token it comes back with is
    * checked against the real keys.
    */
  class VerifiedToken(verifier: JwtVerifier, val login: Option[LoginConfig]) extends PlayAuth {
    def callerOf(request: EngineRequest): Either[Refusal, String] =
      request.bearerToken match {
        case None        => Left(Refusal.NotYours("sign in to play"))
        case Some(token) => verifier.verify(token).left.map(why => Refusal.NotYours(s"sign in to play: $why"))
      }
  }

  /** Local, with no user pool configured at all: whoever the caller says they are.
    *
    * The zero-setup mode, and the counterpart of matchmaker's `AUTH_MODE=header` — an engine
    * pointed at a local matchmaker in that mode has no tokens to verify anyway. Safe only because
    * nothing that runs this way is reachable from anywhere; the local server says so at startup.
    */
  object Trusted extends PlayAuth {
    val login: Option[LoginConfig] = None

    def callerOf(request: EngineRequest): Either[Refusal, String] =
      request.headers
        .get("x-player-id")
        .orElse(request.query.get("as"))
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(Refusal.NotYours("say who you are with ?as=<cognito sub> or an X-Player-Id header"))
  }
}
