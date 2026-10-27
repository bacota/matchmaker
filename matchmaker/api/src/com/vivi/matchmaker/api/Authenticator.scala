package com.vivi.matchmaker.api

import ApiGateway.{Request, Response}
import cats.effect.IO
import com.vivi.matchmaker.auth.ApiKeys

/** Establishes who is calling.
  *
  * This is a parameter of `Router.dispatch` rather than something the router works out for itself, because where trust
  * comes from differs by deployment and the router should not have to know. Behind API Gateway a JWT authorizer
  * verifies the caller's token before the function is ever invoked, so the handler only has to read an
  * already-validated claim. Running locally there is no gateway, so either the caller is trusted outright or the token
  * has to be verified in-process.
  *
  * Failure is a `Response` rather than a flag, so that each implementation can say what was actually wrong — a missing
  * header and an expired token are not the same 401.
  *
  * An `IO`, because one of them has to look something up: an engine's API key is checked against the keys stored with
  * the games. The others answer from the request alone and are pure underneath.
  */
trait Authenticator {
    def callerOf(request: Request): IO[Either[Response, String]]

    /** The address the caller's identity has been shown to receive mail at, if this authenticator can vouch for one.
      *
      * The one source of `player.email`: the router writes nothing else to it, and never an address from a request
      * body, which would let a caller send their notifications to a mailbox that is not theirs. `None` is the default,
      * so an authenticator that says nothing about addresses — an engine's API key — vouches for none.
      */
    def verifiedEmailOf(request: Request): Option[String] = None
}

object Authenticator {

    /** Takes the caller's identity from the `X-External-Id` header and trusts it.
      *
      * This is the whole of authentication today, and is safe only because nothing is deployed publicly. It stays
      * afterwards as the local development mode, where picking an identity with a curl flag beats running a
      * hosted-login flow to exercise a route.
      */
    object TrustedHeader extends Authenticator {
        def callerOf(request: Request): IO[Either[Response, String]] =
            IO.pure(
              request
                  .header(ApiGateway.ExternalIdHeader)
                  .map(_.trim)
                  .filter(_.nonEmpty)
                  .toRight(Errors.unauthenticated)
            )

        /** From the `X-Email` header, trusted as the identity header is: locally there is no Cognito identity behind
          * the caller, so there is nothing to verify the address against either, and the header is how a curl call
          * gives the local player one.
          */
        override def verifiedEmailOf(request: Request): Option[String] =
            request.header(ApiGateway.EmailHeader).map(_.trim).filter(_.nonEmpty)
    }

    /** Takes the caller's identity from the `sub` claim of the token API Gateway already verified.
      *
      * This deliberately verifies nothing. The JWT authorizer in front of the function checks the signature, expiry,
      * issuer and audience, and rejects the request with 401 before the function is invoked; re-doing that here would
      * mean fetching JWKS on the request path for no gain.
      *
      * What that argument rests on is that the function is only reachable through the gateway, and that every route
      * carries the authorizer. Both are true in the terraform — the `$default` route sets `authorization_type = "JWT"`
      * and the only `lambda_permission` is API Gateway's — and if either stopped being true, a request arriving with no
      * claims would be unauthenticated rather than admitted, which is what the missing-`sub` case below is for.
      */
    object GatewayClaims extends Authenticator {
        def callerOf(request: Request): IO[Either[Response, String]] =
            IO.pure(request.claim("sub").filter(_.nonEmpty).toRight(Errors.unauthenticatedToken))

        /** The token's `email` claim, when its `email_verified` claim says Cognito has confirmed it.
          *
          * The pool requires verification before an address change takes effect
          * (`attributes_require_verification_before_update`), so an issued token's `email` is already one whose code
          * was answered; `email_verified` is checked as well rather than relying on that setting staying put. The
          * gateway passes every claim as a string, so the flag arrives as `"true"`.
          */
        override def verifiedEmailOf(request: Request): Option[String] =
            if (request.claim("email_verified").contains("true"))
                request.claim("email").map(_.trim).filter(_.nonEmpty)
            else None
    }

    /** Takes the caller's identity from the API key the game engine presented.
      *
      * This is how the game engine's callbacks are authenticated. Matchmaker and each engine share one secret: the
      * engine has it from its own configuration, and matchmaker from the game's stored key, which an admin sets on the
      * game's form. The key names the engine — the game it is stored with has the engine's identity as its
      * `externalId`, which is what the services compare a game-authorized caller against.
      *
      * `keys` is asked on every call rather than once, so that a key an admin has just changed is the one checked.
      *
      * Unlike the other two, this one verifies the credential itself: there is no authorizer in front of these routes
      * any more, so nothing has checked anything by the time the function runs. A wrong key and a missing key are the
      * same 401 on purpose — telling a caller that the key it sent was well-formed but unknown tells it that guessing
      * is worth continuing.
      */
    class ApiKey(keys: () => IO[ApiKeys]) extends Authenticator {
        def callerOf(request: Request): IO[Either[Response, String]] =
            request.header(ApiKeys.Header).map(_.trim).filter(_.nonEmpty) match {
                // Nothing presented is refused without a lookup: there is nothing to look up.
                case None            => IO.pure(Left(Errors.unauthenticated))
                case Some(presented) => keys().map(_.nameOf(presented).toRight(Errors.unauthenticated))
            }
    }

    /** The deployed authenticator: whichever authorizer actually ran decides how the caller is identified.
      *
      * The player routes carry the Cognito JWT authorizer and arrive with claims; the game engine's callback routes
      * carry no authorizer and arrive with an API key. Claims are looked at first and are not something a caller can
      * put there — they are written into the event by the authorizer — so an engine cannot present itself as a player,
      * and a player's token is no use as a key.
      *
      * Neither present is still unauthenticated, which is what keeps a route accidentally left open from being admitted
      * here.
      */
    class Gateway(keys: () => IO[ApiKeys]) extends Authenticator {
        private val apiKey = ApiKey(keys)

        def callerOf(request: Request): IO[Either[Response, String]] =
            if (request.claims.nonEmpty) GatewayClaims.callerOf(request)
            else if (request.header(ApiKeys.Header).isDefined) apiKey.callerOf(request)
            else IO.pure(Left(Errors.unauthenticatedToken))

        /* Only from claims, for the reason `callerOf` looks at them first: an engine's key vouches
         * for no address. */
        override def verifiedEmailOf(request: Request): Option[String] =
            if (request.claims.nonEmpty) GatewayClaims.verifiedEmailOf(request) else None
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
