package com.vivi.matchmaker.ui

import scala.concurrent.Future
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
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

    /** An offer to carry what is about to be saved down to the level below it.
      *
      * A checkbox beside the save button rather than a separate button, because it is part of the same sentence: the
      * player is saying "this change, and make it there too". Since a seat carries its own answers, changing a game's
      * settings leaves the matches already being played alone — which is what makes these offers worth making rather
      * than being how it works anyway.
      *
      * What travels is the change, not the form: the questions a save leaves alone are left alone at every level below
      * it, so a mute on one match survives a change to something else. Every caption says so, because a box reading
      * "use these everywhere" would be promising something else.
      *
      * `chosen` is read by whoever supplied the cascade, inside its own save; `form` only renders it. `shown` is for an
      * offer that only makes sense once another has been taken — the account form's "and in all my matches", which
      * follows from "in all my games" — and an offer that goes away is unchecked on the way out, so a box the player
      * can no longer see cannot still be part of what they save.
      */
    case class Cascade(
        label: String,
        detail: String,
        chosen: Var[Boolean] = Var(false),
        shown: Signal[Boolean] = Val(true)
    )

    /** What a form asks *after* the player has pressed save, rather than underneath the button.
      *
      * The cascades are the reason this exists. They are a second sentence about a save — "and in my games too" — and
      * on a form of eight questions they read as two more questions, which is how a player ends up scrolling past them
      * to find the button. Behind the button they are the only thing on screen at the moment they apply.
      *
      * Before the save rather than after it, which is not a detail: a cascade carries what a save *changed*, so saving
      * first and offering to cascade afterwards would offer to carry a change that had already been recorded and had
      * nothing left to carry (`NotificationService.updateMine` says the same thing from the other end).
      *
      * `alongside` is whatever else the screen wants in that dialog, rendered under the save — the account panel puts
      * its per-game form there, because "not for all my games, just this one" is the other thing a player thinks at
      * this exact moment. It is not part of this form's save, and has its own.
      */
    case class Deferred(heading: String, alongside: Seq[HtmlElement] = Nil)

    /* A cascade as a control. The caption wraps the box, so it is named without an id, for the same
     * reason `question` gives -- a page may render this form more than once. */
    private def offer(cascade: Cascade): HtmlElement =
        div(
          cascade.shown.changes.filterNot(identity) --> (_ => cascade.chosen.set(false)),
          child <-- cascade.shown.map {
              case false => emptyNode
              case true =>
                  label(
                    cls := "cascade",
                    input(
                      tpe := "checkbox",
                      checked <-- cascade.chosen.signal,
                      onInput.mapToChecked --> cascade.chosen
                    ),
                    span(cascade.label),
                    // A span inside the label for the same reason the questions' hints are: it says
                    // what the box will actually touch, and belongs to the control rather than sitting
                    // near it.
                    span(cls := "detail hint", cascade.detail)
                  )
          }
        )

    /** The questions, in the order a player meets the events they are about.
      *
      * All eight unless a screen says otherwise. `kinds` is for a form that cannot act on some of them — the per-match
      * one, where the five questions about challenges and about the start are all about things that have already
      * happened. A question left out is not answered differently; the answer the form was seeded with travels back
      * untouched.
      */
    def editor(
        preferences: Var[NotificationPreferences],
        withDefault: Boolean = true,
        kinds: Seq[NotificationType] = NotificationType.values.toSeq
    ): HtmlElement =
        div(kinds.map(question(_, preferences, withDefault)))

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
      *
      * `cascades` are rendered between the questions and the button, and are not passed to `save`: whoever offered one
      * reads its `chosen` inside their own save, because what it means is a parameter of their request and not
      * something this form could carry out.
      */
    def form(
        heading: String,
        explanation: String,
        preferences: Var[NotificationPreferences],
        withDefault: Boolean = true,
        saveLabel: String = "Save",
        cascades: Seq[Cascade] = Nil,
        kinds: Seq[NotificationType] = NotificationType.values.toSeq,
        deferred: Option[Deferred] = None
    )(save: NotificationPreferences => Future[Unit]): HtmlElement = {
        val busy = Var(false)
        val outcome: Var[Option[Outcome]] = Var(None)

        /* Whether the dialog is up. Only ever true when `deferred` is set, and the same `Var` is what
         * decides where the outcome is reported: the message belongs wherever the button that produced
         * it is, and two live regions holding one message announce it twice. */
        val asking = Var(false)

        /* The button the dialog was opened from, so closing it can put focus back. A keyboard user
         * returned to the top of the document has been sent somewhere, not returned -- the account
         * panel's own trigger is held for the same reason. */
        var trigger: Option[dom.html.Element] = None

        def perform(): Unit =
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

        def dismiss(): Unit = {
            asking.set(false)
            trigger.foreach(_.focus())
        }

        def saveButton(press: () => Unit, isTrigger: Boolean): HtmlElement =
            button(
              tpe := "button",
              disabled <-- busy.signal.combineWith(preferences.signal).map { case (waiting, current) =>
                  // On the game form every question must be answered before there is anything to save;
                  // elsewhere "unanswered" is itself an answer, so only the request blocks the button.
                  // Only the questions being asked can hold the button: one that is not on screen cannot
                  // be answered, so waiting for it would disable the button with nothing to click.
                  waiting || (!withDefault && current.unsaid.exists(kinds.contains))
              },
              child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
              saveLabel,
              if (isTrigger) onMountCallback(context => trigger = Some(context.thisNode.ref)) else emptyMod,
              onClick --> (_ => press())
            )

        /* The dialog: the cascades, a button that does the save this time, and whatever the screen
         * sent along. Stays up after a successful save rather than closing on it, because what it
         * holds below the button is the next thing a player may want and closing would take it away
         * -- "Saved." above it says the save happened. */
        def dialog(ask: Deferred): HtmlElement =
            div(
              cls := "modal-scrim",
              // The scrim is the gesture "not this", and the card inside it is not the scrim. Compared
              // against `currentTarget` rather than tested for containment, because that is exactly the
              // question: did the click land on the backdrop itself.
              onClick --> (event => if (event.target == event.currentTarget) dismiss()),
              div(
                cls := "modal card",
                role := "dialog",
                htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
                aria.label := ask.heading,
                // Focusable so focus can be moved in, but not a tab stop of its own.
                tabIndex := -1,
                inContext(node => onMountCallback(_ => node.ref.focus())),
                // Escape closes this and only this. The account panel listens for Escape on the
                // document, so an unstopped one would take the whole panel down and lose the dialog
                // with it -- stopping it here is what makes the inner layer the one that answers.
                onKeyDown.filter(_.key == "Escape") --> { event =>
                    event.stopPropagation()
                    dismiss()
                },
                h3(ask.heading),
                cascades.map(offer),
                saveButton(() => perform(), isTrigger = false),
                report(outcome),
                ask.alongside,
                div(
                  cls := "alternatives",
                  button(tpe := "button", cls := "link", "Close", onClick --> (_ => dismiss()))
                )
              )
            )

        val tail: Seq[Modifier[HtmlElement]] = deferred match {
            case None =>
                Seq(div(cascades.map(offer)), saveButton(() => perform(), isTrigger = false), report(outcome))
            case Some(ask) =>
                Seq(
                  saveButton(() => asking.set(true), isTrigger = true),
                  // Only while the dialog is shut: the message is about the button that was pressed, and
                  // the dialog reports it there while it is the one on screen.
                  child <-- asking.signal.map(if (_) emptyNode else report(outcome)),
                  child <-- asking.signal.map(if (_) dialog(ask) else emptyNode)
                )
        }

        div(
          cls := "account-section",
          h3(heading),
          p(cls := "detail", explanation),
          editor(preferences, withDefault, kinds),
          tail
        )
    }
}
