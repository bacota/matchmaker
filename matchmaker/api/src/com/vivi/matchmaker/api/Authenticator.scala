package com.vivi.matchmaker.api

import ApiGateway.{Request, Response}
import com.vivi.matchmaker.auth.ApiKeys

/** Establishes who is calling.
  *
  * This is a parameter of `Router.dispatch` rather than something the router works out for
  * itself, because where trust comes from differs by deployment and the router should not have
  * to know. Behind API Gateway a JWT authorizer verifies the caller's token before the function
  * is ever invoked, so the handler only has to read an already-validated claim. Running locally
  * there is no gateway, so either the caller is trusted outright or the token has to be verified
  * in-process.
  *
  * Failure is a `Response` rather than a flag, so that each implementation can say what was
  * actually wrong — a missing header and an expired token are not the same 401.
  */
trait Authenticator {
  def callerOf(request: Request): Either[Response, String]
}

object Authenticator {

  /** Takes the caller's identity from the `X-External-Id` header and trusts it.
    *
    * This is the whole of authentication today, and is safe only because nothing is deployed
    * publicly. It stays afterwards as the local development mode, where picking an identity with
    * a curl flag beats running a hosted-login flow to exercise a route.
    */
  object TrustedHeader extends Authenticator {
    def callerOf(request: Request): Either[Response, String] =
      request
        .header(ApiGateway.ExternalIdHeader)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(Errors.unauthenticated)
  }

  /** Takes the caller's identity from the `sub` claim of the token API Gateway already verified.
    *
    * This deliberately verifies nothing. The JWT authorizer in front of the function checks the
    * signature, expiry, issuer and audience, and rejects the request with 401 before the function
    * is invoked; re-doing that here would mean fetching JWKS on the request path for no gain.
    *
    * What that argument rests on is that the function is only reachable through the gateway, and
    * that every route carries the authorizer. Both are true in the terraform — the `$default`
    * route sets `authorization_type = "JWT"` and the only `lambda_permission` is API Gateway's —
    * and if either stopped being true, a request arriving with no claims would be unauthenticated
    * rather than admitted, which is what the missing-`sub` case below is for.
    */
  object GatewayClaims extends Authenticator {
    def callerOf(request: Request): Either[Response, String] =
      request
        .claim("sub")
        .filter(_.nonEmpty)
        .toRight(Errors.unauthenticatedToken)
  }

  /** Takes the caller's identity from the API key the game engine presented.
    *
    * This is how the game engine's callbacks are authenticated. Matchmaker and each engine share
    * one secret, configured on both sides; the key names the engine, because matchmaker holds a
    * different key for each one (see [[com.vivi.matchmaker.auth.ApiKeys]]). The name a key is
    * filed under is the value an administrator records as the game's `externalId`, which is what
    * the services compare a game-authorized caller against.
    *
    * Unlike the other two, this one verifies the credential itself: there is no authorizer in
    * front of these routes any more, so nothing has checked anything by the time the function
    * runs. A wrong key and a missing key are the same 401 on purpose — telling a caller that the
    * key it sent was well-formed but unknown tells it that guessing is worth continuing.
    */
  class ApiKey(keys: () => ApiKeys) extends Authenticator {
    def callerOf(request: Request): Either[Response, String] =
      request
        .header(ApiKeys.Header)
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(keys().nameOf)
        .toRight(Errors.unauthenticated)
  }

  /** The deployed authenticator: whichever authorizer actually ran decides how the caller is
    * identified.
    *
    * The player routes carry the Cognito JWT authorizer and arrive with claims; the game
    * engine's callback routes carry no authorizer and arrive with an API key. Claims are looked
    * at first and are not something a caller can put there — they are written into the event by
    * the authorizer — so an engine cannot present itself as a player, and a player's token is no
    * use as a key.
    *
    * Neither present is still unauthenticated, which is what keeps a route accidentally left open
    * from being admitted here.
    */
  class Gateway(keys: () => ApiKeys) extends Authenticator {
    private val apiKey = ApiKey(keys)

    def callerOf(request: Request): Either[Response, String] =
      if (request.claims.nonEmpty) GatewayClaims.callerOf(request)
      else if (request.header(ApiKeys.Header).isDefined) apiKey.callerOf(request)
      else Left(Errors.unauthenticatedToken)
  }

  /* One further implementation is expected, and is part of why this is an interface rather than a
   * method on Router:
   *
   *   VerifiedToken  - local. Verifies a real token against the pool's JWKS endpoint, which is
   *                    public, so a localhost server can validate genuine tokens from the dev
   *                    user pool with no AWS credentials and no emulation. Note that verification
   *                    needs network I/O, so this signature would have to change to return IO
   *                    before that lands. TrustedHeader remains the zero-setup local mode.
   */
}
