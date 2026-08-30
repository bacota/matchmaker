package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** The account menu: the three things a player can change about themselves.
  *
  * They are three because they live in two different places, and the split is not arbitrary:
  *
  *   - the *nickname* is matchmaker's, the name other players see, and goes through the API;
  *   - the *email* and *password* belong to the Cognito identity, and are changed against
  *     Cognito directly from this page — the same reasoning as `SignIn`. Matchmaker keeps no
  *     copy of either, so there is nothing here to keep the two sides in step.
  *
  * Each form reports next to itself rather than into `Store.error`. A failure here belongs to the
  * field the user is typing in, and the header banner is both far away and easy to lose behind
  * the open menu.
  */
object Account {

  /** Whether the menu is open. Closed on sign-out with everything else in it, so a later sign-in
    * does not start with someone else's half-typed address on screen.
    */
  val open: Var[Boolean] = Var(false)

  def close(): Unit = {
    open.set(false)
    reset()
  }

  private def reset(): Unit = {
    nickname.set("")
    email.set("")
    emailCode.set("")
    emailStage.set(EmailStage.Idle)
    currentPassword.set("")
    newPassword.set("")
    outcomes.foreach(_.set(None))
  }

  /** How far an email change has got. Cognito does not change the address on the first call: the
    * pool auto-verifies email, so it mails a code to the new address and holds the change until
    * that code comes back.
    */
  private enum EmailStage {
    case Idle
    case Sent(destination: Option[String])
  }

  /** What one form has to say for itself: nothing yet, a failure, or a confirmation. Kept per
    * form so that renaming successfully does not wipe the message the email form just produced.
    */
  private case class Outcome(failed: Boolean, message: String)

  private val nicknameOutcome: Var[Option[Outcome]] = Var(None)
  private val emailOutcome: Var[Option[Outcome]] = Var(None)
  private val passwordOutcome: Var[Option[Outcome]] = Var(None)
  private val outcomes = Seq(nicknameOutcome, emailOutcome, passwordOutcome)

  private val nickname: Var[String] = Var("")
  private val email: Var[String] = Var("")
  private val emailCode: Var[String] = Var("")
  private val emailStage: Var[EmailStage] = Var(EmailStage.Idle)
  private val currentPassword: Var[String] = Var("")
  private val newPassword: Var[String] = Var("")

  // -------------------------------------------------------------------------
  // The three changes
  // -------------------------------------------------------------------------

  private def saveNickname(busy: Var[Boolean]): Unit = {
    val wanted = nickname.now().trim

    if (wanted.isEmpty) nicknameOutcome.set(Some(Outcome(true, "Enter a nickname.")))
    else {
      nicknameOutcome.set(None)
      busy.set(true)
      ApiClient.updateNickname(wanted).onComplete { result =>
        busy.set(false)
        result match {
          case Success(player) =>
            // The header shows the nickname, so the change has to reach the store or the menu
            // would report a rename the rest of the page disagrees with.
            Store.player.set(Store.PlayerState.Registered(player))
            nickname.set("")
            nicknameOutcome.set(Some(Outcome(false, s"You are now ${player.nickname}.")))
          case Failure(error) =>
            nicknameOutcome.set(Some(Outcome(true, explain(error))))
        }
      }
    }
  }

  /** Asks Cognito to change the address, which starts the verification rather than finishing the
    * change. Until the code below is answered, the old address is still the one that signs in.
    */
  private def sendEmailCode(busy: Var[Boolean]): Unit = {
    val wanted = email.now().trim

    if (wanted.isEmpty) emailOutcome.set(Some(Outcome(true, "Enter an email address.")))
    else
      withAccessToken(emailOutcome, busy) { token =>
        CognitoIdp.updateEmail(token, wanted).map { destination =>
          emailStage.set(EmailStage.Sent(destination))
          emailOutcome.set(None)
        }
      }
  }

  private def confirmEmail(busy: Var[Boolean]): Unit = {
    val code = emailCode.now().trim

    if (code.isEmpty) emailOutcome.set(Some(Outcome(true, "Enter the code we sent.")))
    else
      withAccessToken(emailOutcome, busy) { token =>
        CognitoIdp.verifyEmail(token, code).map { _ =>
          val changed = email.now().trim
          emailStage.set(EmailStage.Idle)
          email.set("")
          emailCode.set("")
          // Worth saying explicitly: the address is the username on this pool, so the next sign-in
          // is with the new one, and a player who does not know that has locked themselves out as
          // far as they can tell.
          emailOutcome.set(Some(Outcome(false, s"Your email address is now $changed. Sign in with it next time.")))
        }
      }
  }

