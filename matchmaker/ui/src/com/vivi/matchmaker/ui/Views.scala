package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** The page's frame -- header, error banner, registration, the menu and the screen it switches between -- and the
  * pieces every screen is built from: labelled fields, busy buttons, refreshable sections, explanations behind an ⓘ.
  */
object Views {

    def app: HtmlElement =
        div(
          cls := "app",
          // Mounted before anything is said into it, and for the life of the page, so that what is said is read out.
          div(cls := "sr-only", role := "status", aria.live := "polite", child.text <-- announcement.signal),
          // Nothing one player was told is left for the next to hear.
          Store.signedIn.signal.changes --> (_ => announcement.set("")),
          header,
          child <-- Store.signedIn.signal.map(if (_) signedInBody else signedOutBody)
        )

    private val announcement = Var("")

    /** Says out loud how an action went whose own control is gone with what it acted on: a row's Cancel that removes
      * the row, a Stop that removes the button. The row going is what a sighted player sees; this is what everyone else
      * is told. Cleared first and set a turn later, so the same words said twice are read out twice.
      *
      * Not set at all if the session that said it ends in that turn: a sign-out clears what was said, and the next
      * player is not told how the last one's action went. An answer that can arrive after a sign-out reaches this
      * through `Store.runSignedIn`, which drops it before it is said.
      */
    private[ui] def announce(text: String): Unit = {
        val signIn = Store.currentSignIn
        announcement.set("")
        dom.window.setTimeout(() => if (Store.isStillSignedInAs(signIn)) announcement.set(text), 50)
    }

    /** A button that starts a request, and says so while it is running.
      *
      * Every one of these calls the API, and some of those calls are slow enough that a click with no visible answer
      * reads as a button that did nothing — so the button disables itself and shows a spinner until the request comes
      * back. The handler is handed the flag to pass to `Store.run`, which clears it however the request ends: a failure
      * re-enables the button rather than leaving it spinning on an answer that already arrived.
      *
      * Busy is said with `aria-disabled` rather than `disabled`: a disabled button drops the keyboard's focus, so a
      * player who pressed it with a key would find themselves back at the top of the page while they wait. The press a
      * busy button refuses is refused by the handler instead. `disabledWhen` is a real `disabled` -- a button that
      * cannot be used yet is one to skip over.
      *
      * `busy` is the button's own unless one is passed in. A button in a row or page that the store's next answer
      * redraws needs one held outside it -- `heldBusy` -- or the redrawn button is pressable again while the first
      * press is still out.
      */
    private[ui] def busyButton(
        label: String,
        classes: Option[String] = None,
        disabledWhen: Signal[Boolean] = Val(false),
        busy: Var[Boolean] = Var(false)
    )(action: Var[Boolean] => Unit): HtmlElement =
        button(
          classes.map(cls := _).getOrElse(emptyMod),
          disabled <-- disabledWhen,
          aria.disabled <-- busy.signal,
          // Before the label rather than after it, so the label does not shift as the spinner appears.
          child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
          label,
          // A second press while the first is out is refused here, since the button stays pressable.
          onClick --> (_ => if (!busy.now()) action(busy))
        )

    /* Busy flags kept apart from the buttons that show them, by what each acts on. Never emptied: one
     * flag per thing pressed in a session, and keyed by the session too, so that the next player's
     * buttons do not start out busy with the last one's requests. */
    private val heldBusyFlags = scala.collection.mutable.Map.empty[(Int, String), Var[Boolean]]

    /** The busy flag for `key` -- what the button acts on, as "start round 12" -- the same one each time it is asked
      * for, so a button drawn again while its request is out is drawn busy. For `busyButton`'s `busy`.
      */
    private[ui] def heldBusy(key: String): Var[Boolean] =
        heldBusyFlags.getOrElseUpdate((Store.currentSignIn, key), Var(false))

