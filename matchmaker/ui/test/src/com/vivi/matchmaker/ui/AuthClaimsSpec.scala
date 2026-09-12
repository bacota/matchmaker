package com.vivi.matchmaker.ui

import munit.FunSuite

/** Reading a claim out of an ID token.
  *
  * Worth testing because the encoding is the part that is easy to get wrong and quiet when it is:
  * a JWT payload is base64url with the padding dropped, and a decoder that assumes standard
  * base64 fails only on the tokens whose payload length happens to need it — which is most of
  * them, but not the short one someone tries by hand.
  */
class AuthClaimsSpec extends FunSuite {

  /** A token as Cognito issues one: three dot-separated parts, of which only the payload is read.
    * The signature is not checked here and is not checked in `Auth` either — the gateway is what
    * decides whether a token is good.
    */
  private def token(payload: String): String = {
    val encoded = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes("UTF-8"))
    s"header.$encoded.signature"
  }

  test("the email claim is read back out of a token") {
    val jwt = token("""{"sub":"s-1","email":"alice@example.com","email_verified":true,"exp":1893456000}""")
    assertEquals(Auth.claimOf(jwt, "email"), Some("alice@example.com"))
  }

  // A payload whose length is not a multiple of three encodes to base64 needing padding, which
  // base64url drops. Decoding has to cope, so one of these is deliberately of that shape.
  test("a payload that would need base64 padding still decodes") {
    val jwt = token("""{"email":"bo@x.io"}""")
    assertEquals(Auth.claimOf(jwt, "email"), Some("bo@x.io"))
  }

  /* `iat` is read as a number, and is what decides whether a token predates a confirmed address
   * change -- the check that stops `Store.syncEmail` writing a stale claim back over a change the
   * player has just made. */

  test("the issued-at claim is read as a number") {
    val jwt = token("""{"sub":"s-1","iat":1893456000,"exp":1893459600}""")
    assertEquals(Auth.numericClaimOf(jwt, "iat"), Some(1893456000d))
  }

  test("a string where a number was expected is absent rather than parsed") {
    val jwt = token("""{"iat":"1893456000"}""")
    assertEquals(Auth.numericClaimOf(jwt, "iat"), None)
  }

  test("a token carrying no issued-at claim is absent") {
    val jwt = token("""{"sub":"s-1","exp":1893456000}""")
    assertEquals(Auth.numericClaimOf(jwt, "iat"), None)
  }

  test("a claim the token does not carry is absent, not empty") {
    val jwt = token("""{"sub":"s-1","exp":1893456000}""")
    assertEquals(Auth.claimOf(jwt, "email"), None)
  }

  // Rendering `["a","b"]` into "You sign in as ..." is worse than rendering nothing at all.
  test("a claim that is not a string is not stringified") {
    val jwt = token("""{"email":["a@x.io","b@x.io"],"email_verified":true}""")
    assertEquals(Auth.claimOf(jwt, "email"), None)
    assertEquals(Auth.claimOf(jwt, "email_verified"), None)
  }

  test("a token that is not a token yields nothing rather than throwing") {
    assertEquals(Auth.claimOf("not-a-jwt", "email"), None)
    assertEquals(Auth.claimOf("", "email"), None)
    assertEquals(Auth.claimOf("header.@@@.signature", "email"), None)
  }
}