  private def savePassword(busy: Var[Boolean]): Unit =
    if (currentPassword.now().isEmpty || newPassword.now().isEmpty)
      passwordOutcome.set(Some(Outcome(true, "Enter your current password and the new one.")))
    else
      withAccessToken(passwordOutcome, busy) { token =>
        CognitoIdp.changePassword(token, currentPassword.now(), newPassword.now()).map { _ =>
          currentPassword.set("")
          newPassword.set("")
          // The session is not ended: Cognito leaves the existing tokens valid, and signing the
          // user out of the tab they are working in would be a surprising cost for a change they
          // made deliberately.
          passwordOutcome.set(Some(Outcome(false, "Your password has been changed.")))
        }
      }

  /** Runs a Cognito account operation with an access token, obtaining one first.
    *
    * The API calls carry the ID token; these do not accept it. The access token is refreshed on
    * demand rather than assumed present, since a session that began before it was stored has
    * only the other two.
    */
  private def withAccessToken(outcome: Var[Option[Outcome]], busy: Var[Boolean])(
      action: String => Future[Unit]
  ): Unit = {
    outcome.set(None)
    busy.set(true)

    Auth
      .freshAccessToken()
      .flatMap {
        case Some(token) => action(token)
        case None =>
          Future.failed(new IllegalStateException("Your session has expired. Sign in again to change this."))
      }
      .onComplete { result =>
        busy.set(false)
        result.failed.foreach(error => outcome.set(Some(Outcome(true, explain(error)))))
      }
  }

  /** As `SignIn.explain`: Cognito's own wording for anything not named here, since those strings
    * are written for end users. Named separately are the ones where what to do next is not
    * obvious from the message.
    */
  private def explain(error: Throwable): String = error match {
    case CognitoIdp.IdpError("NotAuthorizedException", _) =>
      "That is not your current password."
    case CognitoIdp.IdpError("LimitExceededException", _) =>
      "Too many attempts. Wait a few minutes and try again."
    case CognitoIdp.IdpError("CodeMismatchException", _) =>
      "That code is not right. Check it and try again."
    case CognitoIdp.IdpError("ExpiredCodeException", _) =>
      "That code has expired. Send yourself a new one."
    case CognitoIdp.IdpError("AliasExistsException", _) =>
      "There is already an account with that email address."
    // The message field, not getMessage: IdpError's own message prefixes the exception type,
    // and "InvalidPasswordException: Password did not conform..." is not a sentence to show a
    // user. Cognito's message on its own is written to be read.
    case CognitoIdp.IdpError("InvalidPasswordException", message) =>
      s"That password does not meet the pool's requirements: $message"
    case CognitoIdp.IdpError(_, message) => message
    case _: CognitoIdp.IdpUnavailable =>
      "Could not reach the sign-in service. Check your connection and try again."
    case ApiError(409, _) => "That nickname is taken."
    case ApiError(_, message) => message
    case other => Option(other.getMessage).getOrElse(other.toString)
  }

  // -------------------------------------------------------------------------
  // The menu
  // -------------------------------------------------------------------------

  def view: HtmlElement = {
    // Held so that closing the panel can put focus back where it came from. A keyboard user who
    // presses Escape and lands at the top of the document has been sent somewhere, not returned.
    var trigger: Option[dom.html.Element] = None

    div(
      cls := "account",
      button(
        cls := "link",
        "Account",
        aria.expanded <-- open.signal,
        htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
        onMountCallback(context => trigger = Some(context.thisNode.ref)),
        onClick --> (_ => if (open.now()) close() else open.set(true))
      ),
      // Escape closes it from anywhere inside, and a click anywhere outside does the same. Both
      // are listened for on the document, because the panel is not what has focus when either
      // happens — and both are bound here rather than on the panel so they are torn down with
      // this element rather than left behind by it.
      documentEvents(_.onKeyDown).filter(e => open.now() && e.key == "Escape") --> { _ =>
        close()
        trigger.foreach(_.focus())
      },
      documentEvents(_.onClick).filter(_ => open.now()) --> { event =>
        val target = event.target
        val inside = target match {
          case node: dom.Node => panelRoot.exists(_.contains(node)) || trigger.exists(_.contains(node))
          case _              => false
        }
        if (!inside) close()
      },
      child <-- open.signal.map(if (_) menu else emptyNode)
    )
  }

  /* The rendered panel, so the outside-click test has something to ask about. Set when the panel
   * mounts and cleared when it unmounts, which is the only time either happens. */
  private var panelRoot: Option[dom.Node] = None

