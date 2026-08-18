package com.vivi.tictactoe

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.time.{Duration, Instant}
import java.util.Base64
import scala.util.control.NonFatal

/** Verifies a Cognito ID token against the pool's published keys.
  *
  * Deployed, nothing uses this: API Gateway's JWT authorizer checks the signature, expiry, issuer
  * and audience before the function is invoked, and re-doing that per request would mean fetching
  * JWKS on the request path for no gain. It exists for the local server, where there is no
  * gateway — and it verifies genuinely rather than emulating, because the pool's JWKS endpoint is
  * public: a process on localhost with no AWS credentials can validate real tokens from the real
  * user pool, which is what lets the same sign-in flow be exercised locally.
  *
  * What is checked, and why each one matters:
  *
  *   - the RS256 signature, against the key named by the token's `kid`
  *   - `iss`, so a token from another pool cannot be presented here
  *   - `aud`, so a token issued to another application cannot
  *   - `token_use` is `id`, since an access token from the same pool carries `client_id` rather
  *     than `aud` and would otherwise pass the audience check vacuously
  *   - `exp`, with a small allowance for clock skew
  */
class JwtVerifier(
    issuer: String,
    audience: String,
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    now: () => Instant = () => Instant.now(),
    skew: Duration = Duration.ofSeconds(60)
) {

  private val jwksUrl = s"${issuer.stripSuffix("/")}/.well-known/jwks.json"

  // Keys by kid, refetched when a token names one that is not here. Cognito rotates keys, and a
  // fetch per unknown kid is both correct and cheap; the interval keeps a token with a made-up
  // kid from turning into a fetch per request.
  private var keys: Map[String, java.security.PublicKey] = Map.empty
  private var lastFetch: Instant = Instant.EPOCH
  private val minRefetchInterval = Duration.ofMinutes(1)

  /** The `sub` of a valid token, or why it was refused. The message is deliberately the same
    * shape for every failure — a caller who is not signed in learns nothing about the token from
    * which check rejected it.
    */
  def verify(token: String): Either[String, String] =
    try {
      val parts = token.split('.')
      if (parts.length != 3) Left("malformed token")
      else {
        val header = ujson.read(decode(parts(0)))
        val claims = ujson.read(decode(parts(1)))
        val signature = Base64.getUrlDecoder.decode(parts(2))

        for {
          _ <- Either.cond(header.obj.get("alg").map(_.str).contains("RS256"), (), "unsupported token algorithm")
          kid <- header.obj.get("kid").map(_.str).toRight("token names no key")
          key <- keyFor(kid).toRight("token names an unknown key")
          _ <- Either.cond(verified(key, parts(0), parts(1), signature), (), "bad token signature")
          _ <- Either.cond(claims.obj.get("iss").map(_.str).contains(issuer), (), "token is from another issuer")
          _ <- Either.cond(claims.obj.get("token_use").map(_.str).contains("id"), (), "not an id token")
          _ <- Either.cond(audienceOf(claims).contains(audience), (), "token is for another application")
          _ <- Either.cond(claims.obj.get("exp").map(_.num).exists(exp => now().minus(skew).getEpochSecond < exp.toLong), (), "token has expired")
          sub <- claims.obj.get("sub").map(_.str).filter(_.nonEmpty).toRight("token names no subject")
        } yield sub
      }
    } catch { case NonFatal(_) => Left("malformed token") }

  private def audienceOf(claims: ujson.Value): Set[String] =
    claims.obj.get("aud") match {
      case Some(ujson.Str(one))  => Set(one)
      case Some(ujson.Arr(many)) => many.collect { case ujson.Str(s) => s }.toSet
      case _                     => Set.empty
    }

  private def verified(key: java.security.PublicKey, header: String, payload: String, signature: Array[Byte]): Boolean = {
    val rsa = java.security.Signature.getInstance("SHA256withRSA")
    rsa.initVerify(key)
    rsa.update(s"$header.$payload".getBytes(UTF_8))
    rsa.verify(signature)
  }

  private def keyFor(kid: String): Option[java.security.PublicKey] = synchronized {
    keys.get(kid).orElse {
      if (Duration.between(lastFetch, now()).compareTo(minRefetchInterval) >= 0) {
        fetchKeys()
        keys.get(kid)
      } else None
    }
  }

  private def fetchKeys(): Unit = {
    lastFetch = now()
    try {
      val request = HttpRequest.newBuilder(URI.create(jwksUrl)).timeout(Duration.ofSeconds(5)).GET().build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode == 200) keys = parseJwks(response.body)
    } catch { case NonFatal(_) => () /* keep whatever keys we had; the next token retries */ }
  }

  private[tictactoe] def parseJwks(body: String): Map[String, java.security.PublicKey] = {
    val factory = KeyFactory.getInstance("RSA")
    ujson
      .read(body)("keys")
      .arr
      .flatMap { key =>
        for {
          kid <- key.obj.get("kid").map(_.str)
          n <- key.obj.get("n").map(_.str)
          e <- key.obj.get("e").map(_.str)
        } yield kid -> factory.generatePublic(RSAPublicKeySpec(unsigned(n), unsigned(e)))
      }
      .toMap
  }

  // base64url of a big-endian magnitude, which is unsigned — the 1-byte sign prefix is what keeps
  // a modulus whose top bit is set from being read as negative.
  private def unsigned(base64Url: String): BigInteger = BigInteger(1, Base64.getUrlDecoder.decode(base64Url))

  private def decode(part: String): String = String(Base64.getUrlDecoder.decode(part), UTF_8)
}
