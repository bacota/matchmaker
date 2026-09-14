package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model.{GameId, NotificationPreferences, NotificationSettings}

/** The account menu: the three things a player can change about themselves.
  *
  * They are three because they live in two different places, and the split is not arbitrary:
  *
  *   - the *nickname* is matchmaker's, the name other players see, and goes through the API;
  *   - the *email* and *password* belong to the Cognito identity, and are changed against Cognito directly from this
  *     page — the same reasoning as `SignIn`. Matchmaker keeps no copy of the password. It does keep one of the
  *     address, because that is where it sends notifications from a lambda that has no token to read one out of — but
  *     this form does not report the change to the API. It cannot usefully: the session's token still carries the old
  *     address until Cognito issues a new one. The copy is reconciled at the next sign-in instead, from that token's
  *     claim, which is the only account of the address either side can verify. See `Store.syncEmail`.
  *
  * Each form reports next to itself rather than into `Store.error`. A failure here belongs to the field the user is
  * typing in, and the header banner is both far away and easy to lose behind the open menu.
  */
object Account {

    /** Whether the menu is open. Closed on sign-out with everything else in it, so a later sign-in does not start with
      * someone else's half-typed address on screen.
      */
    val open: Var[Boolean] = Var(false)

    def close(): Unit = {
        open.set(false)
        reset()
    }

    private def reset(): Unit = {
        nickname.set("")
        currentEmail.set(None)
        email.set("")
        emailCode.set("")
        emailStage.set(EmailStage.Idle)
        currentPassword.set("")
        newPassword.set("")
        outcomes.foreach(_.set(None))
    }

    /** How far an email change has got. Cognito does not change the address on the first call: the pool auto-verifies
      * email, so it mails a code to the new address and holds the change until that code comes back.
      */
    private enum EmailStage {
        case Idle
        case Sent(destination: Option[String])
    }

    /** What one form has to say for itself: nothing yet, a failure, or a confirmation. Kept per form so that renaming
      * successfully does not wipe the message the email form just produced.
      */
    private case class Outcome(failed: Boolean, message: String)

    private val nicknameOutcome: Var[Option[Outcome]] = Var(None)
    private val emailOutcome: Var[Option[Outcome]] = Var(None)
    private val passwordOutcome: Var[Option[Outcome]] = Var(None)
    private val outcomes = Seq(nicknameOutcome, emailOutcome, passwordOutcome)

    private val nickname: Var[String] = Var("")

    /** The address the player signs in with, as the form shows it back to them.
      *
      * Held rather than read from `Auth` where it is rendered, for the one case where the two disagree: a change
      * confirmed in this tab is the truth, and the ID token will not carry it until the session refreshes. Filled when
      * the form mounts and corrected when a change completes.
      */
    private val currentEmail: Var[Option[String]] = Var(None)
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

    /** Asks Cognito to change the address, which starts the verification rather than finishing the change. Until the
      * code below is answered, the old address is still the one that signs in.
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
                    currentEmail.set(Some(changed))
                    emailStage.set(EmailStage.Idle)
                    email.set("")
                    emailCode.set("")
                    // Worth saying explicitly: the address is the username on this pool, so the next sign-in
                    // is with the new one, and a player who does not know that has locked themselves out as
                    // far as they can tell.
                    //
                    // Nothing is reported to the API here. Matchmaker keeps a copy of the address to send
                    // notifications to, and it is brought up to date at the next sign-in, from the claim of
                    // the token that sign-in issues — see `Store.syncEmail`. Reporting it now would mean
                    // sending an address that this session's own token still disagrees with, and the token
                    // is the only thing either side can check.
                    emailOutcome.set(
                      Some(Outcome(false, s"Your email address is now $changed. Sign in with it next time."))
                    )
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
      * The API calls carry the ID token; these do not accept it. The access token is refreshed on demand rather than
      * assumed present, since a session that began before it was stored has only the other two.
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

    /** As `SignIn.explain`: Cognito's own wording for anything not named here, since those strings are written for end
      * users. Named separately are the ones where what to do next is not obvious from the message.
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
        case ApiError(409, _)     => "That nickname is taken."
        case ApiError(_, message) => message
        case other                => Option(other.getMessage).getOrElse(other.toString)
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
          notificationForms,
          // Nothing to change at Cognito when there is no Cognito: local mode authenticates with a
          // header, and offering forms that could only fail would be worse than leaving them out.
          if (Config.current.headerAuth)
              p(cls := "empty", "Email and password are managed by the sign-in service, which is not in use locally.")
          else
              div(emailForm, passwordForm),
          div(cls := "alternatives", button(tpe := "button", cls := "link", "Close", onClick --> (_ => close())))
        )

    /** What the player wants to be told about, in general and per game.
      *
      * In the account panel rather than on a screen of its own because it is a thing about the player, like their name
      * and their address, and because the panel is where a player already goes to change one of those. The per-match
      * level is not here: it belongs to the match, and lives on its row.
      *
      * Fetched when the panel opens rather than with the home screen's lists. Most sessions never open it, and five
      * requests at sign-in is already enough of them.
      */
    private def notificationForms: HtmlElement =
        div(
          onMountCallback(_ => Store.loadNotifications()),
          child <-- Store.notificationSettings.signal.map {
              case Some(settings) => notificationSections(settings)
              case None           => p(cls := "detail", "Loading your notification settings…")
          }
        )