  private def menu: HtmlElement =
    div(
      cls := "account-menu card",
      // A dialog by behaviour — it is over the page, and Escape closes it — so it says so, and
      // is named by the heading it already had rather than by a label repeating it.
      role := "dialog",
      aria.labelledBy := "account-menu-heading",
      onMountCallback(context => panelRoot = Some(context.thisNode.ref)),
      onUnmountCallback(_ => panelRoot = None),
      // Focus moves in with the panel: without this the keyboard is still on the trigger, and
      // the fields are reached by tabbing forward through a panel that may not be next.
      inContext(node => onMountCallback(_ => node.ref.focus())),
      // Focusable so that focus can be moved to it, but not a tab stop of its own.
      tabIndex := -1,
      h2(idAttr := "account-menu-heading", "Your Account"),
      nicknameForm,
      // Nothing to change at Cognito when there is no Cognito: local mode authenticates with a
      // header, and offering forms that could only fail would be worse than leaving them out.
      if (Config.current.headerAuth)
        p(cls := "empty", "Email and password are managed by the sign-in service, which is not in use locally.")
      else
        div(emailForm, passwordForm),
      div(cls := "alternatives", button(tpe := "button", cls := "link", "Close", onClick --> (_ => close())))
    )

  private def nicknameForm: HtmlElement = {
    val busy = Var(false)

    form(
      cls := "account-section",
      onSubmit.preventDefault --> (_ => if (!busy.now()) saveNickname(busy)),
      h3("Nickname"),
      p(cls := "detail", child.text <-- Store.currentPlayer.map(_.map(p => s"Other players see you as ${p.nickname}.").getOrElse(""))),
      label(
        "New nickname",
        input(
          tpe := "text",
          autoComplete := "nickname",
          placeholder <-- Store.currentPlayer.map(_.map(_.nickname).getOrElse("")),
          controlled(value <-- nickname.signal, onInput.mapToValue --> nickname)
        )
      ),
      submit("Save nickname", busy),
      report(nicknameOutcome)
    )
  }

  private def emailForm: HtmlElement = {
    val busy = Var(false)

    form(
      cls := "account-section",
      onSubmit.preventDefault --> { _ =>
        if (!busy.now()) emailStage.now() match {
          case EmailStage.Idle    => sendEmailCode(busy)
          case EmailStage.Sent(_) => confirmEmail(busy)
        }
      },
      h3("Email Address"),
      label(
        "New email address",
        input(
          tpe := "email",
          autoComplete := "email",
          // Locked once the code is out: the code was mailed to what this said at the time, and
          // an address edited underneath it would confirm one address having verified another.
          disabled <-- emailStage.signal.map(_ != EmailStage.Idle),
          controlled(value <-- email.signal, onInput.mapToValue --> email)
        )
      ),
      child <-- emailStage.signal.map {
        case EmailStage.Idle => emptyNode
        case EmailStage.Sent(destination) =>
          div(
            p(
              cls := "detail",
              destination match {
                case Some(masked) => s"We sent a code to $masked. Enter it to finish the change."
                case None         => "We sent a code to your new address. Enter it to finish the change."
              }
            ),
            label(
              "Code",
              input(
                tpe := "text",
                autoComplete := "one-time-code",
                inputMode := "numeric",
                controlled(value <-- emailCode.signal, onInput.mapToValue --> emailCode)
              )
            )
          )
      },
      child <-- emailStage.signal.map {
        case EmailStage.Idle    => submit("Send verification code", busy)
        case EmailStage.Sent(_) => submit("Confirm new address", busy)
      },
      report(emailOutcome)
    )
  }

  private def passwordForm: HtmlElement = {
    val busy = Var(false)

    form(
      cls := "account-section",
      onSubmit.preventDefault --> (_ => if (!busy.now()) savePassword(busy)),
      h3("Password"),
      label(
        "Current password",
        input(
          tpe := "password",
          autoComplete := "current-password",
          controlled(value <-- currentPassword.signal, onInput.mapToValue --> currentPassword)
        )
      ),
      label(
        "New password",
        input(
          tpe := "password",
          autoComplete := "new-password",
          controlled(value <-- newPassword.signal, onInput.mapToValue --> newPassword)
        )
      ),
      submit("Change password", busy),
      report(passwordOutcome)
    )
  }

  /** The submit button of one form. Not `busyButton`: these are real form submits, so that Enter
    * works in the fields and password managers offer to fill and to save.
    */
  private def submit(label: String, busy: Var[Boolean]): HtmlElement =
    button(
      tpe := "submit",
      disabled <-- busy.signal,
      child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
      label
    )

  /** How a change went, said in the panel and said out loud.
    *
    * The container carries the live region rather than the message, because a region has to be
    * in the document before the text appears in it — one announced when it is added, with the
    * text already inside, is a region that has not changed and is read out by nobody. A failure
    * is `alert` and a success is the politer `status`: one interrupts, the other waits for a
    * pause.
    */
  private def report(outcome: Var[Option[Outcome]]): HtmlElement =
    div(
      aria.live <-- outcome.signal.map {
        case Some(Outcome(true, _)) => "assertive"
        case _                      => "polite"
      },
      child <-- outcome.signal.map {
        case Some(Outcome(true, message))  => div(cls := "error", role := "alert", message)
        case Some(Outcome(false, message)) => div(cls := "notice", role := "status", message)
        case None                          => emptyNode
      }
    )
}