    /** A form control with a visible caption tied to it.
      *
      * Wrapping rather than `for`/`id`: it needs no id to be unique across a page that renders the same form more than
      * once. A placeholder is not a label — it is a hint that disappears at the first keystroke, and leaves a screen
      * reader with an unnamed box — so every field that had only a placeholder gets one of these instead.
      */
    private[ui] def field(caption: String, control: HtmlElement): HtmlElement =
        label(cls := "field", caption, control)

    /** [[field]] whose caption changes as it is shown, and that is left out while it is `None`: the competition form's,
      * which the format chosen may reword.
      */
    private[ui] def field(caption: Signal[Option[String]], control: HtmlElement): HtmlElement =
        label(cls := "field", child.text <-- caption.map(_.getOrElse("")), control, shownWhen(caption))

    /* Hidden while `caption` is `None`: out of sight and out of the accessibility tree, and kept, with what was typed
     * into it, for when it is shown again. */
    private[ui] def shownWhen(caption: Signal[Option[String]]): Modifier[HtmlElement] =
        display <-- caption.map(c => if (c.isDefined) "" else "none")

    /** The body of a list: its rows, or a line saying why there are none.
      *
      * Three answers rather than two, because "there is nothing here" and "nobody has told us yet" are different things
      * and only one of them is news a player can act on. Every section used to say the first while the request that
      * would have filled it was still in flight — telling a player their turn was clear at the one moment nobody knew.
      *
      * A list that already holds something goes on showing it while a reload is in flight. The section dims to say so,
      * and throwing away what is on screen to say "Loading…" would lose the rows in order to repeat what the dimming
      * has already said.
      */
    private[ui] def listing[A](items: Signal[Seq[A]], fetching: Signal[Boolean])(
        empty: => HtmlElement
    )(rows: Seq[A] => HtmlElement): Modifier[HtmlElement] =
        child <-- items.combineWith(fetching).map {
            case (Seq(), true)  => p(cls := "empty", "Loading…")
            case (Seq(), false) => empty
            case (found, _)     => rows(found)
        }

    /** A section with a refresh button of its own.
      *
      * Every list here can go stale while it is being looked at — somebody else accepts a challenge, an engine finishes
      * a match — and the only remedy used to be reloading the page, which throws away every other section to reload
      * one. This re-fetches just this section's list and leaves the rest of the screen alone.
      *
      * The body dims and comes back while the request is in flight, so it is clear which part of the page the button
      * acted on. A fast request would flash too briefly to register, so the dimming is held for a moment; a slow one
      * holds it until the answer arrives.
      */
    private[ui] def refreshableSection(
        heading: String,
        reload: () => Future[Unit],
        subsection: Boolean
    )(content: Modifier[HtmlElement]*): HtmlElement =
        refreshableSection(heading = heading, refreshing = Var(false), reload = reload, subsection = subsection)(
          content*
        )

    /** The same, over a reloading flag the caller owns.
      *
      * Two uses for that. One is a reload that fills more than one section — the game screen's two challenge lists come
      * from a single request — where a flag per section would dim the one that was clicked while quietly replacing the
      * other, which is both confusing to look at and a lie to a screen reader, since the content that changed would be
      * outside the region marked busy. The other is a section whose element is rebuilt by its own reload: a flag
      * created inside it is thrown away along with the element, and the dimming ends the moment the response lands
      * rather than being seen.
      */
    private[ui] def refreshableSection(
        heading: String,
        refreshing: Var[Boolean],
        reload: () => Future[Unit],
        subsection: Boolean,
        /** What the section is, as a tip beside its heading ([[withTip]]): the tip's id, and its text. */
        tip: Option[(String, String)] = None
    )(content: Modifier[HtmlElement]*): HtmlElement = {
        val title = if (subsection) h3(heading) else h2(heading)
        sectionTag(
          cls := "refreshable",
          cls("refreshing") <-- refreshing.signal,
          div(
            cls := "section-head",
            tip.fold(title)((id, text) => withTip(id = id, subject = heading, text = text)(title)),
            button(
              cls := "refresh",
              tpe := "button",
              // The glyph is decorative; the button needs a name a screen reader can read out, and
              // naming the section it belongs to is what distinguishes it from the others.
              aria.label := Buttons.app.refreshNamed(section = heading),
              // Not disabled while the section reloads, which would drop the keyboard's focus: a press
              // made meanwhile is refused instead.
              aria.disabled <-- refreshing.signal,
              span(aria.hidden := true, "\u21bb"),
              onClick --> (_ => if (!refreshing.now()) refresh(refreshing, reload))
            )
          ),
          div(
            cls := "section-body",
            // Says the list is being replaced, so a screen reader does not read out half of it
            // mid-update.
            aria.busy <-- refreshing.signal,
            content
          )
        )
    }

