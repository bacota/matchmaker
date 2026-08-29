package com.vivi.matchmaker.ui

import scala.util.Success
import munit.FunSuite

/** Reading what Cognito answers with.
  *
  * Worth testing because the shapes are not interchangeable and the failure modes are quiet: a
  * challenge misread as an authentication stores no token and reports nothing, and a `Session`
  * dropped on the floor makes the *next* request fail rather than this one.
  */
class CognitoIdpSpec extends FunSuite {

  test("a completed authentication yields both tokens") {
    val body = """{"AuthenticationResult":{"IdToken":"id.jwt","AccessToken":"access.jwt",
                 |"RefreshToken":"refresh","ExpiresIn":3600,"TokenType":"Bearer"}}""".stripMargin

    assertEquals(
      CognitoIdp.outcomeOf(body),
      Success(CognitoIdp.AuthOutcome.Authenticated(CognitoIdp.Tokens("id.jwt", Some("refresh"))))
    )
  }

  // A refresh returns no refresh token of its own. Reading that as an empty string would
  // overwrite the stored one and end the session at the following expiry.
  test("a refresh response carries no refresh token") {
    val body = """{"AuthenticationResult":{"IdToken":"id.jwt","AccessToken":"access.jwt","ExpiresIn":3600}}"""

    assertEquals(
      CognitoIdp.outcomeOf(body),
      Success(CognitoIdp.AuthOutcome.Authenticated(CognitoIdp.Tokens("id.jwt", None)))
    )
  }

  test("an emailed code is a challenge, with the session and the masked destination") {
    val body = """{"ChallengeName":"EMAIL_OTP","Session":"session-token",
                 |"ChallengeParameters":{"CODE_DELIVERY_DELIVERY_MEDIUM":"EMAIL",
                 |"CODE_DELIVERY_DESTINATION":"b***@v***.com"}}""".stripMargin

    CognitoIdp.outcomeOf(body) match {
      case Success(CognitoIdp.AuthOutcome.Challenged(challenge)) =>
        assertEquals(challenge.name, "EMAIL_OTP")
        assertEquals(challenge.session, "session-token")
        assertEquals(challenge.deliveredTo, Some("b***@v***.com"))
      case other => fail(s"expected an EMAIL_OTP challenge, got $other")
    }
  }

  test("a challenge with no parameters is still readable") {
    CognitoIdp.outcomeOf("""{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"s"}""") match {
      case Success(CognitoIdp.AuthOutcome.Challenged(challenge)) =>
        assertEquals(challenge.name, "NEW_PASSWORD_REQUIRED")
        assertEquals(challenge.deliveredTo, None)
      case other => fail(s"expected a challenge, got $other")
    }
  }

  test("available challenges are split, so SELECT_CHALLENGE can be answered with PASSWORD") {
    val body = """{"ChallengeName":"SELECT_CHALLENGE","Session":"s",
                 |"ChallengeParameters":{"AVAILABLE_CHALLENGES":"PASSWORD,EMAIL_OTP"}}""".stripMargin

    CognitoIdp.outcomeOf(body) match {
      case Success(CognitoIdp.AuthOutcome.Challenged(challenge)) =>
        assertEquals(challenge.availableChallenges, Seq("PASSWORD", "EMAIL_OTP"))
      case other => fail(s"expected a challenge, got $other")
    }
  }

  test("a malformed body fails rather than being read as an empty authentication") {
    assert(CognitoIdp.outcomeOf("""{"nothing":"useful"}""").isFailure)
  }

  // Cognito qualifies the type with a namespace in some responses and not in others. Callers
  // match on the bare name — `endsSession` and the sign-in form's wording both do — so both
  // spellings have to reduce to it.
  test("an error type is read whether or not it is namespaced") {
    val bare = CognitoIdp.errorOf(400, """{"__type":"NotAuthorizedException","message":"Incorrect username or password."}""")
    val qualified = CognitoIdp.errorOf(
      400,
      """{"__type":"com.amazonaws.cognitoidentityprovider#NotAuthorizedException","message":"Incorrect username or password."}"""
    )

    assertEquals(bare, CognitoIdp.IdpError("NotAuthorizedException", "Incorrect username or password."))
    assertEquals(qualified, CognitoIdp.IdpError("NotAuthorizedException", "Incorrect username or password."))
  }

  // A 5xx from in front of Cognito is an HTML error page, not JSON. That is a reachability
  // problem, and `endsSession` must not read it as a rejected token and sign the player out.
  test("a non-JSON error body is unavailability, not a rejection") {
    assert(CognitoIdp.errorOf(503, "<html>Service Unavailable</html>").isInstanceOf[CognitoIdp.IdpUnavailable])
  }
}
