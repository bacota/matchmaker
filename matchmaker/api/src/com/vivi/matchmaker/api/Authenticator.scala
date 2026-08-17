package com.vivi.matchmaker.api

import ApiGateway.{Request, Response}

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

  /** Takes the caller's identity from the IAM principal API Gateway verified the signature of.
    *
    * This is how the game engine's callbacks are authenticated. An `AWS_IAM`-authorized route
    * rejects an unsigned or wrongly-signed request before the function is invoked, so what
    * arrives here is a principal the gateway has already established — the same argument
    * [[GatewayClaims]] makes about a verified token.
    *
    * The identity is the *role*, not the session: a role assumed twice produces two different
    * session ARNs, so `arn:aws:sts::123:assumed-role/game-engine/i-0abc` is normalized to
    * `arn:aws:iam::123:role/game-engine`. That is the value an administrator records as the
    * game's `externalId`, which is what the services compare a game-authorized caller against.
    */
  object GatewayIam extends Authenticator {
    def callerOf(request: Request): Either[Response, String] =
      request.iam
        .map(_.roleArn)
        .filter(_.nonEmpty)
        .toRight(Errors.unauthenticated)
  }

  /** The deployed authenticator: whichever authorizer actually ran decides how the caller is
    * identified.
    *
    * A route in API Gateway carries exactly one authorizer, so the event says unambiguously which
    * kind of caller this is: the player routes carry the Cognito JWT authorizer and arrive with
    * claims, and the game engine's callback routes are `AWS_IAM` and arrive with a principal.
    * Routing on the event rather than on the path means the two cannot drift apart — the function
    * cannot decide a request is a game callback when the gateway authenticated it as a player.
    *
    * Neither present is still unauthenticated, which is what keeps a route accidentally left open
    * from being admitted here.
    */
  object Gateway extends Authenticator {
    def callerOf(request: Request): Either[Response, String] =
      if (request.claims.nonEmpty) GatewayClaims.callerOf(request)
      else if (request.iam.isDefined) GatewayIam.callerOf(request)
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