    /** How long the dimming is held for, whether or not the request takes that long. Short enough not to be in the way,
      * long enough to be seen.
      */
    private val blinkMillis = 400L

    /* The reload always happens; only the dimming is conditional.
     *
     * It used to be skipped outright while a reload was already in flight, which was right when
     * the only caller was the button — a second click asks the same question. It is wrong for the
     * callers below, which reload because something *has changed*: dropping one of those would
     * leave a match that now exists off a list until somebody asked again. The refresh button
     * refuses a press while its section is refreshing, so the repeated-click case it guarded
     * against cannot arise from there anyway.
     *
     * Two reloads can share the one flag — a cross-refresh landing while the player has the
     * section's own button in flight, or the two challenge lists that are refreshed together — so
     * the flag is cleared by the last of them rather than the first. The flag is not just the
     * dimming: it also drives `aria.busy` and the refresh button's `aria-disabled`, and clearing it
     * early would tell a screen reader the list had settled while it was still being replaced, and
     * offer a button for a refresh already underway. */
    private[ui] def refresh(refreshing: Var[Boolean], reload: () => Future[Unit]): Unit = {
        reloadsInFlight.update(refreshing, reloadsInFlight.getOrElse(refreshing, 0) + 1)
        refreshing.set(true)
        val startedAt = System.currentTimeMillis()
        reload().onComplete { _ =>
            // Measured from *this* reload's start, so one that begins later keeps the section dimmed
            // for its own full blink rather than inheriting what is left of an earlier one's.
            val remaining = math.max(0L, blinkMillis - (System.currentTimeMillis() - startedAt))
            dom.window.setTimeout(() => finished(refreshing), remaining.toDouble)
        }
    }

    /** How many reloads are dimming each flag right now.
      *
      * Keyed on the `Var` itself, which defines no `equals`, so this is identity-keyed — two sections that happen to be
      * showing the same value are still two entries. Entries are removed as they reach zero, so the map holds only what
      * is actually in flight, and a flag belonging to an element that has since been discarded leaves nothing behind.
      */
    private val reloadsInFlight = scala.collection.mutable.Map.empty[Var[Boolean], Int]

    private def finished(refreshing: Var[Boolean]): Unit = {
        val left = reloadsInFlight.getOrElse(refreshing, 1) - 1
        if (left > 0) reloadsInFlight.update(refreshing, left)
        else {
            reloadsInFlight.remove(refreshing)
            refreshing.set(false)
        }
    }

    private def header: HtmlElement =
        headerTag(
          cls := "header",
          h1(Headings.app.title),
          // Unmissable, because a page that looks like the real thing but authenticates nobody is
          // exactly the page you do not want to be confused about.
          if (Config.current.isHeaderAuth)
              div(cls := "banner", s"local mode — signed in as ${Config.current.localExternalId}, no authentication")
          else emptyNode,
          // Drawn again only for a different player. A rename writes the player too, and rebuilding the account
          // panel on that remounted it open, taking the focus from "Save nickname" to the top of the panel.
          child <-- Store.player.signal
              .map {
                  case Store.PlayerState.Registered(player) => Some(player.playerId)
                  case _                                    => None
              }
              .distinct
              .map {
                  case Some(_) =>
                      div(
                        cls := "who",
                        span(child.text <-- currentPlayer.map(_.fold("")(_.nickname))),
                        // Where a page like this puts it: top right, next to who you are signed in as.
                        Account.view,
                        if (Config.current.isHeaderAuth) emptyNode
                        else button(cls := "link", Buttons.app.signOut, onClick --> (_ => Store.signOut()))
                      )
                  case _ => emptyNode
              },
          errorBanner
        )

