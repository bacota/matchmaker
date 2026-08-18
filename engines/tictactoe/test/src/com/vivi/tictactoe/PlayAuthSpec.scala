package com.vivi.tictactoe

import munit.FunSuite

/** Which player a request is from, in each of the three modes.
  *
  * What each mode is *for* is in `PlayAuth`; what matters here is that none of them admits an
  * unidentified caller, since that caller would otherwise be looked up as a seat holder.
  */
class PlayAuthSpec extends FunSuite {

  private val login = LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback")

  test("the gateway mode reads the sub the authorizer verified") {
    val auth = PlayAuth.GatewayClaims(Some(login))
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", claims = Map("sub" -> "sub-alice"))), Right("sub-alice"))
  }

  test("the gateway mode admits nobody when no authorizer ran") {
    val auth = PlayAuth.GatewayClaims(Some(login))
    assertEquals(auth.callerOf(EngineRequest("GET", "/x")), Left(Refusal.NotYours("sign in to play")))
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", claims = Map("sub" -> ""))).isLeft, true)
    // A bearer token is not read here: in this mode the gateway is what verifies one, and reading
    // it unverified would accept a token the gateway would have rejected.
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", headers = Map("authorization" -> "Bearer whatever"))).isLeft, true)
  }

  test("the verifying mode takes the token from the Authorization header") {
    val verifier = new JwtVerifier("https://issuer.test", "client-1") {
      override def verify(token: String): Either[String, String] =
        if (token == "good") Right("sub-alice") else Left("bad token signature")
    }
    val auth = PlayAuth.VerifiedToken(verifier, Some(login))

    assertEquals(auth.callerOf(EngineRequest("GET", "/x", headers = Map("authorization" -> "Bearer good"))), Right("sub-alice"))
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", headers = Map("authorization" -> "bearer good"))), Right("sub-alice"))
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", headers = Map("authorization" -> "Bearer bad"))).isLeft, true)
    assertEquals(auth.callerOf(EngineRequest("GET", "/x", headers = Map("authorization" -> "good"))).isLeft, true)
    assertEquals(auth.callerOf(EngineRequest("GET", "/x")).isLeft, true)
  }

  test("the trusted mode takes the caller's word, from the header or the query") {
    assertEquals(PlayAuth.Trusted.callerOf(EngineRequest("GET", "/x", headers = Map("x-player-id" -> "sub-bob"))), Right("sub-bob"))
    assertEquals(PlayAuth.Trusted.callerOf(EngineRequest("GET", "/x", query = Map("as" -> "sub-bob"))), Right("sub-bob"))
    assertEquals(PlayAuth.Trusted.callerOf(EngineRequest("GET", "/x")).isLeft, true)
    // No login to offer, so the page shows no sign-in button.
    assertEquals(PlayAuth.Trusted.login, None)
  }

  test("the mode is chosen by the environment, and Lambda always means gateway") {
    val pool = Map(
      "COGNITO_ISSUER" -> "https://issuer.test",
      "COGNITO_CLIENT_ID" -> "client-1",
      "HOSTED_LOGIN_URL" -> "https://login.test"
    )

    assert(Config.playAuth(pool.get, "http://engine.test").isInstanceOf[PlayAuth.VerifiedToken])
    assert(Config.playAuth((pool + ("AWS_LAMBDA_FUNCTION_NAME" -> "tictactoe-dev")).get, "http://engine.test").isInstanceOf[PlayAuth.GatewayClaims])
    assertEquals(Config.playAuth(Map.empty[String, String].get, "http://engine.test"), PlayAuth.Trusted)
    assert(Config.playAuth((pool + ("PLAY_AUTH" -> "trusted")).get, "http://engine.test") == PlayAuth.Trusted)
  }

  test("the redirect the page uses is this engine's own callback path") {
    val configured = Config.loginConfig(
      Map("HOSTED_LOGIN_URL" -> "https://login.test/", "COGNITO_CLIENT_ID" -> "client-1").get,
      "http://engine.test"
    )
    assertEquals(configured, Some(LoginConfig("https://login.test", "client-1", "http://engine.test/auth/callback")))
  }

  test("half a login configuration is a startup failure, not a button that goes nowhere") {
    intercept[IllegalStateException](Config.loginConfig(Map("COGNITO_CLIENT_ID" -> "client-1").get, "http://engine.test"))
    assertEquals(Config.loginConfig(Map.empty[String, String].get, "http://engine.test"), None)
  }
}
