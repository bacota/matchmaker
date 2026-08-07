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

  /* Two further implementations are expected when Cognito lands, and are the reason this is an
   * interface rather than a method on Router:
   *
   *   GatewayClaims  - production. Reads `sub` out of requestContext.authorizer.jwt.claims, which
   *                    means teaching ApiGateway.decodeRequest to keep that object. It does not
   *                    verify anything, because the gateway already did and the function is only
   *                    reachable through it.
   *   VerifiedToken  - local. Verifies a real token against the pool's JWKS endpoint, which is
   *                    public, so a localhost server can validate genuine tokens from the dev
   *                    user pool with no AWS credentials and no emulation. This is what exercises
   *                    the PKCE redirect and code exchange, which TrustedHeader never touches.
   */
}