    /** `Store.error` as a banner: in the header, and again inside each dialog that reports through it, since a modal
      * dialog makes the header inert and covers it -- a refusal shown only there is neither seen nor read out.
      */
    private[ui] def errorBanner: Modifier[HtmlElement] =
        child <-- Store.error.signal.map {
            case Some(message) =>
                div(
                  cls := "error",
                  // Every failed request in the application reports here. Without this the banner is a
                  // silent red box: it appears with no page change to notice, so a screen reader is
                  // never given a reason to read it out.
                  role := "alert",
                  span(message),
                  button(cls := "link", Buttons.app.dismiss, onClick --> (_ => Store.error.set(None)))
                )
            case None => emptyNode
        }

    /** The sign-in form itself, not a button that navigates to one: signing in happens on this page now, so that the
      * password is asked for first. Sign-up and password reset are still links out to the hosted pages, from inside
      * `SignIn.view`.
      */
    private def signedOutBody: HtmlElement = SignIn.view

    /** Either registration or the application proper, decided by whether this Cognito identity has a player. `ui.txt`:
      * self-registration in Cognito triggers a player set-up.
      */
    private def signedInBody: HtmlElement =
        div(
          child <-- Store.player.signal.map {
              case Store.PlayerState.Loading             => p(cls := "loading", "Loading…")
              case Store.PlayerState.Unregistered        => registration
              case Store.PlayerState.Registered(_)       => home
              case Store.PlayerState.Unavailable(reason) => unavailable(reason)
          }
        )

    /** The API could not be reached, or answered in a way that says nothing about this account.
      *
      * Deliberately not the registration form: the player may well exist, and inviting them to register again would be
      * wrong as well as confusing. A retry is all this can honestly offer.
      */
    private def unavailable(reason: String): HtmlElement =
        div(
          cls := "card",
          h2(ErrorText.loading.accountHeading),
          p(reason),
          p(cls := "detail", ErrorText.loading.accountDetail),
          button(Buttons.app.tryAgain, onClick --> (_ => Store.loadAll()))
        )

    private def registration: HtmlElement = {
        val nickname = Var("")
        // As `Account.saveNickname`: this writes into the store, so it only writes if the session
        // that asked is still the session that is here. See `Store.currentSignIn`.
        val signIn = Store.currentSignIn

        div(
          cls := "card",
          h2(Headings.app.chooseNickname),
          p("You are signed in, but you do not have a player yet. Your nickname is what other players see."),
          field(
            Labels.register.nickname,
            input(controlled(value <-- nickname.signal, onInput.mapToValue --> nickname))
          ),
          busyButton(label = Buttons.register.create, disabledWhen = nickname.signal.map(_.trim.isEmpty)) { busy =>
              Store.run(ApiClient.register(nickname.now().trim, Auth.email), busy) { player =>
                  if (Store.isStillSignedInAs(signIn)) {
                      Store.player.set(Store.PlayerState.Registered(player))
                      Store.refreshMatches()
                      Store.refreshGames()
                  }
              }
          }
        )
    }

    /** The application proper: a menu down the left, a screen to the right of it.
      *
      * The menu is the only navigation there is — the wire-frame asks for a link to the main page and a link per game —
      * so it is rendered once here and outlives whatever screen is showing, rather than being part of each screen and
      * redrawn with it.
      */
    private def home: HtmlElement =
        div(
          cls := "layout",
          menu,
          mainTag(
            cls := "screen",
            // Focusable from script only: where the focus goes on a new screen that has no heading yet.
            tabIndex := -1,
            child <-- Store.page.signal.map {
                case Store.Page.Home                => mainPage
                case Store.Page.OneGame(gameId)     => Games.gamePage(gameId)
                case Store.Page.NewGame             => Games.newGamePage
                case Store.Page.FindPlayers         => Players.findPlayersPage
                case Store.Page.OnePlayer(player)   => Players.playerPage(player)
                case Store.Page.OneCharacter(c)     => Players.characterPage(c)
                case Store.Page.OneTournament(g, t) => Tournaments.page(g, t)
            },
            inContext(screen => Store.page.signal.changes --> (_ => focusNewScreen(screen.ref)))
          )
        )

