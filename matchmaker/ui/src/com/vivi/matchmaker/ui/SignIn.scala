package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import com.raquo.laminar.api.L.{*, given}

/** The sign-in form, and the challenge run behind it.
  *
  * Cognito's `USER_AUTH` flow is a conversation rather than a single call: a first request names
  * the factor to try, and the answer is either tokens or another challenge carrying a `Session`
  * to echo back. This holds the position in that conversation.
  *
  * The reason it exists at all is ordering. The pool allows `PASSWORD` and `EMAIL_OTP` as first
  * factors, and managed login decides between them itself — putting the emailed code first, with
  * no setting to change it. Asking for `PREFERRED_CHALLENGE = PASSWORD` here is what makes the
  * password the default and the code an alternative the user can take.
  */
object SignIn {

  /** Where the conversation with Cognito has got to. */
  enum Stage {
    /** Nothing started: the email and password form. */
    case Credentials

    /** Cognito sent a code and wants it back. `challenge` is its own name for the challenge,
      * which decides the field the answer goes in — see `codeResponseKeys`.
      */
    case Code(challenge: String, username: String, session: String, deliveredTo: Option[String])

    /** A first sign-in on a temporary password. Reached by the administrator created in the
      * terraform when `admin_initial_password` was left unset, and by anyone Cognito has had a
      * password reset forced on.
      */
    case NewPassword(username: String, session: String)
  }

  /** The field a delivered code goes back in, per challenge.
    *
    * Only `EMAIL_OTP` can arrive with the pool as it is configured — there is no MFA and no phone
    * number. The rest are here because they are the same screen with a different key, and a
    * challenge this does not recognise is a dead end for the user in front of it.
    */
  private val codeResponseKeys = Map(
    "EMAIL_OTP" -> "EMAIL_OTP_CODE",
    "SMS_OTP" -> "SMS_OTP_CODE",
    "SMS_MFA" -> "SMS_MFA_CODE",
    "SOFTWARE_TOKEN_MFA" -> "SOFTWARE_TOKEN_MFA_CODE"
  )

  private val stage: Var[Stage] = Var(Stage.Credentials)

  /** True while a request is in flight. Disables the buttons, so that a slow answer does not
    * become two sign-in attempts — the second of which would arrive with a spent `Session`.
    */
  private val busy: Var[Boolean] = Var(false)

  /** Shown above the form. Not `Store.error`: this belongs to the form and should clear when the
    * user tries again, rather than joining the application-wide errors in the header.
    */
  private val problem: Var[Option[String]] = Var(None)

  private val email: Var[String] = Var("")
  private val password: Var[String] = Var("")
  private val code: Var[String] = Var("")
  private val newPassword: Var[String] = Var("")

  // -------------------------------------------------------------------------
  // The conversation
  // -------------------------------------------------------------------------

  /** The ordinary path: email and password, one round trip when the password is right.
    *
    * The password goes in `AuthParameters` alongside the preference, so a correct one comes back
    * as tokens rather than as a `PASSWORD` challenge to answer separately.
    */
  private def withPassword(): Unit = {
    val username = email.now().trim
    run(username)(CognitoIdp.initiateUserAuth(username, "PASSWORD", Some(password.now())))
  }

  /** The alternative: no password, mail a code. Always comes back as a challenge. */
  private def withEmailCode(): Unit = {
    val username = email.now().trim
    run(username)(CognitoIdp.initiateUserAuth(username, "EMAIL_OTP", None))
  }

  private def answerCode(stageNow: Stage.Code): Unit =
    run(stageNow.username)(
      CognitoIdp.respondToChallenge(
        stageNow.challenge,
        stageNow.session,
        Map(
          "USERNAME" -> stageNow.username,
          codeResponseKeys(stageNow.challenge) -> code.now().trim
        )
      )
    )

  private def answerNewPassword(stageNow: Stage.NewPassword): Unit =
    run(stageNow.username)(
      CognitoIdp.respondToChallenge(
        "NEW_PASSWORD_REQUIRED",
        stageNow.session,
        Map("USERNAME" -> stageNow.username, "NEW_PASSWORD" -> newPassword.now())
      )
    )

