package com.vivi.tictactoe

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import com.sun.net.httpserver.HttpServer
import munit.FunSuite

/** Verification against real keys and real signatures — a token minted here with an RSA key, and
  * the matching JWKS served over HTTP the way Cognito serves its own.
  *
  * Signing tokens in the test rather than pasting a captured one is what lets the failure cases
  * exist at all: an expired token, one for another pool, one signed by the wrong key.
  */
class JwtVerifierSpec extends FunSuite {

  private val issuer = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_test"
  private val audience = "client-1"
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  private val keys = keyPair()
  private val other = keyPair()

  private def keyPair(): KeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()
  }

  private def base64Url(bytes: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def jwks(kid: String, pair: KeyPair): String = {
    val key = pair.getPublic.asInstanceOf[RSAPublicKey]
    ujson.write(
      ujson.Obj(
        "keys" -> ujson.Arr(
          ujson.Obj(
            "kid" -> kid,
            "kty" -> "RSA",
            "alg" -> "RS256",
            "use" -> "sig",
            // Two's-complement encodings carry a leading zero byte for a positive number whose top
            // bit is set; JWK wants the unsigned magnitude, so it is stripped.
            "n" -> base64Url(key.getModulus.toByteArray.dropWhile(_ == 0)),
            "e" -> base64Url(key.getPublicExponent.toByteArray.dropWhile(_ == 0))
          )
        )
      )
    )
  }

  private def token(
      pair: KeyPair = keys,
      kid: String = "key-1",
      iss: String = issuer,
      aud: ujson.Value = ujson.Str(audience),
      tokenUse: String = "id",
      sub: String = "sub-alice",
      expiresAt: Instant = now.plusSeconds(3600),
      alg: String = "RS256"
  ): String = {
    val header = base64Url(ujson.write(ujson.Obj("alg" -> alg, "kid" -> kid)).getBytes(UTF_8))
    val claims = base64Url(
      ujson
        .write(ujson.Obj("iss" -> iss, "aud" -> aud, "token_use" -> tokenUse, "sub" -> sub, "exp" -> expiresAt.getEpochSecond.toDouble))
        .getBytes(UTF_8)
    )
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(pair.getPrivate)
    signer.update(s"$header.$claims".getBytes(UTF_8))
    s"$header.$claims.${base64Url(signer.sign())}"
  }

  /** Serves one JWKS document on a loopback port, so the verifier fetches over HTTP as it will in
    * production rather than being handed keys directly.
    */
  private def withJwks(body: String)(f: JwtVerifier => Unit): Unit = {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/.well-known/jwks.json",
      exchange => {
        val bytes = body.getBytes(UTF_8)
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try f(JwtVerifier(s"http://127.0.0.1:${server.getAddress.getPort}", audience, now = () => now))
    finally server.stop(0)
  }

  /* The verifier is constructed with the served url as its issuer, so `iss` has to match that
   * rather than the Cognito-shaped string above. */
  private def issuerOf(verifier: JwtVerifier): String = {
    val field = classOf[JwtVerifier].getDeclaredField("issuer")
    field.setAccessible(true)
    field.get(verifier).asInstanceOf[String]
  }

  test("a well-formed token from the right pool verifies to its subject") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(iss = issuerOf(verifier))), Right("sub-alice"))
    }
  }

  test("a token from another issuer is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(iss = "https://cognito-idp.us-east-1.amazonaws.com/somewhere-else")), Left("token is from another issuer"))
    }
  }

  test("a token for another application is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(
        verifier.verify(token(iss = issuerOf(verifier), aud = ujson.Str("another-client"))),
        Left("token is for another application")
      )
    }
  }

  /* An access token from the same pool carries client_id rather than aud, so an audience check
   * alone would pass it vacuously — and an access token does not identify the user the way an ID
   * token does. */
  test("an access token from the same pool is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(iss = issuerOf(verifier), tokenUse = "access")), Left("not an id token"))
    }
  }

  test("an expired token is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(
        verifier.verify(token(iss = issuerOf(verifier), expiresAt = now.minusSeconds(3600))),
        Left("token has expired")
      )
    }
  }

  test("a token signed by the wrong key is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(pair = other, iss = issuerOf(verifier))), Left("bad token signature"))
    }
  }

  test("a token naming a key the pool does not publish is refused") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(kid = "key-2", iss = issuerOf(verifier))), Left("token names an unknown key"))
    }
  }

  test("alg=none and other malformed tokens are refused rather than accepted") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(verifier.verify(token(iss = issuerOf(verifier), alg = "none")), Left("unsupported token algorithm"))
      assertEquals(verifier.verify("not.a.token"), Left("malformed token"))
      assertEquals(verifier.verify(""), Left("malformed token"))
      assertEquals(verifier.verify("only-one-part"), Left("malformed token"))
    }
  }

  test("an unreachable jwks endpoint refuses rather than admits") {
    val verifier = JwtVerifier("http://127.0.0.1:1", audience, now = () => now)
    assertEquals(verifier.verify(token()), Left("token names an unknown key"))
  }

  test("a multi-valued aud claim is accepted when this application is among them") {
    withJwks(jwks("key-1", keys)) { verifier =>
      assertEquals(
        verifier.verify(token(iss = issuerOf(verifier), aud = ujson.Arr(ujson.Str("other"), ujson.Str(audience)))),
        Right("sub-alice")
      )
    }
  }
}