    /** Takes the focus to a screen that has just replaced another: to its first heading, or to the screen itself while
      * it has none.
      *
      * A link on a screen -- an opponent's name, "Back to search" -- is taken out of the page with the screen it was
      * on, and the focus with it: a keyboard was left at the top of the document and a screen reader told nothing about
      * where it had arrived. A menu entry stays, but the page it opened is elsewhere, and the heading is what says
      * which page it is. Focus already inside the new screen -- the New Competition dialog a player's page opens onto
      * takes it as it mounts -- is left where it is. A turn later than the change, so that the new screen has been
      * drawn; and a screen still loading passes the focus to its heading when that arrives.
      */
    private def focusNewScreen(screen: dom.html.Element): Unit =
        dom.window.setTimeout(
          () => {
              val active = dom.document.activeElement
              val lost = active == null || active == dom.document.body || !dom.document.body.contains(active) ||
                  active.closest(".menu") != null
              if (lost) {
                  def focusHeading(): Boolean = screen.querySelector("h2") match {
                      case heading: dom.html.Element =>
                          heading.setAttribute("tabindex", "-1")
                          heading.focus()
                          true
                      case _ => false
                  }
                  if (!focusHeading()) {
                      // A screen still loading has no heading yet: the screen holds the focus until it comes, and
                      // hands it on then -- unless the reader has moved it meanwhile.
                      screen.focus()
                      val watching = new dom.MutationObserver((_, observer) =>
                          if (dom.document.activeElement != screen || focusHeading()) observer.disconnect()
                      )
                      watching.observe(screen, new dom.MutationObserverInit { childList = true; subtree = true })
                      dom.window.setTimeout(() => watching.disconnect(), 5000)
                  }
              }
          },
          0
        )