  /** Runs one step and advances, or reports why it did not.
    *
    * `username` is threaded through because Cognito does not echo it and every subsequent
    * response has to carry it.
    */
  private def run(username: String)(step: => Future[CognitoIdp.AuthOutcome]): Unit = {
    if (username.isEmpty) problem.set(Some("Enter your email address."))
    else if (busy.now()) ()
    else {
      busy.set(true)
      problem.set(None)

      step.onComplete { outcome =>
        busy.set(false)
        outcome match {
          case Success(result) => advance(username, result)
          case Failure(error)  => problem.set(Some(explain(error)))
        }
      }
    }
  }

  private def advance(username: String, outcome: CognitoIdp.AuthOutcome): Unit = outcome match {
    case CognitoIdp.AuthOutcome.Authenticated(tokens) => succeed(tokens)

    case CognitoIdp.AuthOutcome.Challenged(challenge) =>
      challenge.name match {
        case name if codeResponseKeys.contains(name) =>
          code.set("")
          stage.set(Stage.Code(name, username, challenge.session, challenge.deliveredTo))

        case "NEW_PASSWORD_REQUIRED" =>
          newPassword.set("")
          stage.set(Stage.NewPassword(username, challenge.session))

        /* Cognito asking for the password separately rather than accepting the one already sent.
         * Not the usual answer — a correct password authenticates outright, and a wrong one is a
         * rejection, not a repeat — so this is answered once from the field rather than being
         * turned into another screen. There is no loop to fall into: the response either
         * authenticates or fails.
         */
        case "PASSWORD" | "PASSWORD_SRP" if password.now().nonEmpty =>
          run(username)(
            CognitoIdp.respondToChallenge(
              "PASSWORD",
              challenge.session,
              Map("USERNAME" -> username, "PASSWORD" -> password.now())
            )
          )

        /* The pool offering a choice instead of honouring the preference. Answering with PASSWORD
         * keeps this page's ordering rather than dropping the user into a factor picker.
         */
        case "SELECT_CHALLENGE" if challenge.availableChallenges.contains("PASSWORD") =>
          run(username)(
            CognitoIdp.respondToChallenge(
              "SELECT_CHALLENGE",
              challenge.session,
              Map("USERNAME" -> username, "ANSWER" -> "PASSWORD")
            )
          )

        case other =>
          problem.set(
            Some(s"This account needs a sign-in step this page does not support ($other). Try resetting your password.")
          )
      }
  }

  private def succeed(tokens: CognitoIdp.Tokens): Unit = {
    Auth.storeTokens(tokens)
    reset()
    Store.signedIn.set(true)
    Store.loadAll()
  }

  /** Back to an empty form. Called on success so that the password does not sit in a `Var` for
    * the rest of the session, and on "start again" so a half-finished challenge is not resumed
    * with a spent session.
    */
  private def reset(): Unit = {
    stage.set(Stage.Credentials)
    password.set("")
    code.set("")
    newPassword.set("")
    problem.set(None)
  }

  /** What to put in front of the user for a failure.
    *
    * Cognito's own wording is used for anything not named here: those strings are written to be
    * read by end users, and a second copy of its error catalogue is not worth maintaining. The
    * exceptions are the few where the right thing to say includes what to do next.
    */
  private def explain(error: Throwable): String = error match {
    // Deliberately does not distinguish a bad address from a bad password: the pool client sets
    // `prevent_user_existence_errors`, and saying more here would undo it on the client side.
    case CognitoIdp.IdpError("NotAuthorizedException", _) =>
      "Incorrect email or password."
    case CognitoIdp.IdpError("UserNotFoundException", _) =>
      "Incorrect email or password."
    case CognitoIdp.IdpError("UserNotConfirmedException", _) =>
      "This account has not been confirmed yet. Check your email for the confirmation link."
    case CognitoIdp.IdpError("PasswordResetRequiredException", _) =>
      "This account needs a new password. Use “Forgot your password?” below."
    case CognitoIdp.IdpError("CodeMismatchException", _) =>
      "That code is not right. Check it and try again."
    case CognitoIdp.IdpError("ExpiredCodeException", _) =>
      "That code has expired. Start again to have a new one sent."
    // The message field, not getMessage: IdpError's own message prefixes the exception type,
    // and "InvalidPasswordException: Password did not conform..." is not a sentence to show a
    // user. Cognito's message on its own is written to be read.
    case CognitoIdp.IdpError("InvalidPasswordException", message) =>
      s"That password does not meet the pool's requirements: $message"
    case CognitoIdp.IdpError(_, message) => message
    case _: CognitoIdp.IdpUnavailable =>
      "Could not reach the sign-in service. Check your connection and try again."
    case other => other.getMessage
  }