    /* Built from the settings as fetched, so every control starts on what the server holds.
     *
     * The three `Var`s are local to this element, which is rebuilt each time the panel opens -- so
     * closing the panel on a half-changed form discards it, which is what closing a panel should do.
     * `perGame` is kept and updated on save because the game picker comes back to games it has
     * already saved, and a map that was not updated would show them the values it was opened with. */
    private def notificationSections(settings: NotificationSettings): HtmlElement = {
        val overall = Var(settings.player)
        val perGame = Var(settings.games.map(g => g.gameId -> g.preferences).toMap)
        val chosen: Var[Option[GameId]] = Var(None)

        div(
          Notifications.form(
            "Notifications",
            "What we email you about, unless you say otherwise for a particular game or match.",
            overall,
            saveLabel = "Save notifications"
          )(preferences =>
              ApiClient.updateNotifications(preferences).map(_ =>
                  Store.notificationSettings.update(_.map(_.copy(player = preferences)))
              )
          ),
          div(
            cls := "account-section",
            h3("One Game"),
            p(
              cls := "detail",
              "Answers for a single game, which win over the ones above. " +
                  "Anything left on \"Use Default\" falls back to them, and then to what the game itself asks for."
            ),
            label(
              cls := "field",
              "Game",
              select(
                value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                onChange.mapToValue --> { raw =>
                    chosen.set(raw.toIntOption.map(GameId.apply))
                },
                option(value := "", "Choose a game"),
                children <-- Store.games.signal.map(
                  _.map(game => option(value := game.gameId.value.toString, game.name)).toList
                )
              )
            ),
            // Rebuilt per game, which is also how the form is re-seeded: a fresh element over a fresh
            // `Var` of that game's answers, rather than one form whose contents have to be swapped
            // underneath it.
            child <-- chosen.signal.map {
                case None => emptyNode
                case Some(gameId) =>
                    val forGame = Var(perGame.now().getOrElse(gameId, NotificationPreferences.unset))
                    Notifications.form(
                      Store.games.now().find(_.gameId == gameId).map(_.name).getOrElse("This game"),
                      "Leave a question on \"Use Default\" to answer it from your settings above.",
                      forGame,
                      saveLabel = "Save for this game"
                    ) { preferences =>
                        ApiClient
                            .updateGameNotifications(gameId, preferences)
                            .map(_ => perGame.update(_.updated(gameId, preferences)))
                    }
            }
          )
        )
    }

    private def nicknameForm: HtmlElement = {
        val busy = Var(false)

        form(
          cls := "account-section",
          onSubmit.preventDefault --> (_ => if (!busy.now()) saveNickname(busy)),
          h3("Nickname"),
          p(
            cls := "detail",
            child.text <-- Store.currentPlayer.map(_.map(p => s"Other players see you as ${p.nickname}.").getOrElse(""))
          ),
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
          // Read on mount rather than once at startup: the panel is built fresh each time it opens,
          // so this is also how a change made earlier in the session is still shown afterwards.
          onMountCallback(_ => currentEmail.set(Auth.email)),
          h3("Email Address"),
          // What the nickname form says above its own field, and for the same reason: a player
          // changing an address should be able to see which one they are changing. Nothing at all
          // when the token does not carry it — an empty line saying "you sign in as" would be worse
          // than no line.
          child <-- currentEmail.signal.map {
              case Some(address) => p(cls := "detail", s"You sign in as $address.")
              case None          => emptyNode
          },
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

    /** The submit button of one form. Not `busyButton`: these are real form submits, so that Enter works in the fields
      * and password managers offer to fill and to save.
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
      * The container carries the live region rather than the message, because a region has to be in the document before
      * the text appears in it — one announced when it is added, with the text already inside, is a region that has not
      * changed and is read out by nobody. A failure is `alert` and a success is the politer `status`: one interrupts,
      * the other waits for a pause.
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