    /** The left-hand menu: the main page, then every game, then — for an admin — the form that adds one.
      *
      * The games come from the same list the home screen already loads, so opening the menu costs no request. Only the
      * games themselves are links; the entry for the current screen is marked rather than removed, so the menu does not
      * change shape as it is used.
      */
    private def menu: HtmlElement =
        navTag(
          cls := "menu",
          menuItem(Buttons.menu.home, Store.Page.Home),
          // Above the games rather than below them: it is a way of getting to a player, and the
          // games below it are a way of getting to a game. The list of games can be long, and an
          // entry after it is an entry that has to be scrolled to.
          menuItem(Buttons.menu.findPlayers, Store.Page.FindPlayers),
          listing(Store.games.signal, Store.loading(Store.Fetch.Games))(p(cls := "empty", "No games yet."))(games =>
              div(games.map(gameMenuItem))
          ),
          // Only for admins, because only an admin can create a game: the server answers anyone else
          // with a 403, and a menu entry that always fails is worse than no entry.
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin => menuItem(Buttons.menu.addGame, Store.Page.NewGame)
              case _                              => emptyNode
          }
        )

    /** A game's entry in the menu, with a button beside it that goes to the game's screen and reloads all of it.
      *
      * Going to the screen reloads the challenges, characters and admins already (`Store.show`), and the admin's match
      * list with them, since it is asked for again whenever its admins are. What it does not reload are the player's
      * own lists, which the main page shares: those are re-read here, through the same flags their sections' own
      * buttons use, so each one dims while it reloads.
      */
    private def gameMenuItem(game: Game): HtmlElement =
        div(
          cls := "menu-game",
          menuItem(game.displayName, Store.Page.OneGame(game.gameId)),
          button(
            cls := "refresh",
            tpe := "button",
            aria.label := Buttons.menu.reloadGameNamed(game = game.displayName),
            span(aria.hidden := true, "\u21bb"),
            onClick --> (_ => reloadGamePage(game.gameId))
          )
        )

    private def reloadGamePage(gameId: GameId): Unit = {
        Store.show(Store.Page.OneGame(gameId))
        refresh(MatchSections.refreshingDue, () => Store.reloadDue())
        refresh(MatchSections.refreshingActive, () => Store.reloadActive())
        refresh(MatchSections.refreshingCompleted, () => Store.reloadCompleted())
    }

    private def menuItem(caption: String, target: Store.Page): HtmlElement = {
        val isCurrent = Store.page.signal.map(_ == target)

        button(
          cls := "menu-item",
          cls("current") <-- isCurrent,
          // The tint and the heavier weight say which screen this is to anyone who can see them.
          // This says it to everyone else, and is why the styling is not the only thing that does.
          aria.current <-- isCurrent.map(if (_) "page" else "false"),
          caption,
          onClick --> (_ => Store.show(target))
        )
    }

    /** The main page: what is waiting on this player, what they are playing, and what lately finished. No games list —
      * the menu is the games list now — and the completed matches are shown a page at a time, because the whole history
      * of a game is what that game's own screen is for.
      */
    private def mainPage: HtmlElement =
        div(
          MatchSections.dueSection(),
          MatchSections.myMatchesSection(),
          Tournaments.mainSections,
          MatchSections.recentlyCompletedSection
        )

    /** Opens a game engine's page — a board, a spectator's view, a character page — in a new tab, signed in as the
      * player signed in here. See `Auth.handOff`.
      *
      * Opened at once when the hand-off is ready at once, which keeps it inside the click a browser requires a popup to
      * come from; only a session that has to be refreshed first waits for that.
      */
    private[ui] def openSignedIn(url: String): Unit = {
        def open(target: String): Unit = { dom.window.open(target, "_blank", "noopener,noreferrer"); () }
        val handed = Auth.handOff(url)
        handed.value match {
            case Some(scala.util.Success(target)) => open(target)
            case _                                => handed.foreach(open)
        }
    }

    /** Which cells a table header names: its column, or its row. Not among Laminar's attributes. */
    private[ui] val scopeAttr = htmlAttr("scope", com.raquo.laminar.codecs.StringAsIsCodec)

    /** How long is left of the turn in front of this player, counting down while they look at it.
      *
      * On the "Your turn" list only, which is the one place a deadline is a thing to act on rather than a fact about a
      * match. An instant on its own does not answer the question being asked there — "have I got time for this now?" —
      * and answering it by subtracting one timestamp from another is work the page can do.
      *
      * The ticking text is hidden from assistive technology and the absolute deadline is given instead. A number that
      * rewrites itself every second is either announced every second or read stale, and neither is the deadline: "due
      * 14:32 UTC" is, and it does not move.
      *
      * The countdown is against the browser's clock, while the deadline was set by the database's — a device whose
      * clock is minutes out will show a countdown that is minutes out with it. The server's own comparison is the one
      * that decides a forfeit; this is a reading of it, and it is not what anybody is judged by.
      */
    private[ui] def countdown(deadline: java.time.Instant): HtmlElement = {
        // Ticks only while the row is on screen: Laminar starts the stream when the element mounts
        // and stops it when it goes, so a list that has been navigated away from costs nothing.
        val remaining =
            EventStream.periodic(1000).toSignal(0).map(_ => deadline.toEpochMilli - System.currentTimeMillis())
        div(
          cls := "due",
          cls("overdue") <-- remaining.map(_ <= 0),
          span(
            aria.hidden := true,
            child.text <-- remaining.map(Format.remaining)
          ),
          span(cls := "sr-only", s"due ${Format.instant(deadline)}")
        )
    }

    /** A captioned text field whose explanation is a tip beside it rather than a line under it: [[withTip]] around a
      * `label.field`, with the input pointed at the tip so that it is read out with the field. Just the field while
      * `text` is empty.
      */
    private[ui] def tipField(id: String, caption: String, text: String)(control: HtmlElement): HtmlElement =
        withTip(id = id, subject = caption, text = text)(
          label(cls := "field", caption, if (text.nonEmpty) control.amend(aria.describedBy := id) else control)
        )

    /** [[tipField]] whose caption and tip change as it is shown: the competition form's, which the format chosen may
      * reword. Left out while the caption is `None`, and without its tip while the tip is.
      */
    private[ui] def tipField(id: String, caption: Signal[Option[String]], text: Signal[Option[String]])(
        control: HtmlElement
    ): HtmlElement = {
        val words = caption.map(_.getOrElse(""))
        withTip(id = id, subject = words, text = text)(
          label(cls := "field", child.text <-- words, control.amend(aria.describedBy := id))
        ).amend(shownWhen(caption))
    }

    /** A control with a tip beside it: an ⓘ button that shows `text` on hover, on keyboard focus, and on a tap, which
      * is the only one of the three a phone has. The tip is the element `id` names, so the control can point
      * `aria-describedby` at it and have it read out whether or not it is showing.
      *
      * A button rather than a `title`: a title never appears on a touch screen and is not reliably read out. Outside
      * the label, because a tap on anything inside a label toggles its checkbox.
      */
    /* Tips are found by id, and one match can be on a page twice -- "Your Turn" and "Current Matches"
     * both list it -- so a tip under a row is numbered as it is made rather than named after the row. */
    private var tipsMade = 0

    private[ui] def freshTipId(prefix: String): String = {
        tipsMade += 1
        s"$prefix-$tipsMade"
    }

    /** The face of a tip's button: an "i" in a circle, drawn by `.tip-icon` rather than a ⓘ, which some fonts set too
      * small and faint to read. The button's `aria-label` is what a screen reader hears.
      */
    private[ui] def tipIcon(): HtmlElement = span(cls := "tip-icon", aria.hidden := true, "i")

    /** "friendly" under a match or a challenge that is one, with what that means a tap away. Said only when it is: a
      * rated match is the ordinary kind, and needs no saying.
      */
    private[ui] def friendlyLabel(): HtmlElement =
        withTip(
          id = freshTipId("friendly-tip"),
          subject = "friendly",
          text = Tips.matches.friendly
        )(div(cls := "detail", "friendly")).amend(cls := "inline-tip")

    /** The "Friendly" column's heading: the word itself showing what a friendly match is, as the Elo column's
      * "friendly" does. And the word again as plain text, for the narrow layout: there the heading row is hidden from
      * sight but kept for a screen reader, and its buttons are taken out so that nothing unseen takes the focus --
      * which, the word being the button, would leave the column with no name.
      */
    private[ui] def friendlyHeading(): HtmlElement =
        span(
          span(cls := "narrow-heading", "Friendly"),
          tipWord(id = freshTipId("friendly-tip"), word = "Friendly", text = Tips.matches.friendly)
        )

    private[ui] def withTip(id: String, subject: String, text: String)(control: HtmlElement): HtmlElement =
        tipped(id = id, text = text, control = control, hasTip = Val(text.nonEmpty))(
          cls := "tip-toggle",
          aria.label := Buttons.app.tipNamed(subject = subject),
          tipIcon()
        )

    /** [[withTip]] whose subject and tip change as it is shown, with no tip while it is `None`. */
    private[ui] def withTip(id: String, subject: Signal[String], text: Signal[Option[String]])(
        control: HtmlElement
    ): HtmlElement =
        tipped(
          id = id,
          text = child.text <-- text.map(_.getOrElse("")),
          control = control,
          hasTip = text.map(_.isDefined)
        )(
          cls := "tip-toggle",
          aria.label <-- subject.map(s => Buttons.app.tipNamed(subject = s)),
          tipIcon()
        )

    /** A word that is its own tip: hovered, focused or tapped, it shows what it means, as an ⓘ beside it would. For a
      * cell too narrow to hold both a word and an ⓘ -- the heading of a running match's Friendly column. A button, so
      * that a keyboard and a finger can reach the tip as well as a mouse can, but set as the word with a dotted line
      * under it. Just the word while `text` is empty.
      */
    private def tipWord(id: String, word: String, text: String): HtmlElement =
        if (text.isEmpty) span(word)
        else
            tipped(id = id, text = text, control = emptyNode)(cls := "tip-toggle tip-word", word)
                .amend(cls := "inline-tip")

    /* The tip, and the button that shows it after `control`: the ⓘ of `withTip`, or `tipWord`'s word. `text` is the
     * tip's words, or a `child.text` that keeps them up to date. While `hasTip` is false the button and the tip are
     * hidden, leaving `control` alone. */
    private[ui] def tipped(
        id: String,
        text: Modifier[HtmlElement],
        control: Modifier[HtmlElement],
        hasTip: Signal[Boolean] = Val(true)
    )(
        toggle: Modifier[HtmlElement]*
    ): HtmlElement = {
        val tipDisplay = hasTip.map(if (_) "" else "none")
        val open = Var(false)
        // Escape hides a tip that hover or keyboard focus is showing, which `open` knows nothing about: this holds it
        // hidden, with focus left where it was, until the pointer or focus arrives afresh.
        val dismissed = Var(false)
        val tip = span(
          idAttr := id,
          role := "tooltip",
          cls := "tip",
          cls("open") <-- open.signal,
          cls("dismissed") <-- dismissed.signal,
          display <-- tipDisplay,
          text
        )
        // In a table that scrolls sideways, the scrolling box would clip a tip hanging below its heading, so there the
        // tip is pinned to the window instead (`.table-scroll .tip`), under its ⓘ as it is about to show.
        def place(toggle: dom.Element): Unit =
            if (toggle.closest(".table-scroll") != null) {
                val at = toggle.getBoundingClientRect()
                val width = math.min(352.0, dom.window.innerWidth - 32)
                val left = math.max(16.0, math.min(at.left, dom.window.innerWidth - 16 - width))
                tip.ref.style.top = s"${at.bottom}px"
                tip.ref.style.left = s"${left}px"
            }
        div(
          cls := "with-tip",
          control,
          button(
            tpe := "button",
            display <-- tipDisplay,
            toggle,
            // So that the tip is read out as the button is reached, rather than only shown: a screen reader is not
            // told what appeared when it opened.
            aria.describedBy := id,
            aria.expanded <-- open.signal,
            aria.controls := id,
            onClick --> { event =>
                place(event.currentTarget.asInstanceOf[dom.Element])
                dismissed.set(false)
                open.update(!_)
            },
            onFocus --> { event =>
                place(event.currentTarget.asInstanceOf[dom.Element])
                dismissed.set(false)
            },
            onMouseEnter --> { event =>
                place(event.currentTarget.asInstanceOf[dom.Element])
                dismissed.set(false)
            },
            onBlur --> (_ => open.set(false)),
            // A tip that is showing takes the Escape that hides it, so a dialog the tip is in stays open. Showing by
            // `open`, or by the CSS, which shows it for keyboard focus and -- where a pointer hovers, as the CSS has
            // it, not a touch screen's leftover `:hover` -- for hover, without telling `open`. And a hovered tip's
            // Escape comes from wherever focus is, not from this button. So it is heard on the document,
            // while capturing, which is before anything on the way to that focus can hear it.
            inContext(node =>
                documentEvents(_.onKeyDown.useCapture)
                    .filter(event =>
                        event.key == "Escape" && !dismissed.now() &&
                            (open.now() || node.ref.matches(":focus-visible") ||
                                (node.ref.matches(":hover") && dom.window.matchMedia("(hover: hover)").matches))
                    ) --> { event =>
                    event.stopPropagation()
                    open.set(false)
                    dismissed.set(true)
                }
            )
          ),
          tip
        )
    }

    private[ui] def currentPlayer: Signal[Option[Player]] = Store.currentPlayer
}