  // -------------------------------------------------------------------------
  // The form
  // -------------------------------------------------------------------------

  /** The button that sends the step. `busy` already disables it — this adds the spinner, since a
    * disabled button on its own says "not now" rather than "waiting for an answer", and the round
    * trip to Cognito is the slowest thing on this page.
    */
  private def submit(label: String): HtmlElement =
    button(
      tpe := "submit",
      disabled <-- busy.signal,
      child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
      label
    )

  def view: HtmlElement =
    div(
      cls := "card sign-in",
      child <-- problem.signal.map {
        case Some(message) => div(cls := "error", message)
        case None          => emptyNode
      },
      child <-- stage.signal.map {
        case Stage.Credentials      => credentials
        case s: Stage.Code          => codeEntry(s)
        case s: Stage.NewPassword   => newPasswordEntry(s)
      }
    )

  private def credentials: HtmlElement =
    form(
      // The browser's own submit is what makes Enter work in either field, and what gets password
      // managers to offer to fill and to save. preventDefault, or the page reloads.
      onSubmit.preventDefault --> (_ => withPassword()),
      h2("Sign In"),
      label("Email", input(tpe := "email", autoComplete := "username", value <-- email, onInput.mapToValue --> email)),
      label(
        "Password",
        input(
          tpe := "password",
          autoComplete := "current-password",
          value <-- password,
          onInput.mapToValue --> password
        )
      ),
      submit("Sign in"),
      // The passwordless route, kept but not put first — which is the whole reason this form
      // exists instead of a redirect to managed login.
      div(
        cls := "alternatives",
        button(
          tpe := "button",
          cls := "link",
          "Email me a code instead",
          disabled <-- busy.signal,
          onClick --> (_ => withEmailCode())
        ),
        button(
          tpe := "button",
          cls := "link",
          "Forgot your password?",
          onClick --> (_ => Auth.hostedForgotPassword().failed.foreach(Store.report))
        ),
        button(
          tpe := "button",
          cls := "link",
          "Create an account",
          onClick --> (_ => Auth.hostedSignUp().failed.foreach(Store.report))
        )
      )
    )

  private def codeEntry(stageNow: Stage.Code): HtmlElement =
    form(
      onSubmit.preventDefault --> (_ => answerCode(stageNow)),
      h2("Enter Your Code"),
      p(
        stageNow.deliveredTo match {
          case Some(destination) => s"We sent a sign-in code to $destination."
          case None              => "We sent you a sign-in code."
        }
      ),
      label(
        "Code",
        input(
          tpe := "text",
          // Lets phones offer the code straight from the notification.
          autoComplete := "one-time-code",
          inputMode := "numeric",
          value <-- code,
          onInput.mapToValue --> code
        )
      ),
      submit("Sign in"),
      div(cls := "alternatives", button(tpe := "button", cls := "link", "Start again", onClick --> (_ => reset())))
    )

  private def newPasswordEntry(stageNow: Stage.NewPassword): HtmlElement =
    form(
      onSubmit.preventDefault --> (_ => answerNewPassword(stageNow)),
      h2("Choose a Password"),
      p("This account is signed in with a temporary password. Pick a permanent one to continue."),
      label(
        "New password",
        input(
          tpe := "password",
          autoComplete := "new-password",
          value <-- newPassword,
          onInput.mapToValue --> newPassword
        )
      ),
      submit("Save and sign in"),
      div(cls := "alternatives", button(tpe := "button", cls := "link", "Start again", onClick --> (_ => reset())))
    )
}
