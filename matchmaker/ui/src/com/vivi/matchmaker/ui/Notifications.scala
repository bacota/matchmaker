package com.vivi.matchmaker.ui

import scala.concurrent.Future
import com.raquo.laminar.api.L.{*, given}
import com.vivi.matchmaker.model._

/** The forms a player sets their notification preferences with, wherever they appear.
  *
  * Three screens ask for the same eight questions and differ only in what an unanswered one falls back to — the account
  * panel (everywhere, and per game), a match row (this match), and the admin's game form (the game's own defaults, the
  * one place where "unanswered" is not allowed). So the controls are here rather than in any of them, and each screen
  * supplies the heading, the fallback and the save.
  */
object Notifications {

    /** The value of the "no opinion" option. Empty rather than a word, because a `select` reports its option's `value`
      * and the empty string is the one value no answer can collide with.
      */
    private val useDefault = ""

    /** One question, as a labelled dropdown.
      *
      * A `select` rather than three radio buttons: the third option is a real answer here, not an escape from the other
      * two, and eight groups of three radios is a very tall form on a phone. The caption wraps the control, so the
      * control is named without an id that would have to be unique across a page that renders this form more than once
      * — the same reasoning as `Main.field`.
      *
      * @param withDefault
      *   whether "Use Default" is offered. False on the game form, where the game is the default and there is nothing
      *   below it to defer to.
      */
    private def question(
        kind: NotificationType,
        preferences: Var[NotificationPreferences],
        withDefault: Boolean
    ): HtmlElement =
        label(
          cls := "field",
          kind.label,
          select(
            // Bound to the preference rather than left to the DOM, so that a form re-seeded from the
            // server — an answer arriving, or another game being picked — is actually re-rendered.
            value <-- preferences.signal.map(current =>
                current(kind) match {
                    case Some(true)  => "yes"
                    case Some(false) => "no"
                    case None        => useDefault
                }
            ),
            onChange.mapToValue --> { chosen =>
                val choice = chosen match {
                    case "yes" => Some(true)
                    case "no"  => Some(false)
                    case _     => None
                }
                preferences.update(_.updated(kind, choice))
            },
            // The unanswered option is first and is what an unanswered question shows. On the game
            // form it is still present but says something else: an admin has not chosen yet, and a
            // form that opened with "Yes" already selected would collect a choice nobody made.
            option(value := useDefault, if (withDefault) "Use Default" else "Choose…"),
            option(value := "yes", "Yes"),
            option(value := "no", "No")
          ),
          // A span, not a paragraph: a label may only contain phrasing content, and the hint has to
          // be inside the label to be part of the control's accessible name rather than text merely
          // sitting near it. `label.field > .hint` is what puts it on its own line.
          span(cls := "detail hint", kind.detail)
        )

    /** All eight questions, in the order a player meets the events they are about. */
    def editor(preferences: Var[NotificationPreferences], withDefault: Boolean = true): HtmlElement =
        div(NotificationType.values.toSeq.map(question(_, preferences, withDefault)))

    /** What one form has to say for itself. As `Account.Outcome`, and for the same reason: these forms live inside
      * other screens, and a failure saving one belongs beside it rather than in the banner at the top of the page.
      */
    private case class Outcome(failed: Boolean, message: String)

    /* Says how a save went, out loud as well as on screen. The live region is on the container, not
     * on the message: a region added to the document with its text already in it has not changed and
     * is announced by nobody. Failures interrupt, confirmations wait for a pause. */
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

    /** A form over one level of preference: the eight questions, a save button, and what came of it.
      *
      * The save is handed the current answers and reports when it has finished. Reported here rather than through
      * `Store.error` for the reason `Account` gives: this form is inside a panel or a row, and a banner at the top of
      * the page is both far away and easily lost behind what is over it.
      */
    def form(
        heading: String,
        explanation: String,
        preferences: Var[NotificationPreferences],
        withDefault: Boolean = true,
        saveLabel: String = "Save"
    )(save: NotificationPreferences => Future[Unit]): HtmlElement = {
        val busy = Var(false)
        val outcome: Var[Option[Outcome]] = Var(None)

        div(
          cls := "account-section",
          h3(heading),
          p(cls := "detail", explanation),
          editor(preferences, withDefault),
          button(
            tpe := "button",
            disabled <-- busy.signal.combineWith(preferences.signal).map { case (waiting, current) =>
                // On the game form every question must be answered before there is anything to save;
                // elsewhere "unanswered" is itself an answer, so only the request blocks the button.
                waiting || (!withDefault && current.unsaid.nonEmpty)
            },
            child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
            saveLabel,
            onClick --> { _ =>
                if (!busy.now()) {
                    busy.set(true)
                    outcome.set(None)
                    save(preferences.now()).onComplete { result =>
                        busy.set(false)
                        outcome.set(Some(result match {
                            case scala.util.Success(_) => Outcome(false, "Saved.")
                            case scala.util.Failure(error) =>
                                Outcome(true, Option(error.getMessage).getOrElse(error.toString))
                        }))
                    }(scala.concurrent.ExecutionContext.Implicits.global)
                }
            }
          ),
          report(outcome)
        )
    }
}
