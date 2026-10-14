package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** Entry point.
  *
  * The whole UI is one page with no router: every screen in `ui.txt` is a section of it, and the only URL that ever
  * matters is the one Cognito redirects back to.
  */
object Main {

    @JSExportTopLevel("main")
    def main(): Unit = {
        // Must run before anything reads the token: this may be the page load that carries an
        // authorization code back from the hosted sign-up or password-reset pages, and until it has
        // been redeemed there is no session. An ordinary load has no code and falls straight through.
        if (Config.current.isHeaderAuth) {
            // Nothing to sign in to: the identity is whatever the config says. Straight to the app.
            Store.signedIn.set(true)
            Store.loadAll()
        } else
            Auth.completeSignIn() match {
                // A code came back from the hosted pages, so this load *is* a sign-in: the token was
                // issued seconds ago and its email claim is what Cognito currently holds, which is the one
                // moment matchmaker's copy of the address is reconciled with it.
                case Some(signIn) =>
                    signIn.onComplete { outcome =>
                        outcome.failed.foreach(Store.report)
                        Store.signedIn.set(Auth.isSignedIn)
                        if (Auth.isSignedIn) Store.loadAll(justSignedIn = true)
                    }
                // An ordinary load, resuming a session that may be an hour old. Deliberately not treated
                // as a sign-in: the claims in a token that old can be out of date, and a reload is exactly
                // when reconciling from them would undo a change made since.
                case None =>
                    Store.signedIn.set(Auth.isSignedIn)
                    if (Auth.isSignedIn) Store.loadAll()
            }

        renderOnDomContentLoaded(dom.document.getElementById("app"), Views.app)
    }
}

object Views {

    def app: HtmlElement =
        div(
          cls := "app",
          header,
          child <-- Store.signedIn.signal.map(if (_) signedInBody else signedOutBody)
        )

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
      */
    private[ui] def busyButton(
        label: String,
        classes: Option[String] = None,
        disabledWhen: Signal[Boolean] = Val(false)
    )(action: Var[Boolean] => Unit): HtmlElement = {
        val busy = Var(false)

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
    }

    /** A form control with a visible caption tied to it.
      *
      * Wrapping rather than `for`/`id`: it needs no id to be unique across a page that renders the same form more than
      * once. A placeholder is not a label — it is a hint that disappears at the first keystroke, and leaves a screen
      * reader with an unnamed box — so every field that had only a placeholder gets one of these instead.
      */
    private[ui] def field(caption: String, control: HtmlElement): HtmlElement =
        label(cls := "field", caption, control)

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
        refreshableSection(heading, Var(false), reload, subsection)(content*)

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
            tip.fold(title)((id, text) => withTip(id, heading, text)(title)),
            button(
              cls := "refresh",
              tpe := "button",
              // The glyph is decorative; the button needs a name a screen reader can read out, and
              // naming the section it belongs to is what distinguishes it from the others.
              aria.label := s"Refresh $heading",
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
    private def refresh(refreshing: Var[Boolean], reload: () => Future[Unit]): Unit = {
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

    // -------------------------------------------------------------------------
    // Chrome
    // -------------------------------------------------------------------------

    private def header: HtmlElement =
        headerTag(
          cls := "header",
          h1("Matchmaker"),
          // Unmissable, because a page that looks like the real thing but authenticates nobody is
          // exactly the page you do not want to be confused about.
          if (Config.current.isHeaderAuth)
              div(cls := "banner", s"local mode — signed in as ${Config.current.localExternalId}, no authentication")
          else emptyNode,
          child <-- Store.player.signal.map {
              case Store.PlayerState.Registered(player) =>
                  div(
                    cls := "who",
                    span(player.nickname),
                    // Where a page like this puts it: top right, next to who you are signed in as.
                    Account.view,
                    if (Config.current.isHeaderAuth) emptyNode
                    else button(cls := "link", "Sign out", onClick --> (_ => Store.signOut()))
                  )
              case _ => emptyNode
          },
          errorBanner
        )

    /** `Store.error` as a banner: in the header, and again inside each dialog that reports through it, since a modal
      * dialog makes the header inert and covers it -- a refusal shown only there is neither seen nor read out.
      */
    private def errorBanner: Modifier[HtmlElement] =
        child <-- Store.error.signal.map {
            case Some(message) =>
                div(
                  cls := "error",
                  // Every failed request in the application reports here. Without this the banner is a
                  // silent red box: it appears with no page change to notice, so a screen reader is
                  // never given a reason to read it out.
                  role := "alert",
                  span(message),
                  button(cls := "link", "Dismiss", onClick --> (_ => Store.error.set(None)))
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
          h2("Could Not Load Your Account"),
          p(reason),
          p(cls := "detail", "This is a problem reaching the server, not a problem with your sign-in."),
          button("Try again", onClick --> (_ => Store.loadAll()))
        )

    private def registration: HtmlElement = {
        val nickname = Var("")
        // As `Account.saveNickname`: this writes into the store, so it only writes if the session
        // that asked is still the session that is here. See `Store.currentSignIn`.
        val signIn = Store.currentSignIn

        div(
          cls := "card",
          h2("Choose a Nickname"),
          p("You are signed in, but you do not have a player yet. Your nickname is what other players see."),
          field("Nickname", input(controlled(value <-- nickname.signal, onInput.mapToValue --> nickname))),
          busyButton("Create Player", disabledWhen = nickname.signal.map(_.trim.isEmpty)) { busy =>
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
                case Store.Page.OneGame(gameId)     => gamePage(gameId)
                case Store.Page.NewGame             => newGamePage
                case Store.Page.FindPlayers         => findPlayersPage
                case Store.Page.OnePlayer(player)   => playerPage(player)
                case Store.Page.OneCharacter(c)     => characterPage(c)
                case Store.Page.OneTournament(g, t) => Tournaments.page(g, t)
                case Store.Page.NewTournament(g)    => Tournaments.newPage(g)
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
      * which page it is. Focus already inside the new screen -- the challenge dialog a player's page opens onto takes
      * it as it mounts -- is left where it is. A turn later than the change, so that the new screen has been drawn.
      */
    private def focusNewScreen(screen: dom.html.Element): Unit =
        dom.window.setTimeout(
          () => {
              val active = dom.document.activeElement
              val lost = active == null || active == dom.document.body || !dom.document.body.contains(active) ||
                  active.closest(".menu") != null
              if (lost) {
                  val target = screen.querySelector("h2") match {
                      case heading: dom.html.Element =>
                          heading.setAttribute("tabindex", "-1")
                          heading
                      case _ => screen
                  }
                  target.focus()
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
          menuItem("Main page", Store.Page.Home),
          // Above the games rather than below them: it is a way of getting to a player, and the
          // games below it are a way of getting to a game. The list of games can be long, and an
          // entry after it is an entry that has to be scrolled to.
          menuItem("Find Players", Store.Page.FindPlayers),
          listing(Store.games.signal, Store.loading(Store.Fetch.Games))(p(cls := "empty", "No games yet."))(games =>
              div(games.map(gameMenuItem))
          ),
          // Only for admins, because only an admin can create a game: the server answers anyone else
          // with a 403, and a menu entry that always fails is worse than no entry.
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin => menuItem("Add a Game", Store.Page.NewGame)
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
            aria.label := s"Reload ${game.displayName}",
            span(aria.hidden := true, "\u21bb"),
            onClick --> (_ => reloadGamePage(game.gameId))
          )
        )

    private def reloadGamePage(gameId: GameId): Unit = {
        Store.show(Store.Page.OneGame(gameId))
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        refresh(refreshingChallenges, () => Store.reloadMyChallenges())
        refresh(refreshingCompleted, () => Store.reloadCompleted())
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
          invitationsSection,
          readyToStartSection(),
          dueSection(),
          myMatchesSection(),
          waitingSection(),
          Tournaments.mainSections,
          recentlyCompletedSection
        )

    // -------------------------------------------------------------------------
    // Matches
    // -------------------------------------------------------------------------

    /** Shared by the three sections drawn from the caller's open challenges -- invitations, "Ready to Start" and
      * "Waiting to Start" are one response split by what the caller is to each challenge, so any of their buttons
      * reloads all three and all three have to say so. Held at this level rather than passed down because the sections
      * are siblings on the main page with nothing between them to own it.
      */
    private val refreshingChallenges: Var[Boolean] = Var(false)

    /* The same, for the two match lists. Shared by the home screen's copy of each section and the
     * game screen's, which are never both on screen — and, more to the point, by the sections
     * themselves and by the actions below that change what belongs in them. */
    private val refreshingDue: Var[Boolean] = Var(false)
    private val refreshingActive: Var[Boolean] = Var(false)

    /* And for the completed list, which a refresh can move a row *into*. Shared by the home screen's
     * "Recently Completed" and the game screen's "Your Completed Matches": they are two views of the
     * one list, only one of them is ever on screen, and `reloadCompleted` fills both. */
    private val refreshingCompleted: Var[Boolean] = Var(false)

    /** Reloads what a change to a challenge can have changed, the way each section's own button reloads it: the
      * caller's open challenges, and the game screen's list when that is the screen behind this.
      *
      * Dimmed and re-read rather than edited in place: a list that silently loses a row is a list that might have lost
      * the wrong one.
      */
    private def reloadChallengeSections(gameId: GameId): Unit = {
        refresh(refreshingChallenges, () => Store.reloadMyChallenges())
        if (Store.page.now() == Store.Page.OneGame(gameId)) Store.refreshChallenges(gameId)
    }

    /** Reloads what starting a challenge has changed: it leaves the challenge lists, and its players are asked to
      * choose their roles -- or, once they have, it is a match in "Current Matches", and in "Your Turn" as well if the
      * engine says the first move is this player's. The role choices and the tournaments come with it, since a
      * challenge is one.
      *
      * Through each section's own flag rather than through `Store.refreshMatches`, so that the lists visibly reload --
      * dimmed, and marked `aria-busy` while they do -- instead of quietly growing a row the player has to notice for
      * themselves. The completed list is not touched: a match that has just started has not finished.
      */
    private def reloadAfterStart(gameId: GameId): Unit = {
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        Store.reloadTournamentChoices()
        Store.reloadMyTournaments()
        reloadChallengeSections(gameId)
    }

    /** Reloads the sections a re-check with the engine may have changed.
      *
      * Asking the engine is how a finish that never reached matchmaker — a callback that was lost, a game that simply
      * ends without saying so — is discovered, so the answer can be that the match is over. When it is, the row the
      * player clicked belongs in "Recently Completed" and nowhere else, and leaving it in "Current Matches" with a Play
      * button on it is worse than stale: it offers a turn in a match that has none.
      *
      * The completed list is reloaded only when the match actually finished. Every refresh could reload it, but the
      * usual answer is "still being played" — that is the whole point of the button — and two extra requests to be told
      * nothing changed is what the per-section reloads exist to avoid. `reloadCompleted` brings the result rows with
      * it, which the completed rows are drawn from.
      *
      * Due and active are reloaded either way: the news may be that the match is over, but it may equally be that the
      * turn has moved, which is what moves a row between those two.
      */
    private def reloadAfterMatchRefresh(refreshed: Match): Unit = {
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        if (refreshed.isCompleted || refreshed.isCancelled) refresh(refreshingCompleted, () => Store.reloadCompleted())
    }

    /* What the caller is to a challenge, which decides the section it is listed in. */
    private def isInvitedTo(c: ChallengeSummary): Boolean =
        c.summary.isInvited && !c.summary.isEntered && !c.summary.isOwner

    /* The fewest entries a challenge can start with: enough to fill the game's required roles, and never fewer than
     * two. `None` when the game is not one this player has listed, which is a game whose roles nobody here knows. */
    private def leastToStart(game: Option[Game]): Option[Int] = game.map(g => math.max(2, g.requiredSeats))

    private def gameOf(games: Seq[Game], gameId: GameId): Option[Game] = games.find(_.gameId == gameId)

    /** What this player has been invited to and has not answered.
      *
      * First on the page, and absent when there is nothing in it, for the reason "Ready to Start" is both: an
      * invitation is a thing somebody else did that is waiting on this player, not a list they came to check. Every
      * other section here is about a challenge or match they are already in — this is the only one about one they are
      * not, which is also why it names who offered it.
      */
    private def invitationsSection: HtmlElement =
        div(
          /* What the last answer to an invitation was, said once, to whoever is not watching the list.
           *
           * Answering one removes its row, and may remove the whole section with it — which is a
           * change nobody is told about by sight alone being gone. The error banner is `role="alert"`,
           * so a refusal already announces itself; this is the other half, and the half that was
           * silent. A one-line status rather than `aria-live` on the section, so that the first fetch
           * of the session does not read every row out unprompted. Present from the start and empty,
           * because a live region has to exist before the text arrives in it to be announced reliably. */
          p(
            cls := "sr-only",
            role := "status",
            aria.live := "polite",
            // From the store, and stamped there with the session that said it. See `Store.invitationsSaid`.
            child.text <-- Store.invitationsSaid
          ),
          child <-- Store.myChallenges.signal
              .combineWith(
                Store.games.signal,
                Store.known(Store.Fetch.MyChallenges),
                Store.failed(Store.Fetch.MyChallenges)
              )
              .map { (challenges, games, known, failed) =>
                  val unanswered = challenges.filter(isInvitedTo)
                  /* Three states, not two, and emptiness tells none of them apart. A list that failed
                   * stands with a line saying to ask again, and its refresh button is what asks -- the
                   * other sections drawn from this list are absent when it is empty, their buttons with
                   * them. Nothing at all until it has answered, which is a page still loading; and
                   * nothing once it has if there is nothing to answer. */
                  if (failed)
                      refreshableSection(
                        "You Have Been Invited",
                        refreshingChallenges,
                        () => Store.reloadMyChallenges(),
                        subsection = false
                      )(
                        p(cls := "empty", "Couldn't load your invitations just now. Refresh to try again.")
                      )
                  else if (!known || unanswered.isEmpty) emptyNode
                  else
                      refreshableSection(
                        "You Have Been Invited",
                        refreshingChallenges,
                        () => Store.reloadMyChallenges(),
                        subsection = false
                      )(ul(unanswered.map(c => invitationRow(c, gameOf(games, c.summary.tournament.gameId)))))
              }
        )

    /** One invitation: who asked, to what, on what terms — then the two answers.
      *
      * In a character game the invitation is a character's, so the row names it, Accept enters with it, and Decline
      * turns down its invitation; the character is read from the invitation, whoever owns it now being who may answer.
      * In a plain game Accept enters the player. Every fact it decides on comes from the response rather than from
      * `Store.games`, which holds only the active games -- an invitation outlives its game being deactivated.
      */
    private def invitationRow(invited: ChallengeSummary, game: Option[Game]): HtmlElement = {
        val t = invited.summary.tournament
        def accepted(character: Option[CharacterId])(busy: Var[Boolean]): Unit =
            Store.run(ApiClient.enterTournament(t.gameId, t.tournamentId, character), busy, invitationGone(t)) { _ =>
                Store.sayAboutInvitations(s"Invitation to ${invited.summary.gameName} accepted.")
                // Accepting may have filled one that starts itself: the start's reloads cover both.
                reloadAfterStart(t.gameId)
            }
        def declined(busy: Var[Boolean], decline: Future[Unit]): Unit =
            Store.run(decline, busy, invitationGone(t)) { _ =>
                Store.sayAboutInvitations(s"Invitation to ${invited.summary.gameName} declined.")
                reloadChallengeSections(t.gameId)
            }
        li(
          cls := "row",
          child <-- currentPlayer.map {
              case None     => emptyNode
              case Some(me) =>
                  // The character of the caller's this invitation is for, in a character game.
                  invited.invitedCharacters.find(_.owner.exists(_.playerId == me.playerId)) match {
                      case Some(mine) =>
                          div(
                            div(
                              cls := "title",
                              s"${invited.summary.ownerNickname} invited ${mine.character.name} to ${invited.summary.gameName}"
                            ),
                            div(cls := "detail", challengeTerms(t, invited.entries.size)),
                            busyButton("Accept")(accepted(Some(mine.character.characterId))),
                            busyButton("Decline", classes = Some("link"))(busy =>
                                declined(
                                  busy,
                                  ApiClient.revokeTournamentCharacterInvitation(
                                    t.gameId,
                                    t.tournamentId,
                                    mine.character.characterId
                                  )
                                )
                            )
                          )
                      case None =>
                          div(
                            div(
                              cls := "title",
                              s"${invited.summary.ownerNickname} invited you to ${invited.summary.gameName}"
                            ),
                            div(cls := "detail", challengeTerms(t, invited.entries.size)),
                            game match {
                                case Some(g) if g.gameType == GameType.Plain => busyButton("Accept")(accepted(None))
                                case _ =>
                                    button(
                                      tpe := "button",
                                      cls := "link",
                                      "Open the game",
                                      onClick --> (_ => Store.show(Store.Page.OneGame(t.gameId)))
                                    )
                            },
                            busyButton("Decline", classes = Some("link"))(busy =>
                                declined(
                                  busy,
                                  ApiClient.revokeTournamentInvitation(t.gameId, t.tournamentId, me.playerId)
                                )
                            )
                          )
                  }
          }
        )
    }

    /** What to do when answering an invitation is refused because it is no longer there to answer: the list was fetched
      * before its owner withdrew it, or started or cancelled the challenge, or it filled up. 404 and 409 are both the
      * server saying so, so the lists are re-read and the server's own account is left in the banner — only it knows
      * which of those it was. Anything else says nothing about whether the invitation is still there, and is left as
      * `Store.run` reported it.
      */
    private def invitationGone(t: Tournament)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) => reloadAfterStart(t.gameId)
        case _                      => ()
    }

    /* Absent altogether when there is nothing ready to start, heading and refresh button with it.
     *
     * Every other list here says so when it is empty, because "no matches" is an answer to a
     * question somebody asked. This one is not a question anybody asks: a challenge becomes
     * startable when enough players have entered it, which is something that happens *to* its owner
     * rather than something they came to check. So it is a prompt, and a prompt with nothing to
     * prompt is noise on every screen that carries it. */
    private def readyToStartSection(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.myChallenges.signal
              .combineWith(Store.games.signal)
              .map { (challenges, games) =>
                  val mine = challengesIn(game)(challenges).filter { c =>
                      c.summary.isOwner && !c.summary.tournament.settings.isAutoStarted &&
                      leastToStart(gameOf(games, c.summary.tournament.gameId)).exists(c.entries.size >= _)
                  }
                  if (mine.isEmpty) emptyNode
                  else
                      refreshableSection(
                        "Ready to Start",
                        refreshingChallenges,
                        () => Store.reloadMyChallenges(),
                        subsection = false
                      )(ul(mine.map(readyToStartRow)))
              }
        )

    private def readyToStartRow(c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        li(
          cls := "row",
          div(cls := "title", c.summary.gameName),
          div(cls := "detail", challengeTerms(t, c.entries.size)),
          busyButton("Start") { busy =>
              Store.run(ApiClient.tournamentStep(t.gameId, t.tournamentId, "start"), busy, challengeStale(t))(_ =>
                  reloadAfterStart(t.gameId)
              )
          },
          // Enough players having entered is no promise the owner still wants the match, so the way out of it stays
          // here alongside the start.
          busyButton("Cancel") { busy =>
              Store.run(ApiClient.tournamentStep(t.gameId, t.tournamentId, "cancel"), busy, challengeStale(t))(_ =>
                  reloadChallengeSections(t.gameId)
              )
          }
        )
    }

    /** What a refused start, cancel or withdrawal says about the lists on screen: a 409 or 404 is the challenge having
      * moved on since the list was fetched -- started by itself as it filled, started or cancelled by its owner -- so
      * the lists are re-read, the match lists with them in case it is now a match. Anything else -- a 5xx, a dropped
      * connection -- says nothing about the challenge, and is left as `Store.run` reported it.
      */
    private def challengeStale(t: Tournament)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) => reloadAfterStart(t.gameId)
        case _                      => ()
    }

    /** A challenge's terms in a line: how many it seats and how many have entered, its time limit, and whether it
      * starts by itself, is live, or is rated.
      */
    private def challengeTerms(t: Tournament, entered: Int): String = {
        val seats = t.settings.maxEntrants.getOrElse(t.defaultPoolSize)
        List(
          Some(s"$entered of $seats entered"),
          t.settings.roundDuration.map(d => s"${Format.duration(d, TimeLimitUnit.bestFor(d))} to play"),
          Option.when(t.settings.isAutoStarted)("starts when full"),
          Option.when(t.settings.isLive)("live"),
          Option.when(!t.settings.isFriendly)("rated"),
          t.settings.start.map(at => s"for ${Format.instant(at)}")
        ).flatten.mkString(" · ")
    }

    /** "List of all matches a player has a turn due" — the first thing in `ui.txt`, and the only list shown expanded
      * from the start, because it is the one that needs acting on.
      */
    /** The matches it is this player's turn in.
      *
      * `game` narrows it to one game, which is what the game screen shows. Filtered from the list the home page already
      * has rather than fetched per game, for the reason [[gameHistory]] is: it is the same list in the same order, and
      * a second request would only be a second chance for the two screens to disagree about it.
      */
    private def dueSection(game: Option[Game] = None): HtmlElement =
        refreshableSection("Your Turn", refreshingDue, () => Store.reloadDue(), subsection = false)(
          listing(Store.due.signal.map(matchesIn(game)), Store.loading(Store.Fetch.Due))(
            p(
              cls := "empty",
              game.fold("Nothing is waiting on you.")(g => s"Nothing is waiting on you in ${g.displayName}.")
            )
          )(matches => ul(matches.map(matchRow)))
        )

    /* One game's matches, or all of them. The game screens show a slice of each list rather than a
     * list of their own, so this is the slice. */
    private def matchesIn(game: Option[Game])(matches: Seq[MatchSummary]): Seq[MatchSummary] =
        game.fold(matches)(g => matches.filter(_.gameId == g.gameId))

    /* The same slice, over the caller's challenges. */
    private def challengesIn(game: Option[Game])(challenges: Seq[ChallengeSummary]): Seq[ChallengeSummary] =
        game.fold(challenges)(g => challenges.filter(_.summary.tournament.gameId == g.gameId))

    /** The matches still being played. Expanded rather than behind a toggle: the wire-frame lists it as one of the four
      * things the main page shows, and a section that has to be opened to find out whether it is empty is not shown.
      */
    private def myMatchesSection(game: Option[Game] = None): HtmlElement =
        refreshableSection("Current Matches", refreshingActive, () => Store.reloadActive(), subsection = false)(
          listing(Store.active.signal.map(matchesIn(game)), Store.loading(Store.Fetch.Active))(
            p(
              cls := "empty",
              game.fold("You are not in any matches.")(g => s"You are not in any matches of ${g.displayName}.")
            )
          )(
            matchTable(
              _,
              showGame = game.isEmpty,
              showParameters = game.nonEmpty,
              outcome = playingStatus,
              view = playControls,
              dateHeading = "Started",
              dated = summary => Some(summary.start),
              showResult = false,
              // The main page lists every game's matches as a plain game's: who was played, not as what.
              characterColumns = game.nonEmpty
            )
          )
        )

    /** The challenges this player has entered that have not started: still filling up, or somebody else's to start. The
      * ones this player could start right now are left out: they are in "Ready to Start", and listing them twice would
      * offer the same button in two places.
      *
      * Absent when there is nothing waiting, like "Ready to Start" and for the same reason: what a player has entered
      * and is waiting on is news when there is some, and a heading over a sentence saying there is none the rest of the
      * time.
      */
    private def waitingSection(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.myChallenges.signal.combineWith(Store.games.signal, currentPlayer).map {
              (challenges, games, player) =>
                  val waiting = challengesIn(game)(challenges).filter { c =>
                      val ready = c.summary.isOwner && !c.summary.tournament.settings.isAutoStarted &&
                          leastToStart(gameOf(games, c.summary.tournament.gameId)).exists(c.entries.size >= _)
                      (c.summary.isEntered || c.summary.isOwner) && !ready
                  }
                  if (waiting.isEmpty) emptyNode
                  else
                      refreshableSection(
                        "Waiting to Start",
                        refreshingChallenges,
                        () => Store.reloadMyChallenges(),
                        subsection = true
                      )(ul(waiting.map(c => waitingRow(c, player))))
          }
        )

    private def waitingRow(c: ChallengeSummary, player: Option[Player]): HtmlElement = {
        val t = c.summary.tournament
        val mine = player.flatMap(me => c.entries.find(_.playerId.contains(me.playerId)))
        val full = t.settings.maxEntrants.exists(c.entries.size >= _)
        li(
          cls := "row",
          div(cls := "title", c.summary.gameName),
          div(cls := "detail", challengeTerms(t, c.entries.size)),
          div(
            cls := "detail",
            if (c.summary.isOwner) "your challenge, waiting for players"
            else if (full) s"full — waiting for ${c.summary.ownerNickname} to start it"
            else s"${c.summary.ownerNickname}'s challenge, waiting for players"
          ),
          // The owner calls their own off; anybody else withdraws their entry. Both are refused once it has started,
          // which `challengeStale` treats as the news it is.
          if (c.summary.isOwner)
              busyButton("Cancel", classes = Some("link")) { busy =>
                  Store.run(ApiClient.tournamentStep(t.gameId, t.tournamentId, "cancel"), busy, challengeStale(t))(_ =>
                      reloadChallengeSections(t.gameId)
                  )
              }
          else
              mine.fold(emptyNode) { entry =>
                  busyButton("Withdraw", classes = Some("link")) { busy =>
                      Store.run(
                        ApiClient.withdrawFromTournament(t.gameId, t.tournamentId, entry.entrantId),
                        busy,
                        challengeStale(t)
                      )(_ => reloadChallengeSections(t.gameId))
                  }
              }
        )
    }

    /** The caller's finished matches in every game, a window of time at a time. */
    private def recentlyCompletedSection: HtmlElement =
        refreshableSection(
          "Recently Completed",
          refreshingCompleted,
          () => Store.reloadCompleted(),
          subsection = false
        )(
          completedWindow(Store.CompletedList.Mine(None), "Nothing finished yet.")(
            // Every game's, so no column for any one game's parameters: they differ from game to game.
            matchTable(
              _,
              showGame = true,
              showParameters = false,
              outcome = playerOutcome,
              view = reviewControl,
              characterColumns = false
            )
          )
        )

    /** One completed list, a window of time at a time: the frame to look back over, which window this is, the matches
      * in it, and Prev and Next to move to the newer and older windows of the same length.
      *
      * The same for every completed list — the main page's, a game's, somebody else's page's — with what is shown of a
      * row left to the caller. Fetches nothing when drawn: the list is asked for when its screen is arrived at or its
      * game opened, and again by the controls here, each of which is a click. See `Store.showCompleted`.
      *
      * Prev is there once the reader has gone back from the most recent window, and Next while the list holds anything
      * older than the window shown: once the oldest match this list could ever show is on screen, there is no Next.
      * `never` is what an empty most-recent window says when there is nothing further back either.
      */
    private def completedWindow(list: Store.CompletedList, never: String)(
        render: Seq[MatchSummary] => HtmlElement
    ): Modifier[HtmlElement] = {
        // This list's window, and nothing while another list's is held or this one's is on its way.
        val view: Signal[Option[CompletedPage]] =
            Store.completedView.signal.map(_.filter(_.list == list).map(_.page)).distinct
        // This list's own request and its own failure -- not `Fetch.Completed`'s, which is the last
        // request's for whichever list asked.
        val busy = Store.completedLoading.signal.map(_.contains(list)).distinct
        val failed = Store.completedFailed.signal.map(_.contains(list)).distinct

        /* Try again, for a list whose first window failed to load. It stays where it is while the
         * retry is out -- so the focus on it is not dropped -- and refuses a second press rather than
         * being disabled, which would drop the focus just the same. The status line says what is
         * happening; once there is a window, the button gives way to it.
         *
         * The focus follows to the status line, which is then saying which window it is -- but only if
         * the button still had it when it went. A reader who moved on while the retry was out is left
         * where they went, and hears the result from the live line. Whether it had it is noted as the
         * button is unmounted, which Laminar does before taking it out of the page: by the time the
         * retry's answer is handled it is gone, and the focus with it. */
        val retrying = Var(false)
        var statusLine: Option[dom.html.Element] = None
        var retryHadFocus = false

        def retry(): Unit =
            if (!Store.completedLoading.now().contains(list)) {
                retrying.set(true)
                retryHadFocus = false
                Store.showCompleted(list).onComplete { _ =>
                    retrying.set(false)
                    if (retryHadFocus && Store.completedView.now().exists(_.list == list))
                        statusLine.foreach(_.focus())
                    retryHadFocus = false
                }
            }

        // Built once and shown or not, rather than rebuilt with each change of state, so that it is
        // the same element -- and keeps the focus -- from the press to the answer.
        val retryButton =
            button(
              tpe := "button",
              cls := "link",
              aria.disabled <-- busy,
              child <-- busy.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
              "Try again",
              onClick --> (_ => retry()),
              onUnmountCallback(button => retryHadFocus = dom.document.activeElement == button.ref)
            )
        val showRetry = view
            .combineWith(failed, retrying.signal)
            .map {
                case (None, failedNow, asking) => failedNow || asking
                case _                         => false
            }
            .distinct

        def move(to: CompletedPage => Int): Unit =
            Store.completedView.now().filter(_.list == list).foreach { current =>
                Store.showCompleted(list, to(current.page), Some(current.page.asOf))
            }

        /* Named by the window it goes to -- "Previous week", "Next 24 hours" -- in the frame shown. */
        def step(
            word: String,
            direction: String,
            shown: CompletedPage => Boolean,
            to: CompletedPage => Int
        ) =
            child <-- view.map {
                case Some(page) if shown(page) =>
                    val caption = s"$word ${page.frame.period}"
                    button(
                      tpe := "button",
                      cls := s"link $direction",
                      // The visible words first, then which way they go, which "next" alone does not say.
                      aria.label := s"$caption: $direction completed matches",
                      // Not disabled while a window is on its way, which would drop the keyboard's focus.
                      aria.disabled <-- busy,
                      caption,
                      onClick --> (_ => if (!Store.completedLoading.now().contains(list)) move(to)),
                      // Gone as the window it asked for arrives, so a step that had the focus hands it to the
                      // line saying which window is now shown, rather than letting it fall to the top of the
                      // page.
                      onUnmountCallback(node =>
                          if (dom.document.activeElement == node.ref) statusLine.foreach(_.focus())
                      )
                    )
                case _ => emptyNode
            }

        Seq(
          field(
            "Show",
            select(
              CompletedFrame.values.toSeq.map(frame => option(value := frame.code, frame.label)),
              value <-- Store.completedFrame.signal.map(_.code),
              onChange.mapToValue --> { code =>
                  CompletedFrame.fromCode(code).foreach { frame =>
                      Store.completedFrame.set(frame)
                      Store.showCompleted(list)
                  }
              }
            )
          ),
          // What the list is: which stretch of time its rows are from, or that they are on their way, or
          // that they could not be had. Said politely when it changes -- which is what Prev, Next, the
          // frame and Try again do -- from one line that is always there, since a line that appears
          // with its news is not announced. Focusable from script only: Try again sends the focus here
          // once it is answered.
          p(
            cls := "detail",
            aria.live := "polite",
            tabIndex := -1,
            onMountCallback(context => statusLine = Some(context.thisNode.ref)),
            onUnmountCallback(_ => statusLine = None),
            child.text <-- view.combineWith(failed).map {
                case (None, true)                      => "These matches could not be loaded."
                case (None, false)                     => "Loading…"
                case (Some(page), _) if page.page == 0 => s"Finished in the ${page.frame.label.toLowerCase}"
                case (Some(page), _) =>
                    s"Finished between ${Format.instant(page.from)} and ${Format.instant(page.until)}"
            }
          ),
          child <-- showRetry.map(if (_) retryButton else emptyNode),
          child <-- view.map {
              case None                                           => emptyNode
              case Some(page) if page.matches.nonEmpty            => render(page.matches)
              case Some(page) if page.page == 0 && !page.hasOlder => p(cls := "empty", never)
              case Some(page) if page.hasOlder =>
                  p(cls := "empty", s"None finished in this time. Previous ${page.frame.period} goes further back.")
              case Some(_) => p(cls := "empty", "None finished in this time.")
          },
          div(
            cls := "completed-steps",
            // Earlier on the left, later on the right, as time reads.
            step("Previous", "older", _.hasOlder, _.page + 1),
            step("Next", "newer", _.page > 0, _.page - 1)
          )
        )
    }

    // -------------------------------------------------------------------------
    // Players
    // -------------------------------------------------------------------------

    /* Whether a search is in flight, so the box can say so. At this level because the search is
     * submitted two ways -- the button, and Enter in the field -- and both mean the same thing. */
    private val searchingPlayers: Var[Boolean] = Var(false)

    private def runPlayerSearch(): Unit = refresh(searchingPlayers, () => Store.searchPlayers())

    /** The screen that finds a player by the start of their nickname.
      *
      * A prefix rather than a substring — the start of a name, not any part of it — and nothing else to explain: case
      * is ignored, and so is how the name was spaced, so there is no rule for the searcher to get right. Nicknames
      * themselves are still case sensitive, which is why two players whose names differ only in case both appear here,
      * each spelled as they registered.
      */
    private def findPlayersPage: HtmlElement =
        sectionTag(
          cls := "refreshable",
          div(cls := "section-head", h2("Find Players")),
          div(
            cls := "section-body",
            form(
              cls := "search",
              // Enter in the field submits, which is what a search box does; without this it would
              // reload the page and sign the player out of the screen they are looking at.
              onSubmit.preventDefault.filter(_ => !searchingPlayers.now()) --> (_ => runPlayerSearch()),
              field(
                "Nickname begins with",
                input(
                  tpe := "search",
                  // A nickname is not a word the browser has seen before, and a dropdown of the
                  // player's own past searches over the results is in the way rather than helpful.
                  autoComplete := "off",
                  controlled(value <-- Store.playerSearch.signal, onInput.mapToValue --> Store.playerSearch)
                )
              ),
              button(
                tpe := "submit",
                disabled <-- Store.playerSearch.signal.map(_.trim.isEmpty),
                // Not disabled while searching, which would drop the keyboard's focus: the form refuses
                // a second search meanwhile instead.
                aria.disabled <-- searchingPlayers.signal,
                child <-- searchingPlayers.signal.map(
                  if (_) span(cls := "spinner", aria.hidden := true) else emptyNode
                ),
                "Search"
              )
            ),
            // The live region is this container, which is mounted once, rather than the message
            // inside it: a region created at the moment it has something to say is announced by
            // nothing. So the results are replaced *within* an element that was already there.
            div(
              aria.live := "polite",
              child <-- Store.playerResults.signal.map(playerResults)
            )
          )
        )

    private def playerResults(found: Option[PlayerSearchResult]): HtmlElement = found match {
        // Nothing has been searched for yet, which is not the same as nothing having been found.
        case None                                   => p(cls := "empty", "Search for the start of a nickname.")
        case Some(result) if result.players.isEmpty => p(cls := "empty", "No player's nickname starts with that.")
        case Some(result) =>
            div(
              ul(result.players.map(playerResultRow)),
              // Said only when there are others, and said as the remedy rather than as a limit: the
              // searcher cannot ask for page two, and would not want it — the next page of a name
              // search is a longer prefix, which is the one thing they can do from here.
              if (result.hasMore)
                  div(
                    cls := "detail",
                    s"Showing the first ${result.players.length}. Type more of the nickname to narrow it."
                  )
              else emptyNode
            )
    }

    /* The nickname is the link, rather than a name beside a "View" button: the row has one thing to
     * do, and a control whose name is the player being opened needs no other label. */
    private def playerResultRow(player: PublicPlayer): HtmlElement =
        li(cls := "row", playerLink(player))

    /** A player's nickname as the way to their page: the page a "Find Players" search opens onto. */
    private def playerLink(player: PublicPlayer): HtmlElement =
        button(
          tpe := "button",
          cls := "link",
          player.nickname,
          onClick --> (_ => Store.show(Store.Page.OnePlayer(player)))
        )

    /** Who played an opposing seat, as the way to their page; a seat whose character had no owner was nobody's. */
    private def seatPlayer(o: Opponent): HtmlElement =
        o.player.fold(span(cls := "detail", "nobody"))(playerLink)

    /** A character's name as the way to its page, as [[playerLink]] is a player's. */
    private def characterLink(character: CharacterName): HtmlElement =
        button(
          tpe := "button",
          cls := "link",
          character.name,
          onClick --> (_ => Store.show(Store.Page.OneCharacter(character)))
        )

    /* One flag for the whole of a player's page, because one button reloads all of it: both lists
     * come of the one action, and dimming half a screen that is being replaced whole would be a lie
     * to anything reading it. */
    private val refreshingPublicMatches: Var[Boolean] = Var(false)

    /** Somebody else's page: a row for every game, each opening onto what that player has played of it.
      *
      * Only their public matches, which is their own statement about which ones may be looked at — the "Public" box on
      * the challenge the match was started from. Nothing here is filtered in the browser: the server answers with the
      * public ones and nothing else, so a private match is not among the rows this hides.
      *
      * A row per game rather than a row per game they have played, so the page is the same shape for every player and
      * the count on each row is the answer to "have they played this?" — which is a thing worth being told without
      * having to open anything.
      */
    private def playerPage(player: PublicPlayer): HtmlElement =
        div(
          h2(player.nickname),
          // Back to the results that opened this, which are still held: the store keeps the box and
          // the answer, so the way back is the list as it was rather than a search to type again.
          button(
            tpe := "button",
            cls := "link",
            "Back to search",
            onClick --> (_ => Store.show(Store.Page.FindPlayers))
          ),
          inviteControl(player),
          // Their matches in every game, as the main page lists the reader's own -- but only the ones
          // they made public. One reload fills both, so both are marked as reloading by it.
          refreshableSection(
            "Current Matches",
            refreshingPublicMatches,
            () => Store.reloadPublicMatches(player.playerId),
            subsection = false
          )(
            listing(Store.publicActive.signal, Store.publicMatchesLoading.signal)(
              p(cls := "empty", "None being played in public.")
            )(
              matchTable(
                _,
                showGame = true,
                showParameters = false,
                outcome = waitingFor,
                view = watchControl,
                dateHeading = "Started",
                dated = summary => Some(summary.start),
                showResult = false
              )
            )
          ),
          refreshableSection(
            "Recently Completed",
            refreshingPublicMatches,
            () => Store.reloadPublicMatches(player.playerId),
            subsection = false
          )(
            completedWindow(Store.CompletedList.Public(player.playerId, None), "None finished in public.")(
              matchTable(_, showGame = true, showParameters = false, outcome = publicOutcome, view = watchControl)
            )
          ),
          // A game at a time, for what is about one game: the challenges the reader has offered them in
          // it, and an admin's view of their matches there.
          sectionTag(
            h2("Games"),
            listing(Store.games.signal, Store.loading(Store.Fetch.Games))(p(cls := "empty", "No games yet."))(games =>
                ul(games.map(publicGameRow(player, _)))
            )
          )
        )

    /** A character's page: what its owner says about it, and its matches -- the public ones, as on a player's page, and
      * for the same reason. Each is listed against the opposing character, with the player who owned that character for
      * the match, since a character can change hands and its old matches are still its own.
      */
    private def characterPage(character: CharacterName): HtmlElement =
        div(
          // By the name the profile gives, which is the character's name now; the link's until it has come.
          h2(
            child.text <-- Store.characterProfile.signal.map(
              _.filter(_.character.characterId == character.characterId).fold(character.name)(_.character.name)
            )
          ),
          child <-- Store.characterProfile.signal.combineWith(Store.publicMatchesLoading.signal).map {
              // Only the answer about this character: another one's, still held, is not it.
              case (Some(profile), _) if profile.character.characterId == character.characterId =>
                  div(
                    cls := "character-profile",
                    p(
                      cls := "detail",
                      s"A character in ${profile.gameName}, ",
                      profile.owner.fold[Modifier[HtmlElement]]("owned by nobody now")(owner =>
                          span("owned by ", playerLink(owner))
                      )
                    ),
                    withTip(
                      freshTipId("record-tip"),
                      "record",
                      "Wins, losses and draws in the matches it has finished that were not friendly, whoever owned " +
                          "it for them. In brackets, how many of the wins and losses came by forfeit."
                    )(p(cls := "character-record", "Record: ", recordText(profile.record))),
                    if (profile.description.trim.isEmpty) emptyNode
                    else p(cls := "character-description", profile.description)
                  )
              case (_, true) => p(cls := "detail", "Loading…")
              case _         => emptyNode
          },
          refreshableSection(
            "Current Matches",
            refreshingPublicMatches,
            () => Store.reloadCharacterMatches(character),
            subsection = false
          )(
            listing(Store.publicActive.signal, Store.publicMatchesLoading.signal)(
              p(cls := "empty", "None being played in public.")
            )(
              matchTable(
                _,
                showGame = false,
                showParameters = true,
                outcome = waitingFor,
                view = watchControl,
                dateHeading = "Started",
                dated = summary => Some(summary.start),
                showResult = false,
                aboutCharacter = true
              )
            )
          ),
          refreshableSection(
            "Recently Completed",
            refreshingPublicMatches,
            () => Store.reloadCharacterMatches(character),
            subsection = false
          )(
            completedWindow(Store.CompletedList.OfCharacter(character), "None finished in public.")(
              matchTable(
                _,
                showGame = false,
                showParameters = true,
                outcome = publicOutcome,
                view = watchControl,
                aboutCharacter = true
              )
            )
          )
        )

    /** Asking this player for a game: pick the game, and go and compose the challenge.
      *
      * Two steps rather than one, because a challenge is more than who it is for — it has a clock, a number of players,
      * the game's settings — and all of that lives in the form on the game's screen. So this carries the one thing that
      * screen cannot ask for, the player, and takes the reader there with the form already open.
      *
      * Not shown to a player looking at their own page: the server refuses a challenger inviting themselves, and a
      * control that cannot work is worse than none. Nor when no games have loaded, since there would be nothing to
      * choose.
      */
    private def inviteControl(invitee: PublicPlayer): HtmlElement = {
        val chosen = Var(Option.empty[GameId])

        div(
          child <-- Store.games.signal.combineWith(currentPlayer).map { (games, me) =>
              if (games.isEmpty || me.exists(_.playerId == invitee.playerId)) emptyNode
              else {
                  // Pre-selected rather than left blank: one game is the usual case, and a select whose
                  // first entry is "choose one" is a step that answers nothing.
                  if (chosen.now().isEmpty) chosen.set(games.headOption.map(_.gameId))
                  div(
                    cls := "card",
                    h3(s"Challenge ${invitee.nickname}"),
                    field(
                      "Game",
                      select(
                        onChange.mapToValue --> { raw =>
                            chosen.set(raw.toIntOption.map(GameId.apply).filter(id => games.exists(_.gameId == id)))
                        },
                        value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                        games.map(game => option(value := game.gameId.value.toString, game.displayName))
                      )
                    ),
                    button(
                      tpe := "button",
                      disabled <-- chosen.signal.map(_.isEmpty),
                      s"Offer ${invitee.nickname} a challenge",
                      // One call, because the three things it does have to happen in one order:
                      // `Store.show` clears the form and the invitee, so that arriving at a game any
                      // other way cannot inherit either.
                      onClick --> (_ => chosen.now().foreach(gameId => Store.showGameToInvite(gameId, invitee)))
                    ),
                    // Rebuilt per game, so the choice of challenge starts over when the game does.
                    child <-- chosen.signal.map(
                      _.flatMap(id => games.find(_.gameId == id)) match {
                          case Some(game) => addToExisting(game, invitee)
                          case None       => emptyNode
                      }
                    )
                  )
              }
          }
        )
    }

    /** Inviting this player to a challenge the reader has already offered in `game`, rather than composing a new one.
      *
      * Lists the reader's own open challenges there that neither invite this player already nor have them in, since a
      * second invitation changes nothing and somebody already in has nothing to be asked to.
      */
    private def addToExisting(game: Game, invitee: PublicPlayer): HtmlElement = {
        val picked = Var(Option.empty[TournamentId])

        div(
          onMountCallback(_ => Store.refreshChallenges(game.gameId)),
          child <-- Store.challengesByGame.signal.map(_.get(game.gameId)).map {
              case Some(challenges) =>
                  // In a character game it is one of their characters that is invited, so a challenge already asking
                  // one of them is still one to ask another to; one they are in is not.
                  val mine = challenges.filter(c =>
                      c.summary.isOwner && !c.entries.exists(_.playerId.contains(invitee.playerId)) &&
                          (game.gameType == GameType.Character || !c.invited.exists(_.playerId == invitee.playerId))
                  )
                  if (mine.isEmpty) emptyNode
                  else {
                      // Kept when the list is re-read, as long as the challenge is still in it.
                      if (!picked.now().exists(id => mine.exists(_.summary.tournament.tournamentId == id)))
                          picked.set(mine.headOption.map(_.summary.tournament.tournamentId))
                      div(
                        h4(s"Or add ${invitee.nickname} to a challenge you have already offered"),
                        field(
                          "Challenge",
                          select(
                            onChange.mapToValue --> { raw =>
                                picked.set(
                                  raw.toLongOption
                                      .map(TournamentId.apply)
                                      .filter(id => mine.exists(_.summary.tournament.tournamentId == id))
                                )
                            },
                            value <-- picked.signal.map(_.map(_.value.toString).getOrElse("")),
                            // Described by its terms, never by an id.
                            mine.map(c =>
                                option(
                                  value := c.summary.tournament.tournamentId.value.toString,
                                  challengeTerms(c.summary.tournament, c.entries.size)
                                )
                            )
                          )
                        ),
                        if (game.gameType == GameType.Character)
                            // Rebuilt per challenge, leaving out the characters it already asks.
                            child <-- picked.signal.map(
                              _.flatMap(id => mine.find(_.summary.tournament.tournamentId == id)) match {
                                  case Some(c) =>
                                      val t = c.summary.tournament
                                      characterInviteButtons(
                                        game.gameId,
                                        invitee,
                                        c.invitedCharacters.map(_.character.characterId).toSet
                                      ) { (ch, busy) =>
                                          Store.run(
                                            ApiClient.inviteCharacterToTournament(
                                              game.gameId,
                                              t.tournamentId,
                                              ch.characterId
                                            ),
                                            busy,
                                            challengeStale(t)
                                          )(_ => Store.refreshChallenges(game.gameId))
                                      }
                                  case None => emptyNode
                              }
                            )
                        else
                            busyButton(s"Invite ${invitee.nickname}", disabledWhen = picked.signal.map(_.isEmpty)) {
                                busy =>
                                    picked.now().foreach { id =>
                                        Store.run(
                                          ApiClient.inviteToTournament(game.gameId, id, invitee.playerId),
                                          busy,
                                          challengeStale(
                                            mine.find(_.summary.tournament.tournamentId == id).get.summary.tournament
                                          )
                                        )(_ => Store.refreshChallenges(game.gameId))
                                    }
                            }
                      )
                  }
              case None => emptyNode
          }
        )
    }

    /** One game on a player's page: its name, how many of its matches they are playing, and — when opened — the
      * matches.
      *
      * The count is drawn whether or not the row is open. Finished matches are not counted: they are a list a window of
      * time at a time, fetched for the game that is opened, and the row does not fetch them all to count them.
      */
    private def publicGameRow(player: PublicPlayer, game: Game): HtmlElement = {
        val expanded = Store.expandedPublicGame.signal.map(_.contains(game.gameId))
        val running = Store.publicActive.signal.map(_.filter(_.gameId == game.gameId))

        li(
          cls := "row",
          button(
            tpe := "button",
            cls := "toggle",
            // The state is announced rather than spelled into the label, so the label stays the name
            // of the game — which is what the reader is scanning the list for.
            aria.expanded <-- expanded,
            game.displayName,
            onClick --> { _ =>
                Store.expandedPublicGame.update(current =>
                    if (current.contains(game.gameId)) None else Some(game.gameId)
                )
            }
          ),
          div(
            cls := "detail",
            // The same rule the lists themselves follow: what is held goes on being counted while a
            // reload is in flight — the section dims to say so — and "loading…" is for the case where
            // there is nothing to count yet, which is a page just opened rather than a player with
            // nothing to show. Saying "0 being played" in that moment would be a number about to
            // change.
            child.text <-- running.combineWith(Store.publicMatchesLoading.signal).map {
                case (active, loading) if active.isEmpty && loading => "loading…"
                case (active, _)                                    => s"${active.length} being played"
            }
          ),
          child <-- expanded.map {
              if (_)
                  div(
                    cls := "detail-panel",
                    offeredChallenges(game, player),
                    adminMatchesSection(game, player)
                  )
              else emptyNode
          }
        )
    }

    /** The challenges the reader has offered this player in this game and that have not started: the same rows as "Your
      * Open Challenges" on the game's screen, so they can be started, cancelled, or have their invitations changed from
      * here. Offered to them means inviting them, or having them in it already. Nothing at all on the reader's own
      * page.
      */
    private def offeredChallenges(game: Game, invitee: PublicPlayer): HtmlElement =
        div(
          onMountCallback(_ => Store.refreshChallenges(game.gameId)),
          child <-- currentPlayer
              .combineWith(Store.challengesByGame.signal)
              .map {
                  case (Some(me), _) if me.playerId == invitee.playerId => emptyNode
                  case (Some(_), byGame) if byGame.contains(game.gameId) =>
                      val offered = byGame(game.gameId).filter { c =>
                          c.summary.isOwner &&
                          (c.invited.exists(_.playerId == invitee.playerId) ||
                              c.invitedCharacters.exists(_.owner.exists(_.playerId == invitee.playerId)) ||
                              c.entries.exists(_.playerId.contains(invitee.playerId)))
                      }
                      div(
                        h3(s"Your Challenges to ${invitee.nickname}"),
                        if (offered.isEmpty) p(cls := "empty", "None waiting.")
                        else ul(offered.map(myChallengeRow(game, _)))
                      )
                  case _ => p(cls := "detail", "Loading your challenges…")
              }
        )

    /** Another player's characters in a game, by name only -- which is all another player may see of them -- drawn by
      * `render` once they have come, for inviting one: in a character game it is a character that is invited.
      *
      * The answer is dropped unless this same mount is still the one waiting for it and the session that asked is still
      * signed in: a late answer about the player shown before would otherwise draw their characters under somebody
      * else's name. A counter rather than a mounted flag, because a flag reads true again if the same element is
      * remounted before the answer lands. The status line is announced, because what came back decides whether there is
      * anything to invite at all.
      */
    private[ui] def theirCharacters(gameId: GameId, owner: PublicPlayer, exclude: Set[CharacterId])(
        render: Seq[CharacterName] => HtmlElement
    ): HtmlElement = {
        // None while the request is out; Left when it failed.
        val loaded = Var(Option.empty[Either[String, Seq[CharacterName]]])
        var mount = 0
        div(
          onMountCallback { _ =>
              mount += 1
              val asked = mount
              val signIn = Store.currentSignIn
              ApiClient.characterNames(gameId, owner.playerId).onComplete { answer =>
                  if (asked == mount && Store.isStillSignedInAs(signIn)) answer match {
                      case scala.util.Success(names) =>
                          loaded.set(Some(Right(names.filterNot(c => exclude.contains(c.characterId)))))
                      case scala.util.Failure(_) =>
                          loaded.set(Some(Left(s"Couldn't load ${owner.nickname}'s characters just now.")))
                  }
              }
          },
          onUnmountCallback(_ => mount += 1),
          div(
            role := "status",
            aria.live := "polite",
            child <-- loaded.signal.map {
                case None              => p(cls := "detail", s"Loading ${owner.nickname}'s characters...")
                case Some(Left(error)) => p(cls := "empty", error)
                case Some(Right(names)) if names.isEmpty =>
                    p(cls := "empty", s"${owner.nickname} has no character in this game that could be invited.")
                case Some(Right(_)) => emptyNode
            }
          ),
          child <-- loaded.signal.map {
              case Some(Right(names)) if names.nonEmpty => render(names)
              case _                                    => emptyNode
          }
        )
    }

    /** [[theirCharacters]] as a button per character, each inviting it with `invite`. */
    private[ui] def characterInviteButtons(gameId: GameId, owner: PublicPlayer, exclude: Set[CharacterId])(
        invite: (CharacterName, Var[Boolean]) => Unit
    ): HtmlElement =
        theirCharacters(gameId, owner, exclude)(names =>
            ul(
              names.map(c =>
                  li(
                    cls := "row",
                    div(cls := "title", c.name),
                    busyButton(s"Invite ${c.name}", classes = Some("link"))(busy => invite(c, busy))
                  )
              )
            )
        )

    /** [[theirCharacters]] as a picker writing the chosen one into `chosen`, the first of them to begin with. */
    private def characterChooser(
        gameId: GameId,
        owner: PublicPlayer,
        exclude: Set[CharacterId],
        chosen: Var[Option[CharacterId]]
    ): HtmlElement =
        theirCharacters(gameId, owner, exclude) { names =>
            field(
              "Their character",
              select(
                onMountCallback(_ =>
                    if (!chosen.now().exists(id => names.exists(_.characterId == id))) {
                        chosen.set(names.headOption.map(_.characterId))
                    }
                ),
                onChange.mapToValue --> { raw =>
                    chosen.set(raw.toLongOption.map(CharacterId.apply).filter(id => names.exists(_.characterId == id)))
                },
                value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                names.map(c => option(value := c.characterId.value.toString, c.name))
              )
            )
        }

    /** Who a match still being played is waiting on, under its row on somebody else's page. Named, and never "your
      * turn": a turn on this page is somebody else's by construction.
      */
    private def waitingFor(summary: MatchSummary): Modifier[HtmlElement] =
        if (summary.whoseTurn.nonEmpty) div(cls := "detail", s"waiting for ${summary.whoseTurn.mkString(", ")}")
        else div(cls := "detail", "waiting for the other players")

    /** A finished match's board, for its own player: "Review game". Fetched the way "Play" is — the urls live on the
      * match, not on the summary — with `publicUrl` the fallback for a match whose play url the engine has since
      * stopped honouring. Nothing for a cancelled match, which has no board worth looking at; a note instead of the
      * button once a friendly match's archive has expired.
      */
    private def reviewControl(summary: MatchSummary): HtmlElement =
        if (summary.isCompleted && summary.isArchiveExpired) archiveExpiredNote
        else if (summary.isCompleted)
            busyButton("Review game", classes = Some("link")) { busy =>
                Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                    m.playUrl.orElse(m.publicUrl) match {
                        case Some(url) => openSignedIn(url)
                        // Found expired by this very request: the list was read before it was.
                        case None if m.isArchiveExpired =>
                            Store.reportProblem(
                              "This match's archive has expired: friendly matches are kept for 30 days."
                            )
                        case None => Store.reportProblem("This match has no url to view.")
                    }
                }
            }
        else span()

    /** Somebody else's public match, for anyone who cares to look: "Watch", straight off the summary's public url. None
      * where the engine serves no spectator page — a button that opens nothing is worse than none — and a note instead
      * once a friendly match's archive has expired.
      */
    private def watchControl(summary: MatchSummary): HtmlElement =
        if (summary.isArchiveExpired) archiveExpiredNote
        else
            summary.publicUrl
                .map(url => button(tpe := "button", cls := "link", "Watch", onClick --> (_ => openSignedIn(url))))
                .getOrElse(span())

    /** How the caller's own finished match came out: the engine's account where it gave one, and every seat's result
      * where it did not.
      */
    private def playerOutcome(summary: MatchSummary): Modifier[HtmlElement] =
        summary.resultSummary.fold(resultTable(summary))(resultSummary)

    /** How somebody else's public match came out, where the engine said: their page has no result table. */
    private def publicOutcome(summary: MatchSummary): Modifier[HtmlElement] =
        summary.resultSummary.map(resultSummary).getOrElse(emptyNode)

    /** Matches as a table: a row per match — its game (where the list mixes games), who it was played against, when it
      * ended (or, for matches still being played, started), what it did to their Elo rating -- or, for matches still
      * being played, whether it is friendly -- one column per game parameter (on a page about one game, where every
      * match has the same ones), and the way to look at its board — and under each, a row of its own across the table:
      * how a finished match came out, marked won, lost or drawn, or whom a running one is waiting for.
      *
      * The parameter columns are the parameters the matches shown were played under, in the order the first of them
      * lists them, so a game with none has no such column.
      *
      * Where any match shown is a character game's, the opponent is the character played against, and two columns join
      * it: first, the character the list's own seat played -- unless the list is a character's own page
      * (`aboutCharacter`), where every row would name the same one -- and after it the player who owned each opposing
      * character for that match.
      */
    private def matchTable(
        matches: Seq[MatchSummary],
        showGame: Boolean,
        showParameters: Boolean,
        outcome: MatchSummary => Modifier[HtmlElement],
        view: MatchSummary => HtmlElement,
        // The date column: when a finished match ended, or -- for a list of matches still being played --
        // when one started.
        dateHeading: String = "Completed",
        dated: MatchSummary => Option[java.time.Instant] = _.completedAt,
        // A column for won, lost or drawn: only where the matches are finished and there is one to say.
        showResult: Boolean = true,
        // Whether the list is one character's matches, which leaves out the column naming it.
        aboutCharacter: Boolean = false,
        // Whether a character game's matches get its columns at all; without them every row is laid out as a plain
        // game's, the opponent named as a player.
        characterColumns: Boolean = true
    ): HtmlElement = {
        val parameters =
            if (showParameters) matches.flatMap(_.parameters.map(_.displayName)).distinct else Seq.empty
        val showOwner = characterColumns && matches.exists(_.character.isDefined)
        val showCharacter = showOwner && !aboutCharacter
        val columns = Seq(showGame, showResult, showCharacter, showOwner).count(identity) + 4 + parameters.size

        def matchRow(summary: MatchSummary): Seq[HtmlElement] = {
            // Everyone else in it, each a way to their page -- the character's, where they played one; a match
            // played alone has nobody to name.
            def listed(links: Seq[HtmlElement]): Seq[HtmlElement] =
                links.zipWithIndex.map((link, i) => span(if (i > 0) ", " else emptyNode, link))
            val against: Modifier[HtmlElement] =
                if (summary.opponents.isEmpty) "nobody"
                else
                    listed(summary.opponents.map { o =>
                        o.character.filter(_ => showOwner).fold(seatPlayer(o))(characterLink)
                    })
            // Who owned each opposing character for the match; nothing for a plain game's row, whose opponent is
            // already the player.
            val owners = summary.opponents.filter(_.character.isDefined).map(seatPlayer)

            val ended: Modifier[HtmlElement] =
                dated(summary) match {
                    case Some(when) =>
                        // The day, with the instant it was trimmed from kept machine-readable.
                        timeTag(
                          htmlAttr("datetime", com.raquo.laminar.codecs.StringAsIsCodec) := when.toString,
                          Format.date(when)
                        )
                    case None => if (summary.isCancelled) "cancelled" else ""
                }
            val told = outcome(summary)
            Seq(
              tr(
                cls := "match",
                role := "row",
                // Labelled in the cell, as the parameters are, for the narrow layout's card.
                if (showCharacter)
                    td(
                      role := "cell",
                      cls := "character",
                      summary.character.map(_ => dataAttr("label") := "Character"),
                      summary.character.map(characterLink)
                    )
                else emptyNode,
                // Who it was against heads its row, for a screen reader.
                th(scopeAttr := "row", role := "rowheader", span(cls := "opponent", against)),
                if (showOwner)
                    td(
                      role := "cell",
                      cls := "owner",
                      Option.when(owners.nonEmpty)(dataAttr("label") := "Owner"),
                      listed(owners)
                    )
                else emptyNode,
                if (showGame) td(role := "cell", cls := "game", summary.gameName) else emptyNode,
                if (showResult)
                    td(role := "cell", cls := "result", summary.outcome.map(outcomeMark).getOrElse(emptyNode))
                else emptyNode,
                td(role := "cell", ended),
                // Where the matches are finished, what each did to the player's rating -- or, for a friendly match,
                // which moved nobody, that it was one. Otherwise a mark for a friendly match and nothing for a rated
                // one, with a word for a screen reader either way.
                if (showResult) eloCell(summary)
                else
                    td(
                      role := "cell",
                      cls := "friendly",
                      if (summary.isFriendly) span(cls := "mark", aria.hidden := true, "✓") else emptyNode,
                      span(cls := "sr-only", if (summary.isFriendly) "friendly" else "rated")
                    ),
                // Named in the cell as well as the heading, for the narrow layout that has no heading row to show.
                parameters.map(parameter =>
                    td(
                      role := "cell",
                      dataAttr("label") := parameter,
                      summary.parameters.find(_.displayName == parameter).map(_.value).getOrElse("")
                    )
                ),
                td(role := "cell", cls := "board", view(summary))
              ),
              // How it came out, set apart in colour: the line to read under the row. Indented by a column, an
              // empty cell under the first, so that it reads as the row's own detail.
              tr(
                cls := "outcome",
                role := "row",
                td(role := "cell", cls := "indent"),
                td(
                  role := "cell",
                  colSpan := columns - 1,
                  if (summary.isCancelled) div(cls := "detail", "Cancelled by its creator.") else emptyNode,
                  told
                )
              )
            )
        }

        def heading(content: Modifier[HtmlElement]*): HtmlElement =
            th(scopeAttr := "col", role := "columnheader", content)

        // The roles are the ones the elements have anyway, said outright: on a phone the table is laid out as a card
        // per match, and a browser may stop treating a table whose parts are no longer displayed as one as a table.
        div(
          cls := "table-scroll",
          table(
            cls := "match-table",
            role := "table",
            thead(
              role := "rowgroup",
              tr(
                role := "row",
                if (showCharacter) heading("Character") else emptyNode,
                heading("Opponent"),
                if (showOwner) heading("Owner") else emptyNode,
                if (showGame) heading("Game") else emptyNode,
                if (showResult) heading(cls := "result", "Result") else emptyNode,
                heading(dateHeading),
                if (showResult) heading(cls := "elo", "Elo") else heading(cls := "friendly", friendlyHeading()),
                parameters.map(parameter => heading(parameter)),
                heading(span(cls := "sr-only", "Board"))
              )
            ),
            // A body per match, so its two rows read as one.
            matches.map(summary => tbody(role := "rowgroup", matchRow(summary)))
          )
        )
    }

    /** A finished match's Elo cell: the change it made to the player's rating, signed, or a "?" saying it was friendly
      * -- a tip on what that means -- for a match that changed nobody's. Empty for one with no change recorded: called
      * off, or finished before ratings were kept. Labelled for the narrow layout, which has no heading row to show.
      */
    private def eloCell(summary: MatchSummary): HtmlElement =
        if (summary.isFriendly)
            td(
              role := "cell",
              cls := "elo",
              // Only the "?", a word being too wide for the column; named for a screen reader by what it marks.
              tipped(freshTipId("friendly-tip"), friendlyMeaning, emptyNode)(
                cls := "tip-toggle",
                aria.label := "Friendly match",
                "?"
              ).amend(cls := "inline-tip")
            )
        else
            td(
              role := "cell",
              cls := "elo",
              summary.eloDelta.map(_ => dataAttr("label") := "Elo"),
              summary.eloDelta.map {
                  case delta if delta > 0 => s"+$delta"
                  case delta if delta < 0 => s"−${-delta}"
                  case _                  => "±0"
              }
            )

    /** A finished match's Result cell: won, lost or drawn, for whoever the list is about. The mark is drawn for the
      * eye, and the word is what a screen reader hears.
      */
    private def outcomeMark(outcome: MatchOutcome): HtmlElement = {
        val (glyph, word) = outcome match {
            case MatchOutcome.Won  => ("✓", "Won")
            case MatchOutcome.Lost => ("✗", "Lost")
            case MatchOutcome.Drew => ("=", "Drawn")
        }
        span(
          cls := s"outcome-mark ${outcome.code.toLowerCase}",
          title := word,
          span(aria.hidden := true, glyph),
          span(cls := "sr-only", word)
        )
    }

    /** The game's parameters as this match is played under them, one to a line, under what names the match.
      *
      * On running and finished rows alike: unlike the clock, these are what the match *is* — twelve rounds rather than
      * three — and they are as much a part of how it ended as of how it is going.
      */
    private def matchParameters(summary: MatchSummary): Modifier[HtmlElement] =
        if (summary.parameters.isEmpty) emptyNode
        else
            ul(
              cls := "parameters",
              aria.label := "Game parameters",
              summary.parameters.map(p => li(cls := "detail", s"${p.displayName}: ${p.value}"))
            )

    /** Where a Review or Watch link would be, for a friendly match whose archive has expired. */
    private def archiveExpiredNote: HtmlElement =
        div(cls := "detail", "archive expired — friendly matches are kept for 30 days")

    /** Opens a game engine's page — a board, a spectator's view, a character page — in a new tab, signed in as the
      * player signed in here. See `Auth.handOff`.
      *
      * Opened at once when the hand-off is ready at once, which keeps it inside the click a browser requires a popup to
      * come from; only a session that has to be refreshed first waits for that.
      */
    private def openSignedIn(url: String): Unit = {
        def open(target: String): Unit = { dom.window.open(target, "_blank", "noopener,noreferrer"); () }
        val handed = Auth.handOff(url)
        handed.value match {
            case Some(scala.util.Success(target)) => open(target)
            case _                                => handed.foreach(open)
        }
    }

    /** A match it is the caller's turn in, on the "Your Turn" list: what it is, and the clock on that turn. Saying
      * "your turn" here would repeat the heading on every row.
      */
    private def matchRow(summary: MatchSummary): HtmlElement =
        li(
          cls := "row",
          div(cls := "title", summary.gameName),
          div(cls := "detail", summary.description),
          if (summary.isFriendly) friendlyLabel() else emptyNode,
          matchParameters(summary),
          summary.due.map(countdown).getOrElse(emptyNode),
          timeLimitDetail(summary.timeLimit, summary.timeLimitKind, summary.timeLimitUnit, summary.isLive),
          playControls(summary),
          matchNotifications(summary)
        )

    /** How a match the caller is playing stands, under its row in "Current Matches": whose turn it is, the clock on
      * that turn and the rule behind it, and what they want to hear about the match.
      */
    private def playingStatus(summary: MatchSummary): Modifier[HtmlElement] =
        div(
          // `isPending` means it is this player's turn: it is the flag the "Your Turn" list selects on.
          if (summary.isPending) div(cls := "pending", "your turn")
          // Matchmaker is never told whose turn it is in a live match -- the game keeps that -- so
          // "waiting for the other players" would be a guess, and usually a wrong one.
          else if (summary.isLive) div(cls := "detail", "being played live: open the game to see whose turn it is")
          // Named, rather than "the other players": in a match of three it is the difference
          // between knowing who to chase and knowing only that it is not you.
          else waitingFor(summary),
          // The clock on the turn now being taken, whoever is taking it -- the more useful thing to
          // know about a match you cannot move in.
          summary.turnDue.map(countdown).getOrElse(emptyNode),
          // Under a chess clock the deadline above is only half the story: it says when this turn
          // runs out, not how much either player has to last the rest of the match on.
          if (summary.clocks.isEmpty) emptyNode else clockTable(summary.clocks),
          // The rule behind that deadline, which the deadline itself does not give away: the same
          // "due" line comes of a per-turn limit and of a budget nearly spent, and they call for
          // opposite decisions.
          timeLimitDetail(summary.timeLimit, summary.timeLimitKind, summary.timeLimitUnit, summary.isLive),
          matchNotifications(summary)
        )

    /** The caller's way into a match still being played, and the way out of it for its owner. */
    private def playControls(summary: MatchSummary): HtmlElement =
        div(
          cls := "controls",
          // The play url lives on the match rather than the summary, and is the game engine's,
          // not matchmaker's — so it is fetched when asked for and opened directly.
          busyButton("Play", classes = Some("link")) { busy =>
              Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                  m.playUrl match {
                      case Some(url) => openSignedIn(url)
                      case None      => Store.reportProblem("This match has no play url yet.")
                  }
              }
          },
          // Step 4 of the engine flow: any participant may ask matchmaker to re-check with the
          // engine, which is what recovers from a callback that never arrived.
          busyButton("Refresh", classes = Some("link")) { busy =>
              Store.run(ApiClient.refreshMatch(summary.gameId, summary.matchId), busy)(reloadAfterMatchRefresh)
          },
          // Only the owner's. The engine is not told — its board stays playable — so the
          // confirmation says what actually happens.
          if (summary.isOwner)
              busyButton("Cancel", classes = Some("link")) { busy =>
                  if (
                    dom.window.confirm("Cancel this match? It will stop counting here, but the game board stays open.")
                  )
                      Store.run(ApiClient.cancelMatch(summary.gameId, summary.matchId), busy)(_ =>
                          Store.refreshMatches()
                      )
              }
          else emptyNode
        )

    /** What this player wants to hear about this one match, on the match's own row.
      *
      * The whole answer, not an override: the seat was stamped with the player's settings as they stood when the match
      * started, and what is saved here is what that match sends from now on regardless of what they change elsewhere —
      * unless they later ask for a change to be carried into the matches they are in, which the account panel offers.
      *
      * On the row rather than in the account panel because it is about this match: the player who wants quiet wants it
      * from the match that has got noisy, and finding it under Account would mean naming the match in a list of forty.
      *
      * Fetched when the form is first opened. Every row would otherwise cost a request on every reload of the list, for
      * a form almost nobody opens — and the preference cannot be shown from what the lists already hold, since a
      * `MatchSummary` does not carry it.
      *
      * The answers live in a `Var` outside the toggle, so closing and reopening the form shows what was being typed
      * rather than re-fetching over the top of it. Only the "Saved." line is lost, which is a message about something
      * that has already happened.
      */
    private def matchNotifications(summary: MatchSummary): HtmlElement = {
        val shown = Var(false)
        val fetched = Var(false)
        val preferences = Var(NotificationPreferences.unset)
        val busy = Var(false)

        div(
          button(
            tpe := "button",
            cls := "link",
            // The button is the form's control, so it says whether the form is open — both in the
            // label, which is what a sighted user reads, and in the state, which is what is announced.
            aria.expanded <-- shown.signal,
            // Not disabled while the form is fetched, which would drop the keyboard's focus.
            aria.disabled <-- busy.signal,
            child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
            child.text <-- shown.signal.map(if (_) "Hide notifications" else "Notifications"),
            onClick --> { _ =>
                if (busy.now()) ()
                else if (shown.now()) shown.set(false)
                else if (fetched.now()) shown.set(true)
                else
                    Store.run(ApiClient.matchNotifications(summary.gameId, summary.matchId), busy) { current =>
                        preferences.set(current.asPreferences)
                        fetched.set(true)
                        shown.set(true)
                    }
            }
          ),
          child <-- shown.signal.map {
              case false => emptyNode
              case true  =>
                  // `withDefault = false`: this match's seat answers every kind itself and there is nothing under it to defer to. The
                  // answers it opens with are what the seat was stamped with when the match started,
                  // so nothing is unanswered and the button is never disabled for want of a choice.
                  //
                  // Only `duringMatch` is asked about: the seat also holds an answer for
                  // `MatchStarted`, but that match has already started, so it is a choice that could
                  // never apply again. What is saved for it is what was fetched, unchanged.
                  Notifications.form(
                    "Notification Preferences for this match",
                    "What we email you about this match, whatever you change elsewhere later.",
                    preferences,
                    withDefault = false,
                    saveLabel = "Save for this match",
                    kinds = NotificationType.duringMatch
                  ) { chosen =>
                      chosen.completeForSeat match {
                          case Some(answers) =>
                              ApiClient.updateMatchNotifications(summary.gameId, summary.matchId, answers)
                          // Unreachable: the form seeds every kind a seat holds and its button is
                          // disabled while any is unanswered. A failed Future rather than a silent
                          // success, so that a hole in that reasoning shows up beside the form instead
                          // of looking saved.
                          case None =>
                              scala.concurrent.Future.failed(
                                new RuntimeException("Answer every question before saving.")
                              )
                      }
                  }
          }
        )
    }

    /** How a finished match ended, in its game engine's words: a line of HTML sent with the results.
      *
      * Cleaned again here although the server stored it cleaned, so that what reaches `innerHTML` is never only as safe
      * as the server's copy — see `SummaryHtml`. A `div` rather than a `p`, since a summary may hold a list.
      */
    private def resultSummary(html: String): HtmlElement = {
        val element = dom.document.createElement("div").asInstanceOf[dom.html.Div]
        element.className = "result-summary"
        element.innerHTML = SummaryHtml.clean(html)
        foreignHtmlElement(element)
    }

    /** How a finished match ended: every seat, the winner first.
      *
      * The rows are already ordered by rank by the query, so the order of the list is the standing and the rank itself
      * does not need saying — but winning is marked from `isWinner` rather than from position, because a game may have
      * no winner at all (a draw, a cancelled match) and first place would otherwise invent one.
      *
      * A cancelled match has no engine-reported result, so its rows usually have no rank/scores (rather than being
      * absent). Saying so beats rendering an empty-looking table.
      */
    private def resultTable(summary: MatchSummary): HtmlElement =
        div(
          cls := "results",
          listing(
            Store.resultsByMatch.signal.map(_.getOrElse(summary.matchId, Seq.empty)),
            Store.loading(Store.Fetch.Results)
          )(
            p(
              cls := "empty",
              if (summary.isCancelled) "Called off before it finished." else "No result was reported."
            )
          )(rows =>
              div(
                // Said once above the table rather than on each line: a forfeit is how the match
                // ended, which is one fact about the match, not a separate fact about each seat.
                // The lines below still say which of the two things it meant for each player.
                if (rows.exists(_.isForfeit))
                    p(cls := "detail", "Ended by forfeit: a player ran out of time on their turn.")
                else emptyNode,
                ul(
                  cls := "result-rows",
                  rows.map { row =>
                      li(
                        cls := "result-row",
                        // The emoji reads out as "trophy", which is a guess at what it means rather than
                        // a statement of it. The text says it; the emoji is decoration over the top.
                        if (row.isWinner) span(cls := "winner", aria.hidden := true, "🏆 ") else emptyNode,
                        if (row.isWinner) span(cls := "sr-only", "winner: ") else emptyNode,
                        span(cls := "who", s"${row.nickname} (${row.roleName})"),
                        // Their Elo rating as the match began, in a friendly match as in any other, and
                        // what the match did to it if it was rated.
                        span(cls := "detail", s" — ${Format.elo(row.eloStart, row.eloDelta)}"),
                        // Which side of the forfeit this player was on. `isWinner` is what separates
                        // them, and without this a win by forfeit would read as a win on the board.
                        if (!row.isForfeit) emptyNode
                        else if (row.isWinner) span(cls := "detail", " — won by forfeit")
                        else span(cls := "detail", " — forfeited on time"),
                        // How long they spent over their turns, all told. Only when the match has
                        // turns recorded against somebody: a match played before turns were recorded
                        // would otherwise report a table of zeroes as if everybody had moved instantly.
                        if (rows.forall(_.timeTaken.isZero)) emptyNode
                        else span(cls := "detail", s" — ${Format.spent(row.timeTaken)} on the clock"),
                        // Whatever else the engine chose to report. Which keys exist is the game's
                        // business, so they are shown as they came rather than being named here.
                        if (row.scores.isEmpty) emptyNode
                        else
                            span(
                              cls := "scores",
                              " — ",
                              row.scores.toSeq
                                  .sortBy(_._1)
                                  .map((key, value) => s"$key: ${Format.jsonValue(value)}")
                                  .mkString(", ")
                            )
                      )
                  }
                )
              )
          )
        )

    // -------------------------------------------------------------------------
    // Games and challenges
    // -------------------------------------------------------------------------

    /** One game's screen: its open challenges, a way to offer one, and this player's history in it. An admin also gets
      * the edit form.
      *
      * Taken from the games list rather than fetched, so a game id the list does not know about — an inactive game, or
      * a menu that has outlived a reload — says so instead of showing an empty screen that looks like a game with
      * nothing in it.
      */
    private def gamePage(gameId: GameId): HtmlElement = {
        // Through `Store.game`, which answers from the active games and from the deactivated one this
        // screen may have been linked to -- an invitation outlives its game being deactivated, and
        // reading the active list alone left such a page saying "Loading…" for ever. The two states are
        // still told apart: "Loading…" while something is on its way, and a sentence saying so once
        // nothing is.
        val state = Store
            .game(gameId)
            .combineWith(Store.loading(Store.Fetch.Games).combineWith(Store.lookingForGame(gameId)).map(_ || _))

        val message = state.map {
            case (None, true)  => "Loading…"
            case (None, false) => "That game is not available. It may have been withdrawn."
            case (Some(_), _)  => ""
        }

        div(
          /* What the lookup came to, in a region that is mounted before it is asked.
           *
           * The sentence used to arrive as part of a freshly built element, which is the one way of
           * putting text in a live region that is not reliably announced: a reader watches regions it
           * already knows about for changes, and a region that did not exist a moment ago has no
           * change to report. So the region stands here for the life of the screen and only its words
           * come and go -- the same shape the invitations section uses, and for the same reason.
           *
           * A `div` rather than a `p` because it is empty whenever there is a game to draw, and an
           * empty `p` would leave its margins behind above the heading. The class comes and goes with
           * the text so the styling applies to a sentence and to nothing.
           */
          div(
            aria.live := "polite",
            cls <-- message.map(said => if (said.isEmpty) "" else "empty"),
            child.text <-- message
          ),
          child <-- state
              .map {
                  case (None, _) => emptyNode
                  case (Some(game), _) =>
                      div(
                        h2(game.displayName),
                        p(cls := "detail", game.description),
                        editGamePanel(game),
                        // What is waiting on this player in this game, before what they could join: a turn
                        // they owe somebody is more urgent than a challenge they might accept.
                        //
                        // Both halves of the pending acceptances, in the order the home page puts them: the
                        // ones this player can start now, and the ones that are still waiting on somebody.
                        // Splitting them across two screens would leave a game page listing an acceptance as
                        // waiting to start with no way to start it.
                        readyToStartSection(Some(game)),
                        dueSection(Some(game)),
                        myMatchesSection(Some(game)),
                        waitingSection(Some(game)),
                        gameChallenges(game),
                        Tournaments.gameSection(game),
                        ratingsSection(game),
                        gameHistory(game)
                      )
              },
          // The edit dialog, outside the screen drawn above it: that is rebuilt whenever the game is emitted again --
          // a refresh, or the answer to a save made from an earlier opening -- and a dialog inside it would be rebuilt
          // with it, losing whatever was being typed. Built once per opening, from the game as it was when opened.
          child <-- Store.editingGame.signal
              .combineWith(Store.game(gameId))
              .map {
                  case (Some(editing), Some(game)) if editing == gameId => Some(game)
                  case _                                                => None
              }
              .distinctBy(_.map(_.gameId))
              .map(_.fold(emptyNode)(editGameDialog))
        )
    }

    /** The link to the admin's edit form for a game, which opens as a dialog, as the challenge form does. Nothing for
      * anyone else: the server answers a non-admin with a 403, so the link is not there to press.
      */
    private def editGamePanel(game: Game): HtmlElement =
        div(
          child <-- currentPlayer.combineWith(Store.editingGame.signal).map {
              case (Some(player), editing) if player.isAdmin =>
                  div(
                    button(
                      cls := "link",
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded := editing.contains(game.gameId),
                      "Edit game",
                      onMountCallback(context => editGameTrigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => editGameTrigger = None),
                      onClick --> (_ => Store.editingGame.set(Some(game.gameId)))
                    )
                    // The dialog it opens is drawn by `gamePage`, which outlasts this.
                  )
              case _ => emptyNode
          }
        )

    /* The link the edit dialog is opened from, so closing it can put focus back -- `challengeTrigger`'s
     * counterpart. */
    private var editGameTrigger: Option[dom.html.Element] = None

    /* Which opening of the edit dialog is the current one. A save can outlive the dialog it was made from -- it can be
     * closed while the save is waiting, and opened again -- and the save's answer closing the dialog is only right for
     * the opening that made it. Moved on by every close, so an answer from before one is never current. */
    private var editGameOpening = 0

    private def closeEditGame(): Unit = {
        editGameOpening += 1
        Store.editingGame.set(None)
        editGameTrigger.foreach(_.focus())
    }

    /* The edit form as a modal dialog, the way `challengeDialog` shows the challenge form: closed by Escape, by a
     * click on the backdrop, or by its own Close button, and closed by a save that succeeds. */
    private def editGameDialog(game: Game): HtmlElement = {
        val opening = editGameOpening
        gameDialog(
          Some(game),
          s"edit-game-${game.gameId.value}-heading",
          s"Edit ${game.displayName}",
          closeEditGame,
          onSaved = _ => if (editGameOpening == opening) closeEditGame()
        )
    }

    /* The game form as a modal dialog, for adding a game or editing one. */
    private def gameDialog(
        existing: Option[Game],
        headingId: String,
        title: String,
        close: () => Unit,
        onSaved: Game => Unit = _ => ()
    ): HtmlElement =
        div(
          cls := "modal-scrim",
          Modal.inertBehind,
          onClick --> (event => if (event.target == event.currentTarget) close()),
          gameForm(existing, heading = Some(headingId -> title), onSaved = onSaved).amend(
            cls := "modal",
            role := "dialog",
            htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
            aria.labelledBy := headingId,
            tabIndex := -1,
            inContext(node => onMountCallback(_ => node.ref.focus())),
            onKeyDown.filter(_.key == "Escape") --> { event =>
                event.stopPropagation()
                close()
            },
            errorBanner,
            div(
              cls := "alternatives",
              button(tpe := "button", cls := "link", "Close", onClick --> (_ => close()))
            )
          )
        )

    /** This player's finished matches in one game, most recently finished first.
      *
      * The same list the main page shows the top of, filtered rather than fetched again: it is already in the order
      * this asks for, and a second request would only be a second chance for the two screens to disagree.
      */
    private def gameHistory(game: Game): HtmlElement =
        refreshableSection(
          "Your Completed Matches",
          refreshingCompleted,
          () => Store.reloadCompleted(),
          subsection = false
        )(
          completedWindow(Store.CompletedList.Mine(Some(game.gameId)), "You have not finished a match of this yet.")(
            matchTable(_, showGame = false, showParameters = true, outcome = playerOutcome, view = reviewControl)
          )
        )

    /** The admin's add-a-game screen, reached from the menu. The same form the edit link opens, with nothing to start
      * from.
      */
    private def newGamePage: HtmlElement = {
        // Open on arriving, since adding a game is what the menu item was pressed for; closed, the page is left with
        // the button to open it again and the disabled games. Page-local, so leaving the page is closing it -- a
        // create does, for the new game's screen.
        val adding = Var(true)
        var trigger: Option[dom.html.Element] = None
        // Which opening of the dialog is current, as `editGameOpening` is for the edit dialog: moved on by a close and
        // by leaving the page, so that a create answered after either does not pull the admin to the new game's
        // screen from wherever they have gone since. The game is recorded all the same.
        var opening = 0
        def close(): Unit = {
            opening += 1
            adding.set(false)
            trigger.foreach(_.focus())
        }
        div(
          onUnmountCallback(_ => opening += 1),
          h2("Add a Game"),
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin =>
                  div(
                    button(
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded <-- adding.signal,
                      "New Game",
                      onMountCallback(context => trigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => trigger = None),
                      onClick --> (_ => adding.set(true))
                    ),
                    child <-- adding.signal.map { open =>
                        if (open) {
                            val mine = opening
                            // Straight to the game that was just created: it is now in the menu, and its own
                            // screen is where anything else is done with it.
                            gameDialog(
                              None,
                              "add-game-heading",
                              "Add a Game",
                              close,
                              onSaved = saved => if (opening == mine) Store.show(Store.Page.OneGame(saved.gameId))
                            )
                        } else emptyNode
                    },
                    disabledGames
                  )
              case _ => p(cls := "empty", "Only an administrator can add a game.")
          }
        )
    }

    /** The games an admin has disabled, each a link to its own screen, where its edit form can enable it again.
      *
      * Here because nowhere else lists them: a disabled game is out of the menu and every picker, which is the point of
      * disabling it, and without this the only way back to one would be a link somebody kept. Fetched each time the
      * page opens, into this page alone — it is the one screen that wants the disabled games as a list.
      */
    private def disabledGames: HtmlElement = {
        val found = Var(Option.empty[Either[String, Seq[Game]]])
        div(
          cls := "detail-panel",
          h3("Disabled Games"),
          onMountCallback { _ =>
              ApiClient.games(activeOnly = false).onComplete {
                  case scala.util.Success(all) => found.set(Some(Right(all.filterNot(_.isActive))))
                  case scala.util.Failure(e)   => found.set(Some(Left(s"Could not load them: ${e.getMessage}")))
              }
          },
          div(
            // Announced when it arrives, rather than left for a reader to find has changed.
            aria.live := "polite",
            child <-- found.signal.map {
                case None               => p(cls := "detail", "Loading…")
                case Some(Left(why))    => p(cls := "error", why)
                case Some(Right(Seq())) => p(cls := "empty", "None.")
                case Some(Right(games)) =>
                    ul(
                      games.map(game =>
                          li(
                            button(
                              cls := "link",
                              game.displayName,
                              onClick --> (_ => Store.show(Store.Page.OneGame(game.gameId)))
                            )
                          )
                      )
                    )
            }
          )
        )
    }

    /** "An admin user should be able to create a new game."
      *
      * The form asks for everything a game is: its own fields, the roles a player can be seated in, and the parameters
      * the game engine is configured with. Roles are not optional extras — every seat of a match names one, so a game
      * with none is a game nothing can be played in, and the server refuses it.
      */
    /* One row of the role or parameter editor, held as Vars rather than plain values so that
     * typing in a row does not rebuild the list and take the cursor with it: the rendered children
     * change only when a row is added or removed. */
    /* A role draft carries the id of the role it edits, because that is what tells an edit from an
     * addition on the way back — and an existing role can never be removed, only renamed or made
     * optional. Parameters carry no id: they are replaced wholesale, and deleting one is allowed. */
    private case class RoleDraft(
        gameRoleId: GameRoleId,
        name: Var[String],
        displayName: Var[String],
        roleType: Var[RoleType],
        // As typed: blank is no preference.
        preferenceLevel: Var[String]
    )
    private case class ParameterDraft(
        name: Var[String],
        displayName: Var[String],
        values: Var[String],
        default: Var[String]
    )

    private def emptyRole: RoleDraft =
        RoleDraft(GameRoleId.unassigned, Var(""), Var(""), Var(RoleType.Required), Var(""))
    private def emptyParameter: ParameterDraft = ParameterDraft(Var(""), Var(""), Var(""), Var(""))

    private def draftOf(role: GameRole): RoleDraft =
        RoleDraft(
          role.gameRoleId,
          Var(role.name),
          Var(role.displayName),
          Var(role.roleType),
          Var(role.preferenceLevel.fold("")(_.toString))
        )

    private def draftOf(parameter: GameParameter[String]): ParameterDraft =
        ParameterDraft(
          Var(parameter.name),
          Var(parameter.displayName),
          Var(parameter.values.map(_.value).mkString(", ")),
          Var(parameter.defaultValue.getOrElse(""))
        )

    /** The possible values of a parameter, as typed: one comma-separated list, because a parameter with three values is
      * a sentence an admin can type and a list of three inputs is not.
      */
    private def splitValues(raw: String): Seq[String] =
        raw.split(',').map(_.trim).filter(_.nonEmpty).toSeq.distinct

    /** `formKey` tells this form's tips apart from another form's on the same page, since every tip is found by id. */
    private def roleEditor(formKey: String, roles: Var[List[RoleDraft]]): HtmlElement =
        div(
          withTip(
            s"$formKey-roles-tip",
            "Roles",
            "Every seat in a match names a role, so a game needs at least one. A match cannot start until " +
                "every required role is filled, or, for a challenge that starts itself, every required and " +
                "recommended role. Optional roles can always be left empty, and a recommended one counts " +
                "toward a tournament's pool size. A role that already exists can " +
                "be renamed but not removed — acceptances and played matches name it, so retire one by " +
                "making it optional."
          )(h4("Roles")),
          children <-- roles.signal.map(_.zipWithIndex.map { (draft, i) =>
              val nameTip = s"$formKey-role-$i-name-tip"
              val displayTip = s"$formKey-role-$i-display-tip"
              val preferenceTip = s"$formKey-role-$i-preference-tip"
              div(
                cls := "row",
                // A caption on each field, with what it means in a tip beside it rather than under it:
                // the explanation is the same on every row, and repeated in full it would bury the rows.
                withTip(
                  nameTip,
                  "Role name",
                  "What the game engine is sent for this seat, so it has to be the name the engine expects."
                )(
                  label(
                    cls := "field",
                    "Name",
                    input(
                      aria.describedBy := nameTip,
                      controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)
                    )
                  )
                ),
                withTip(
                  displayTip,
                  "Role display name",
                  "What players see for this seat. Left blank, it is the name."
                )(
                  label(
                    cls := "field",
                    "Display name",
                    input(
                      aria.describedBy := displayTip,
                      controlled(value <-- draft.displayName.signal, onInput.mapToValue --> draft.displayName)
                    )
                  )
                ),
                label(
                  cls := "field",
                  "Type",
                  select(
                    onChange.mapToValue --> (code => draft.roleType.set(RoleType.fromCode(code))),
                    value <-- draft.roleType.signal.map(_.code),
                    RoleType.values.toSeq.map(rt => option(value := rt.code, rt.label))
                  )
                ),
                withTip(
                  preferenceTip,
                  "Role preference level",
                  "In a tournament where roles are chosen, the role with the highest level goes to the " +
                      "highest seed. Leave blank for no preference."
                )(
                  label(
                    cls := "field",
                    "Preference",
                    input(
                      tpe := "number",
                      inputMode := "numeric",
                      aria.describedBy := preferenceTip,
                      controlled(
                        value <-- draft.preferenceLevel.signal,
                        onInput.mapToValue --> draft.preferenceLevel
                      )
                    )
                  )
                ),
                // A role that exists cannot be removed: acceptances and played matches name it. The
                // server refuses it too — this is why the button is not there to press.
                if (draft.gameRoleId == GameRoleId.unassigned)
                    // Named for what it removes: a column of identical "Remove" links tells a screen
                    // reader nothing about which row it is on.
                    button(
                      cls := "link",
                      aria.label <-- draft.name.signal.map(n =>
                          if (n.trim.isEmpty) "Remove this role" else s"Remove role $n"
                      ),
                      "Remove",
                      onClick --> (_ => roles.update(_.filterNot(_ eq draft)))
                    )
                else emptyNode
              )
          }),
          button(cls := "link", "Add a Role", onClick --> (_ => roles.update(_ :+ emptyRole)))
        )

    private def parameterEditor(formKey: String, parameters: Var[List[ParameterDraft]]): HtmlElement =
        div(
          withTip(
            s"$formKey-parameters-tip",
            "Parameters",
            "How the game engine is configured when a match is created. The name is what the engine is " +
                "sent; the display name is what players see, and is the name if left blank. A parameter's " +
                "default has to be one of its values, and a game may have none at all."
          )(h4("Parameters")),
          children <-- parameters.signal.map(_.map { draft =>
              // A caption on each box, as on a role's: a placeholder is gone at the first keystroke, and with it
              // any way of telling which of four alike boxes is which.
              div(
                cls := "row",
                field("Name", input(controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name))),
                field(
                  "Display name (left blank, the name)",
                  input(controlled(value <-- draft.displayName.signal, onInput.mapToValue --> draft.displayName))
                ),
                field(
                  "Values, comma separated",
                  input(controlled(value <-- draft.values.signal, onInput.mapToValue --> draft.values))
                ),
                field(
                  "Default value",
                  input(controlled(value <-- draft.default.signal, onInput.mapToValue --> draft.default))
                ),
                button(
                  cls := "link",
                  aria.label <-- draft.name.signal.map(n =>
                      if (n.trim.isEmpty) "Remove this parameter" else s"Remove parameter $n"
                  ),
                  "Remove",
                  onClick --> (_ => parameters.update(_.filterNot(_ eq draft)))
                )
              )
          }),
          button(cls := "link", "Add a Parameter", onClick --> (_ => parameters.update(_ :+ emptyParameter)))
        )

    /** The drafted roles as the model, or the first thing wrong with them.
      *
      * The same rules the server checks, checked here so that a typo is answered by the form rather than by a round
      * trip — the server is still the one that decides, since nothing stops a request being made without this form.
      */
    private def rolesOf(drafts: List[RoleDraft]): Either[String, Seq[GameRole]] = {
        val all = drafts.map { d =>
            val name = d.name.now().trim
            // Left blank, a role is shown by its name -- the server does the same.
            val displayName = Option(d.displayName.now().trim).filter(_.nonEmpty).getOrElse(name)
            val preference = d.preferenceLevel.now().trim
            (d.gameRoleId, name, d.roleType.now(), displayName, preference)
        }
        // A blank new row is one the admin added and did not fill in, and is dropped. A blank
        // existing row is a role whose name has been cleared -- dropping that would ask the server to
        // delete a role, which it refuses, so it is answered here as what it is.
        val (blank, named) = all.partition(_._2.isEmpty)
        if (blank.exists(_._1 != GameRoleId.unassigned))
            Left("A role that already exists cannot be left without a name.")
        else if (named.isEmpty) Left("A game needs at least one role: every player accepting a challenge takes one.")
        else if (named.map(_._2).distinct.sizeIs != named.size) Left("Two roles cannot have the same name.")
        else if (named.map(_._4).distinct.sizeIs != named.size) Left("Two roles cannot be shown under the same name.")
        else if (named.exists((_, _, _, _, p) => p.nonEmpty && p.toIntOption.isEmpty))
            Left("A role's preference level has to be a whole number, or blank.")
        else
            Right(
              named.map((id, name, roleType, displayName, preference) =>
                  GameRole(id, GameId.unassigned, name, roleType, displayName, preference.toIntOption)
              )
            )
    }

    /** A game's maximum turns as typed: blank for none, otherwise a whole number above 0. */
    private def maxTurnsOf(raw: String): Either[String, Option[Int]] =
        raw.trim match {
            case "" => Right(None)
            case trimmed =>
                trimmed.toIntOption
                    .filter(_ > 0)
                    .map(Some(_))
                    .toRight("Maximum turns has to be a whole number above 0, or blank.")
        }

    private def parametersOf(drafts: List[ParameterDraft]): Either[String, Seq[GameParameter[String]]] = {
        val named = drafts
            .map { d =>
                val name = d.name.now().trim
                GameParameter[String](
                  GameId.unassigned,
                  GameParameterId(0),
                  name,
                  Option(d.default.now().trim).filter(_.nonEmpty),
                  splitValues(d.values.now()).map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v)),
                  // Left blank, a parameter is shown by its name -- the server does the same.
                  Option(d.displayName.now().trim).filter(_.nonEmpty).getOrElse(name)
                )
            }
            .filter(_.name.nonEmpty)
        val badDefault = named.find(p => p.defaultValue.exists(d => !p.values.exists(_.value == d)))
        if (named.map(_.name).distinct.sizeIs != named.size) Left("Two parameters cannot have the same name.")
        else if (named.map(_.displayName).distinct.sizeIs != named.size)
            Left("Two parameters cannot be shown under the same name.")
        else
            badDefault match {
                case Some(p) =>
                    Left(s"Parameter '${p.name}' has default '${p.defaultValue.get}', which is not one of its values.")
                case None => Right(named)
            }
    }

    /** The admin's game form, for creating one (`existing` is None) or editing one.
      *
      * The two are the same form because a game is the same thing either way, and every field is editable in both:
      * name, description, url, whether characters are required, the roles and the parameters. There is no player count
      * to set — a game's roles are its seats, so adding one in [[roleEditor]] is how a game gets bigger. What edit
      * cannot do is delete a role.
      *
      * `externalId` is asked for as the engine's identity: the name a callback carrying this game's API key is taken to
      * come from, and what game-authorized requests are matched against. The API key itself is asked for too — the
      * secret the engine was deployed with — and is write-only: required to create a game, left as it is by an edit
      * that leaves the field blank, and never shown, since nothing sends it back. `isActive` is asked only of an edit,
      * as "Disable Game": a new game is created active, and an edit may disable it, which takes it out of the menu and
      * every game picker. It hides the game rather than closing it — its challenges, invitations and matches are left
      * as they are, since `isActive` decides what is listed, not what may be played.
      */
    private def gameForm(
        existing: Option[Game],
        heading: Option[(String, String)] = None,
        onSaved: Game => Unit = _ => ()
    ): HtmlElement = {
        val name = Var(existing.map(_.name).getOrElse(""))
        val displayName = Var(existing.map(_.displayName).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val url = Var(existing.map(_.url).getOrElse(""))
        val engineIdentity = Var(existing.map(_.externalId).getOrElse(""))
        val disabled = Var(existing.exists(!_.isActive))
        // No role has an advantage in winning: ratings are then kept overall only.
        val unimportantRoles = Var(existing.exists(_.hasUnimportantRoles))
        // How players take turns, and how many a simultaneous game lasts; max turns as typed.
        val moveType: Var[MoveType] = Var(existing.map(_.moveType).getOrElse(MoveType.Sequential))
        val maxTurns = Var(existing.flatMap(_.maxTurns).fold("")(_.toString))
        // What this form's tip ids start with: a game's edit form and the new-game form are different forms.
        val formKey = existing.fold("new-game")(game => s"game-${game.gameId.value}")
        // Write-only: starts empty whether or not a key is stored, because the stored one is never sent here.
        val apiKey = Var("")
        // Where a player makes a character: the engine's page, since characters are made there.
        val characterUrl = Var(existing.flatMap(_.characterUrl).getOrElse(""))
        // Plain by default: requiring characters is the additional commitment, so it is the box an
        // admin ticks rather than the one they have to remember to untick.
        val gameType: Var[GameType] = Var(existing.map(_.gameType).getOrElse(GameType.Plain))
        // What happens when a player takes too long over a turn. A dropdown rather than a checkbox
        // because Forfeit is the first of several planned actions, not the only one there will ever
        // be — the control does not have to change when the second arrives, only the enum.
        val timeoutAction: Var[TimeoutAction] =
            Var(existing.map(_.timeoutAction).getOrElse(TimeoutAction.Forfeit))
        // A new game starts with one empty role, because it cannot be created without one, and no
        // parameters, because plenty of games have none. An existing one starts with what it has.
        val roles = Var(existing.map(_.roles.map(draftOf).toList).getOrElse(List(emptyRole)))
        val parameters = Var(
          existing.map(_.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList).getOrElse(Nil)
        )

        div(
          cls := "card",
          // A dialog's title, given as (id, text) so the dialog can be labelled by it.
          heading.fold(emptyNode)((id, text) => h3(idAttr := id, text)),
          // First, because it decides what else the form asks: a character game has a page to make characters on.
          label(
            "Requires characters ",
            input(
              tpe := "checkbox",
              checked <-- gameType.signal.map(_ == GameType.Character),
              onClick --> (_ =>
                  gameType.update(gt => if (gt == GameType.Character) GameType.Plain else GameType.Character)
              )
            )
          ),
          // Only a character game has characters to make. Kept in `characterUrl` while hidden, so
          // unticking and reticking the box does not lose what was typed; a plain game saves none.
          child.maybe <-- gameType.signal.map(gt =>
              Option.when(gt == GameType.Character)(
                field(
                  "Character page url",
                  input(
                    tpe := "url",
                    controlled(value <-- characterUrl.signal, onInput.mapToValue --> characterUrl)
                  )
                )
              )
          ),
          field("Name", input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          tipField(
            s"$formKey-display-name-tip",
            "Display name",
            "What players see. Left blank, it is the name. Rename a game by changing this rather than the " +
                "name, which is what the game engine is told."
          )(input(controlled(value <-- displayName.signal, onInput.mapToValue --> displayName))),
          field("Description", input(controlled(value <-- description.signal, onInput.mapToValue --> description))),
          field("Game engine url", input(tpe := "url", controlled(value <-- url.signal, onInput.mapToValue --> url))),
          // The name means nothing on its own, and getting it wrong fails nowhere near this form.
          tipField(
            s"$formKey-engine-identity-tip",
            "Engine identity",
            "Who the engine is, such as \"boxing\". Moves, results and characters reported with this " +
                "game's API key are taken to come from it."
          )(
            input(
              autoComplete := "off",
              spellCheck := false,
              controlled(value <-- engineIdentity.signal, onInput.mapToValue --> engineIdentity)
            )
          ),
          // Whether a key is stored is the one thing about it this form can tell, since the key itself never comes
          // back.
          tipField(
            s"$formKey-api-key-tip",
            "API key",
            existing match {
                case Some(game) if game.hasApiKey =>
                    "A key is set. Leave this blank to keep it, or enter a new one to replace it."
                case Some(_) =>
                    "No key is set, so a deployed engine will refuse every call. Enter the key the engine " +
                        "was deployed with."
                case None =>
                    "The key the engine was deployed with, at least 24 characters. It is stored, never shown " +
                        "again, and can be replaced here later."
            }
          )(
            // A password field, so it is not shown while it is typed, and new-password so that no browser
            // offers to fill it with something saved for this site.
            input(
              tpe := "password",
              autoComplete := "new-password",
              spellCheck := false,
              controlled(value <-- apiKey.signal, onInput.mapToValue --> apiKey)
            )
          ),
          // Not asked while there is nothing to choose: Forfeit is the only action so far, and a dropdown of one
          // is a question with no answer to give. It comes back by itself when a second action is added.
          if (TimeoutAction.values.sizeIs > 1)
              field(
                "When a turn runs out",
                select(
                  onChange.mapToValue --> (code => timeoutAction.set(TimeoutAction.fromCode(code))),
                  value <-- timeoutAction.signal.map(_.code),
                  TimeoutAction.values.toSeq.map(action => option(value := action.code, action.label))
                )
              )
          else emptyNode,
          // Only an existing game can be disabled: a new one is created active, since a game created
          // hidden would look as though the button had done nothing.
          // In the warning colour: of everything on this form it is the one box that makes a game vanish.
          existing.fold(emptyNode) { _ =>
              val tipId = s"$formKey-disable-tip"
              withTip(
                tipId,
                "Disable Game",
                "A disabled game is taken out of the menu and every game list. Its challenges, invitations " +
                    "and matches are left as they are. It is listed on Add a Game, where it can be enabled again."
              )(
                label(
                  cls := "warn",
                  input(
                    tpe := "checkbox",
                    aria.describedBy := tipId,
                    controlled(checked <-- disabled.signal, onClick.mapToChecked --> disabled)
                  ),
                  "Disable Game"
                )
              )
          },
          roleEditor(formKey, roles),
          // Beside the roles it is about. Unticked, ratings are kept by role as well as overall.
          {
              val tipId = s"$formKey-unimportant-roles-tip"
              withTip(
                tipId,
                "Unimportant Roles",
                "Check this if no role has any advantage in winning the game."
              )(
                label(
                  input(
                    tpe := "checkbox",
                    aria.describedBy := tipId,
                    controlled(checked <-- unimportantRoles.signal, onClick.mapToChecked --> unimportantRoles)
                  ),
                  "Unimportant Roles"
                )
              )
          }, {
              val tipId = s"$formKey-move-type-tip"
              withTip(
                tipId,
                "Moves",
                "Whether players take turns one after another, or all move at once. A tournament gives each " +
                    "player a chess clock in the first case and a time limit per turn in the second."
              )(
                label(
                  cls := "field",
                  "Moves",
                  select(
                    aria.describedBy := tipId,
                    onChange.mapToValue --> (code => moveType.set(MoveType.fromCode(code))),
                    value <-- moveType.signal.map(_.code),
                    MoveType.values.toSeq.map(mt => option(value := mt.code, mt.label))
                  )
                )
              )
          }, {
              val tipId = s"$formKey-max-turns-tip"
              withTip(
                tipId,
                "Maximum turns",
                "How many turns a match lasts, if the game always ends after a fixed number. A tournament " +
                    "divides a round's time by it when everyone moves at once. Leave blank if it varies."
              )(
                label(
                  cls := "field",
                  "Maximum turns",
                  input(
                    tpe := "number",
                    inputMode := "numeric",
                    minAttr := "1",
                    aria.describedBy := tipId,
                    controlled(value <-- maxTurns.signal, onInput.mapToValue --> maxTurns)
                  )
                )
              )
          },
          parameterEditor(formKey, parameters),
          busyButton(
            if (existing.isDefined) "Save Changes" else "Create Game",
            // A new game needs its key; an edit may leave the stored one alone. A key that is typed has
            // to be long enough, as the server checks too.
            disabledWhen = name.signal
                .combineWith(engineIdentity.signal, apiKey.signal)
                .map((n, identity, key) =>
                    n.trim.isEmpty || identity.trim.isEmpty || (existing.isEmpty && key.trim.isEmpty) ||
                        (key.trim.nonEmpty && key.trim.length < 24)
                )
          ) { busy =>
              val drafted = for {
                  roleModels <- rolesOf(roles.now())
                  parameterModels <- parametersOf(parameters.now())
                  turns <- maxTurnsOf(maxTurns.now())
              } yield (roleModels, parameterModels, turns)

              drafted match {
                  case Left(problem) => Store.reportProblem(problem)
                  case Right((roleModels, parameterModels, turns)) =>
                      val game = Game(
                        // Unassigned means create and the server assigns the real id — the same sentinel
                        // the challenge form uses; a real id means update that game.
                        gameId = existing.map(_.gameId).getOrElse(GameId.unassigned),
                        gameType = gameType.now(),
                        name = name.now().trim,
                        // Left blank, the game is shown by its name -- the server does the same.
                        displayName = Option(displayName.now().trim).filter(_.nonEmpty).getOrElse(name.now().trim),
                        description = description.now().trim,
                        url = url.now().trim,
                        // A game nobody can see is not what "create a game" means, and `refreshGames` only
                        // asks for active ones — creating it inactive would look like the button did nothing.
                        // An edit says whether it is disabled.
                        isActive = existing.isEmpty || !disabled.now(),
                        roles = roleModels,
                        parameters = parameterModels,
                        // Who the engine is: the name its API key is filed under, which is what a callback
                        // from it is matched against.
                        externalId = engineIdentity.now().trim,
                        timeoutAction = timeoutAction.now(),
                        hasUnimportantRoles = unimportantRoles.now(),
                        moveType = moveType.now(),
                        maxTurns = turns,
                        characterUrl = Option
                            .when(gameType.now() == GameType.Character)(characterUrl.now().trim)
                            .filter(_.nonEmpty)
                      )

                      // A save can be answered after a sign-out, and by then the dialog open may be the next
                      // player's: an answer from an earlier session closes nothing, opens nothing, writes
                      // nothing into the store and leaves the banner alone. See `Store.runSignedIn`.
                      Store.runSignedIn(
                        ApiClient.createGame(game, Option(apiKey.now().trim).filter(_.nonEmpty)),
                        busy
                      ) { saved =>
                          // Cleared either way: it has been sent, and an edit saved again should not resend it.
                          apiKey.set("")
                          if (existing.isEmpty) {
                              name.set("")
                              displayName.set("")
                              description.set("")
                              url.set("")
                              engineIdentity.set("")
                              characterUrl.set("")
                              unimportantRoles.set(false)
                              moveType.set(MoveType.Sequential)
                              maxTurns.set("")
                              roles.set(List(emptyRole))
                              parameters.set(Nil)
                          } else {
                              // Re-drafted from what came back, so that roles added by this save carry the ids
                              // the insert gave them — without which saving twice would ask to add them again.
                              roles.set(saved.roles.map(draftOf).toList)
                              parameters.set(
                                saved.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList
                              )
                          }
                          // Both copies of the game list, because a game saved while deactivated is in
                          // neither the active one nor reachable by `ensureGame` — its own screen would
                          // otherwise go on showing what it was before this save, and reopening this
                          // form would submit that. See `Store.gameSaved`. Before `onSaved`, so that a
                          // dialog's next step -- the new game's screen -- finds the game already there.
                          Store.gameSaved(saved)
                          onSaved(saved)
                      }
              }
          }
        )
    }

    /** `player`'s matches in the game, for an admin of it looking at that player's page: where they say whether each is
      * friendly. Every match the player has a seat in, public or not — an admin manages them all — and nothing at all
      * for a reader who is not an admin of the game: not a heading, not a request beyond the one that finds out.
      *
      * On the player's page rather than the game's, so that an admin deals with one player's matches at a time, from
      * the page that is about that player.
      */
    private def adminMatchesSection(game: Game, player: PublicPlayer): HtmlElement =
        div(child <-- currentPlayer.map(_.fold(emptyNode)(reader => adminMatches(game, player, reader))))

    /* Not on the admin's own page: it is another player an admin is there to manage -- their rating in
     * the game, and whether each of their matches is friendly. */
    private def adminMatches(game: Game, player: PublicPlayer, reader: Player): HtmlElement =
        if (reader.playerId == player.playerId) div()
        else
            div(
              child <-- Store.administers(game.gameId, reader).map {
                  case true  => div(ratePlayer(game, player), adminMatchList(game, player))
                  case false => emptyNode
              }
            )

    /** The list itself, asked for when it is shown — which is only to an admin of the game. */
    private def adminMatchList(game: Game, player: PublicPlayer): HtmlElement = {
        // `None` until the list has come back.
        val matches = Var(Option.empty[Seq[GameMatch]])
        val refreshing = Var(false)
        // Mounts of this section, so that an answer to an earlier one is not written into a later one.
        var mount = 0

        /* Written only into this section, never the store: the page this sits in is rebuilt when the
         * store changes, and an answer that changed the store would rebuild it, remount this, and ask
         * again. Dropped if it belongs to another mount or another sign-in. */
        def fetch(asked: Int): Future[Unit] = {
            val signIn = Store.currentSignIn
            ApiClient.gameMatches(game.gameId, Some(player.playerId)).map { found =>
                if (asked == mount && Store.isStillSignedInAs(signIn)) matches.set(Some(found))
            }
        }

        div(
          onMountCallback { _ =>
              mount += 1
              fetch(mount)
          },
          onUnmountCallback(_ => mount += 1),
          child <-- matches.signal.map {
              case None => emptyNode
              case Some(found) =>
                  refreshableSection(
                    "Friendly or Rated",
                    refreshing,
                    () => fetch(mount),
                    subsection = true,
                    tip = Some(
                      s"friendly-admin-tip-${game.gameId.value}" ->
                          (s"Every match ${player.nickname} has played or is playing in this game, public or not. As " +
                              "an admin of this game, you say whether each is friendly — even once it is over, until " +
                              "one of its players has played another match of this game since.")
                    )
                  )(
                    if (found.isEmpty) p(cls := "empty", "None yet.")
                    else ul(found.map(adminMatchRow(game, _)))
                  )
          }
        )
    }

    /** One match in [[adminMatchList]], with the box that says whether it is friendly.
      *
      * The box's state is the row's own, set as it is clicked and put back if the server refuses: changing it does not
      * rebuild the list, which would take the keyboard's focus with it.
      */
    private def adminMatchRow(game: Game, m: GameMatch): HtmlElement = {
        val friendly = Var(m.isFriendly)
        val busy = Var(false)
        li(
          cls := "row",
          div(cls := "title", if (m.description.trim.nonEmpty) m.description else "an unnamed match"),
          div(cls := "detail", if (m.players.isEmpty) "nobody seated yet" else m.players.mkString(", ")),
          div(
            cls := "detail",
            m.completedAt.map(when => s"completed ${Format.date(when)}").getOrElse {
                if (m.isCancelled) "cancelled" else s"started ${Format.date(m.start)}"
            }
          ),
          // A completed match can change too, and is re-rated when it does: said beside the box, and read
          // out with it, since that is a bigger thing than the box looks. The server refuses a change that
          // would reach into a later match, and the banner says why.
          if (m.completedAt.isDefined)
              div(idAttr := s"friendly-note-${m.matchId.value}", cls := "detail", "Changing this re-rates the match.")
          else emptyNode,
          label(
            input(
              tpe := "checkbox",
              if (m.completedAt.isDefined) aria.describedBy := s"friendly-note-${m.matchId.value}" else emptyMod,
              // Not disabled while saving: a disabled box drops the keyboard's focus, so a click made
              // meanwhile is ignored instead, and the box keeps showing what is being saved.
              aria.busy <-- busy.signal,
              controlled(
                checked <-- friendly.signal,
                onClick.mapToChecked.filter(_ => !busy.now()) --> { on =>
                    friendly.set(on)
                    Store.run(ApiClient.setFriendly(game.gameId, m.matchId, on), busy, _ => friendly.set(!on))(saved =>
                        friendly.set(saved.isFriendly)
                    )
                }
              )
            ),
            "Friendly"
          )
        )
    }

    /** The game's leaderboard, [[Leaderboard.pageSize]] places at a time, best first, for anybody signed in; and for
      * its admins, a box on each row that sets that player's rating, and a form that rates a player who has none yet.
      *
      * Held in the section rather than the store, as [[adminMatchList]] holds its matches and for the same reason: an
      * answer written into the store would rebuild the page this sits in, remount it, and ask again.
      */
    private def ratingsSection(game: Game): HtmlElement =
        div(
          child <-- currentPlayer.map(
            _.fold(emptyNode)(player => div(ratingsList(game, player, None), roleRankings(game, player)))
          )
        )

    /** Under the overall rankings of a game whose roles matter, a dropdown that shows one role's rankings — asked for,
      * rather than shown, since most visitors want the overall ones. Not there for a game whose admin says no role has
      * an advantage, which keeps no ratings by role, nor for a game of one role, whose only role's rankings would be
      * the overall ones again.
      */
    private def roleRankings(game: Game, player: Player): Node =
        if (game.hasUnimportantRoles || game.roles.sizeIs < 2) emptyNode
        else {
            val chosen = Var(Option.empty[GameRole])
            val id = s"role-rankings-${game.gameId.value}"
            div(
              cls := "role-rankings",
              label(
                cls := "field",
                forId := id,
                "Rankings by role",
                select(
                  idAttr := id,
                  onChange.mapToValue --> (v => chosen.set(game.roles.find(_.gameRoleId.value.toString == v))),
                  option(value := "", "Choose a role"),
                  game.roles.map(r => option(value := r.gameRoleId.value.toString, r.displayName))
                )
              ),
              // A section of its own once a role is chosen, which announces itself as the overall one does.
              child <-- chosen.signal.distinct.map(_.fold(emptyNode)(role => ratingsList(game, player, Some(role))))
            )
        }

    /** The leaderboard — the game's overall one, or `role`'s. A role's has no rating to set: an admin sets the overall
      * rating, and a role's is moved only by play.
      */
    private def ratingsList(game: Game, player: Player, role: Option[GameRole]): HtmlElement = {
        val roleId = role.map(_.gameRoleId)
        // What tells this board's ids apart from the overall one's, on the same page.
        val idSuffix = role.fold("")(r => s"-role-${r.gameRoleId.value}")
        // `None` until a page has come back.
        val board = Var(Option.empty[Leaderboard])
        // The page on screen: moved only when the page asked for arrives, so that what the section says
        // about the rows, and where Previous, Next and a refresh go from, are about the rows it shows.
        val page = Var(0)
        val refreshing = Var(false)
        val busy = Var(false)
        // What the last save came to. Said aloud as well as shown: the box that was changed does not
        // say by itself that the change took.
        val said = Var("")
        // Why the last request for a page failed, if it did: so that the section says so rather than
        // "Loading…" for ever. Apart from `said`, and cleared by the next answer, so that a page that
        // loads on a retry is not shown beside the failure before it -- and a save's confirmation does
        // not overwrite why the page is missing.
        val loadError = Var(Option.empty[String])
        // Requests for a page, so that only the newest may write it. Moved on by an unmount too, so
        // that an answer to an earlier mount is not written into a later one, and by a row's save, so
        // that a page asked for before the save cannot answer after it with the rating it changed.
        var request = 0
        // The signed-in player's own standing, under the table: `None` until it has come back, and then
        // either it or what to say instead -- that they have no rating here, or that it could not be had.
        val mine = Var(Option.empty[Either[String, EloRating]])
        // Requests for it, apart from the page's: a save of somebody else's row says nothing about it, and
        // must not drop an answer still on its way. A save of the player's own row moves this on, as the
        // save is newer than anything asked before it.
        var mineRequest = 0
        // The last Find a Player answer: the game's rated players whose nickname starts with what was typed,
        // each shown as a line of its own at the foot of the table -- or why there is no answer.
        val found = Var(Option.empty[Either[String, Leaderboard]])

        /* Dropped if a newer request, mount or save has overtaken it, or it belongs to another sign-in --
         * its failure as well as its answer: the section is the next session's by then. Asks for the
         * player's own standing alongside the page, so that a refresh refreshes both. */
        def fetch(at: Int = page.now()): Future[Unit] = {
            request += 1
            val asked = request
            val signIn = Store.currentSignIn
            def current = asked == request && Store.isStillSignedInAs(signIn)
            mineRequest += 1
            val askedMine = mineRequest
            busy.set(true)
            ApiClient
                .standing(game.gameId, player.playerId, roleId)
                .map(Right(_))
                .recover {
                    case ApiError(404, _) =>
                        Left(
                          role.fold("You have no rating in this game yet.")(r =>
                              s"You have no rating as ${r.displayName} yet."
                          )
                        )
                    case error => Left(s"Your standing could not be loaded: ${error.getMessage}")
                }
                .foreach(answer =>
                    if (askedMine == mineRequest && Store.isStillSignedInAs(signIn)) mine.set(Some(answer))
                )
            ApiClient
                .leaderboard(game.gameId, at, roleId)
                .map { found =>
                    if (current) {
                        loadError.set(None)
                        // The page first: what is said about the rows is worked out as the rows arrive.
                        page.set(at)
                        board.set(Some(found))
                    }
                }
                .recover { case error =>
                    if (current) {
                        // "Refreshed" when an older page is still on screen: it is still there, and
                        // still what it was.
                        val what = if (board.now().isDefined) "refreshed" else "loaded"
                        loadError.set(Some(s"The leaderboard could not be $what: ${error.getMessage}"))
                    }
                }
                .andThen(_ => if (asked == request) busy.set(false))
        }

        /* A row's save is put in the row's place rather than re-sorted into the page: moving the row
         * the keyboard is in would take the focus with it, and its place is the listener's to work out
         * a moment later. The next refresh shows where it went. */
        def savedRow(saved: EloRating): Unit = {
            request += 1
            busy.set(false)
            said.set(s"${saved.player.nickname} is now rated ${saved.rating}.")
            // The rating as saved, and the place with everything it was worked out from as they were, so
            // that the row's place, ranked rating and matches still agree with each other and with the
            // page: the listener places the player again, and the next refresh shows where.
            def updated(r: EloRating) =
                if (r.player.playerId != saved.player.playerId) r
                else saved.copy(rank = r.rank, rankedRating = r.rankedRating, rankedMatches = r.rankedMatches)
            board.update(_.map(b => b.copy(ratings = b.ratings.map(updated))))
            // The player's own standing is the save's answer whole: it was read as the save committed, so
            // it is newer than any standing asked for before it, which is dropped.
            if (saved.player.playerId == player.playerId) {
                mineRequest += 1
                mine.set(Some(Right(saved)))
            }
        }

        val administers: Signal[Boolean] = if (role.isDefined) Val(false) else Store.administers(game.gameId, player)
        val rows = board.signal.map(_.fold(List.empty[EloRating])(_.ratings))

        // The line that says which places the page covers, for the focus to go to when a step it was on goes.
        var placesLine: Option[dom.html.Element] = None

        /* There only when there is somewhere to go: no Previous on the first page, no Next on the last.
         * Built once and mounted or not, so that it is the same element whenever it is there. A step that
         * goes while it has the focus -- Next, pressed onto the last page -- hands the focus to the line
         * saying which places are now shown, rather than letting it fall to the top of the page. While a
         * page is on its way it stays, marked busy, and a press is ignored. */
        def step(caption: String, label: String, side: String, possible: Signal[Boolean], to: Int => Int) = {
            val control = button(
              tpe := "button",
              cls := s"link $side",
              aria.label := label,
              aria.disabled <-- busy.signal,
              caption,
              onClick --> (_ => if (!busy.now()) fetch(to(page.now()))),
              onUnmountCallback(node => if (dom.document.activeElement == node.ref) placesLine.foreach(_.focus()))
            )
            child <-- possible.distinct.map(if (_) control else emptyNode)
        }

        div(
          onMountCallback(_ => fetch(0)),
          onUnmountCallback(_ => request += 1),
          refreshableSection(
            role.fold("Player Rankings")(r => s"${r.displayName} Rankings"),
            refreshing,
            () => fetch(),
            subsection = role.isDefined,
            tip = Some(
              s"ratings-tip-${game.gameId.value}$idSuffix" -> role.fold(
                s"Players are ranked by Elo rating, and by rated matches between equal ratings. " +
                    s"Every rated match of this game moves its players' ratings; a friendly one does not. " +
                    s"A player's first rated match starts them at ${EloRating.initial}."
              )(r =>
                  s"Players are ranked by their Elo rating as ${r.displayName}, which a rated match moves against " +
                      s"the other players' ratings in the roles they played. So a role that wins more than its " +
                      s"players' ratings say it should is rated higher than the others. A player's first rated " +
                      s"match as ${r.displayName} starts them at ${EloRating.initial}."
              )
            )
          )(
            // Two regions, mounted with the section so that what arrives in them is announced: how
            // loading the page went, and what the last save came to.
            div(aria.live := "polite", cls := "empty", child.text <-- loadError.signal.map(_.getOrElse(""))),
            div(aria.live := "polite", cls := "detail", child.text <-- said.signal),
            // Which places the page covers: said when Previous or Next brings a new one. A page is a range of
            // places rather than of players, so a long tie can leave one with nobody on it.
            p(
              cls := "detail",
              aria.live := "polite",
              // Focusable from script only: where the focus goes when the step it was on goes.
              tabIndex := -1,
              onMountCallback(context => placesLine = Some(context.thisNode.ref)),
              onUnmountCallback(_ => placesLine = None),
              child.text <-- board.signal.combineWith(loadError.signal).map {
                  // A failure is said in the region above; this only stops promising a page.
                  case (None, Some(_)) => ""
                  case (None, None)    => "Loading…"
                  case (Some(b), _) =>
                      val first = page.now() * Leaderboard.pageSize + 1
                      val last = first + Leaderboard.pageSize - 1
                      if (b.ratings.nonEmpty) s"Ranks $first to $last"
                      else if (page.now() == 0) "Nobody is ranked yet."
                      else s"Nobody is ranked $first to $last."
              }
            ),
            // Shown when the page has anybody on it, or there is a line of its own to show at its foot -- the
            // player's own standing, or one they searched for -- whoever is on the page.
            child <-- rows
                .map(_.nonEmpty)
                .combineWith(
                  mine.signal.map(_.exists(_.isRight)),
                  found.signal.map(_.exists(_.exists(_.ratings.nonEmpty)))
                )
                .map(_ || _ || _)
                .distinct
                .map {
                    case false => emptyNode
                    case true  =>
                        // Its own scroll, so that a phone scrolls the table sideways rather than the page.
                        div(
                          cls := "table-scroll",
                          table(
                            cls := "leaderboard",
                            caption(
                              cls := "sr-only",
                              s"Player rankings in ${game.name}" + role.fold("")(r => s" as ${r.displayName}")
                            ),
                            thead(
                              tr(
                                th(scopeAttr := "col", "Rank"),
                                th(scopeAttr := "col", "Player"),
                                th(scopeAttr := "col", "Ranked rating"),
                                th(scopeAttr := "col", "Matches"),
                                th(scopeAttr := "col", "Record"),
                                child <-- administers.map(if (_) th(scopeAttr := "col", "Set rating") else emptyNode)
                              )
                            ),
                            // Split by player, so that a row keeps its element -- and whatever is typed in its
                            // box -- when the page around it is answered again.
                            tbody(
                              children <-- rows.split(_.player.playerId)((_, first, rating) =>
                                  ratingRow(game, first.player, rating, administers, savedRow)
                              )
                            ),
                            // The player's own line, after the page and set apart from it: their place, with their
                            // rating and matches as they stand now rather than as they were placed by, which a match
                            // that has just finished may have moved.
                            tfoot(
                              child <-- mine.signal.combineWith(administers).map {
                                  case (Some(Right(you)), admin) => footRow(you, "mine", "you, now", admin)
                                  case _                         => emptyNode
                              },
                              children <-- found.signal.combineWith(administers).map {
                                  case (Some(Right(them)), admin) =>
                                      them.ratings.map(footRow(_, "found", "found, now", admin))
                                  case _ => Nil
                              }
                            )
                          )
                        )
                },
            // What there is to say instead of the player's own line: that it is on its way, that they have no
            // rating here, or that it could not be had. Mounted with the section, so that it is announced.
            div(
              aria.live := "polite",
              child <-- mine.signal.map {
                  case None            => p(cls := "empty", "Loading your standing…")
                  case Some(Left(why)) => p(cls := "empty", why)
                  case Some(Right(_))  => emptyNode
              }
            ),
            div(
              cls := "completed-steps",
              // Named for their board, since a role's sits on the page beside the overall one.
              step(
                "Previous",
                "Previous 20 ranks" + role.fold("")(r => s" as ${r.displayName}"),
                "backward",
                page.signal.map(_ > 0),
                _ - 1
              ),
              step(
                "Next",
                "Next 20 ranks" + role.fold("")(r => s" as ${r.displayName}"),
                "forward",
                board.signal.map(_.exists(_.hasMore)),
                _ + 1
              )
            ),
            findPlayer(game, found, role)
          )
        )
    }

    /** A line of its own at the foot of the leaderboard — the signed-in player's (`mine`), or one they searched for
      * (`found`): their place, and their rating and matches as they stand now. Told apart from the page by its colour,
      * and in words, `note`, which a screen reader hears where it cannot see the colour.
      */
    private def footRow(standing: EloRating, kind: String, note: String, administers: Boolean): HtmlElement =
        tr(
          cls := kind,
          td(standing.rank.fold[Modifier[HtmlElement]](notRanked)(_.toString)),
          th(scopeAttr := "row", playerLink(standing.player), span(cls := "foot-note", s" ($note)")),
          td(standing.rating.toString),
          td(standing.matches.toString),
          recordCell(standing.record),
          // Nothing to set here: an admin sets a rating from the player's row in the page, if it is on it.
          if (administers) td() else emptyNode
        )

    /** The game's rated players by the start of a nickname, as "Find Players" finds players: what is found goes into
      * `found`, which the leaderboard shows as lines at its foot. One row of a form, under the table, since it is a way
      * into the table rather than a section of its own.
      */
    private def findPlayer(
        game: Game,
        found: Var[Option[Either[String, Leaderboard]]],
        role: Option[GameRole]
    ): HtmlElement = {
        val prefix = Var("")
        val busy = Var(false)
        val id = s"find-in-rankings-${game.gameId.value}" + role.fold("")(r => s"-role-${r.gameRoleId.value}")

        form(
          cls := "search inline-form",
          // Enter in the field submits, as on "Find Players".
          onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
              val typed = prefix.now().trim
              if (typed.nonEmpty)
                  Store.runSignedIn(ApiClient.findInRankings(game.gameId, typed, role.map(_.gameRoleId)), busy)(
                    answer => found.set(Some(Right(answer)))
                  )
          },
          label(forId := id, "Find a Player"),
          div(
            cls := "compound",
            input(
              idAttr := id,
              tpe := "search",
              placeholder := "Nickname begins with",
              autoComplete := "off",
              controlled(value <-- prefix.signal, onInput.mapToValue --> prefix)
            ),
            // Not disabled while searching: a disabled button drops the keyboard's focus.
            button(tpe := "submit", aria.busy <-- busy.signal, "Find")
          ),
          // Mounted before it has anything to say, so that what arrives in it is announced. The lines
          // themselves are in the table, which is not announced; this says how many and where.
          div(
            aria.live := "polite",
            child <-- found.signal.map {
                case None            => emptyNode
                case Some(Left(why)) => p(cls := "empty", why)
                case Some(Right(none)) if none.ratings.isEmpty =>
                    p(cls := "empty", "No rated player's nickname starts with that.")
                case Some(Right(some)) =>
                    val count = some.ratings.size
                    val who = if (count == 1) "1 player" else s"$count players"
                    p(
                      cls := "detail",
                      s"$who found, shown at the foot of the rankings." +
                          (if (some.hasMore) " Type more of the nickname to narrow it." else "")
                    )
            }
          )
        )
    }

    /** The rank of a player not yet placed: a dash to the eye, and words to a screen reader. Text rather than an
      * `aria-label`, which a screen reader ignores on a span.
      */
    private def notRanked: HtmlElement =
        span(span(aria.hidden := true, "—"), span(cls := "sr-only", "not ranked yet"))

    /** A player's win–loss–draw record, as the rankings show it: "12 (2)–5 (1)–3", the numbers in parentheses the wins
      * and losses that came by forfeit, and left out when there are none. Said in words to a screen reader, which would
      * otherwise read the dashes and parentheses as punctuation.
      */
    private def recordText(r: MatchRecord): HtmlElement = {
        def byForfeit(n: Int) = if (n > 0) s" ($n)" else ""
        def count(n: Int, one: String, many: String) = s"$n ${if (n == 1) one else many}"
        def spoken(said: String, forfeits: Int) = said + (if (forfeits > 0) s", $forfeits by forfeit" else "")
        // The words as text a screen reader reads in place of the figures, rather than as an `aria-label`, which it
        // ignores on a span.
        span(
          span(
            aria.hidden := true,
            s"${r.wins}${byForfeit(r.forfeitWins)}–${r.losses}${byForfeit(r.forfeitLosses)}–${r.draws}"
          ),
          span(
            cls := "sr-only",
            List(
              spoken(count(r.wins, "win", "wins"), r.forfeitWins),
              spoken(count(r.losses, "loss", "losses"), r.forfeitLosses),
              count(r.draws, "draw", "draws")
            ).mkString("; ")
          )
        )
    }

    private def recordCell(r: MatchRecord): HtmlElement = td(recordText(r))

    /** Which cells a table header names: its column, or its row. Not among Laminar's attributes. */
    private val scopeAttr = htmlAttr("scope", com.raquo.laminar.codecs.StringAsIsCodec)

    /** One player's row of the leaderboard; for an admin of the game, with the box that sets their rating. */
    private def ratingRow(
        game: Game,
        rated: PublicPlayer,
        rating: Signal[EloRating],
        administers: Signal[Boolean],
        onSaved: EloRating => Unit
    ): HtmlElement =
        tr(
          td(
            child <-- rating.map(_.rank match {
                case Some(rank) => span(rank.toString)
                // Rated a moment ago, and placed a moment from now.
                case None => notRanked
            })
          ),
          // Their page, as a "Find Players" search would open it.
          th(scopeAttr := "row", playerLink(rated)),
          // The rating and matches the place was worked out from, so that the columns read in order with the
          // places -- matches telling equal ratings apart; the rating as it stands now is under the table, for
          // the player and for anybody searched for.
          td(child.text <-- rating.map(r => r.rankedRating.getOrElse(r.rating).toString)),
          td(child.text <-- rating.map(r => r.rankedMatches.getOrElse(r.matches).toString)),
          td(child <-- rating.map(r => recordText(r.record))),
          child <-- administers.map {
              case false => emptyNode
              case true =>
                  val typed = Var("")
                  val busy = Var(false)
                  val id = s"rating-${game.gameId.value}-${rated.playerId.value}"
                  td(
                    form(
                      cls := "compound",
                      onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
                          typed.now().trim.toIntOption.foreach { n =>
                              Store.run(ApiClient.setRating(game.gameId, rated.playerId, n), busy) { saved =>
                                  typed.set("")
                                  onSaved(saved)
                              }
                          }
                      },
                      label(cls := "sr-only", forId := id, s"New rating for ${rated.nickname}"),
                      ratingInput(id, typed, placeholder := "New rating"),
                      // Not disabled while saving: a disabled button drops the keyboard's focus. A press
                      // made meanwhile is ignored by the filter above instead.
                      button(tpe := "submit", aria.busy <-- busy.signal, "Set")
                    )
                  )
          }
        )

    /** For an admin of the game, on a player's page: setting that player's rating in it, whether they have one yet or
      * not. On the page a "Find Players" search opens, where the player is already chosen, so there is no nickname to
      * type.
      */
    private def ratePlayer(game: Game, player: PublicPlayer): HtmlElement = {
        val typed = Var("")
        val busy = Var(false)
        // What the last save came to: said aloud, since the box being emptied does not say it took.
        val said = Var("")
        val id = s"rate-${game.gameId.value}-${player.playerId.value}"

        form(
          cls := "search inline-form",
          onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
              typed.now().trim.toIntOption.foreach { n =>
                  Store.run(ApiClient.setRating(game.gameId, player.playerId, n), busy) { saved =>
                      typed.set("")
                      said.set(s"${saved.player.nickname} is now rated ${saved.rating} in ${game.displayName}.")
                  }
              }
          },
          label(forId := id, s"Rate ${player.nickname}"),
          div(
            cls := "compound",
            ratingInput(id, typed, placeholder := "New rating"),
            // Not disabled while saving: a disabled button drops the keyboard's focus.
            button(tpe := "submit", aria.busy <-- busy.signal, "Set rating")
          ),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal)
        )
    }

    /** A box for a rating: a whole number in the range the server accepts, which the browser checks before sending. */
    private def ratingInput(id: String, typed: Var[String], modifiers: Modifier[Input]*): Input =
        input(
          idAttr := id,
          tpe := "number",
          required := true,
          minAttr := EloRating.minimum.toString,
          maxAttr := EloRating.maximum.toString,
          stepAttr := "1",
          // The number pad on a phone, rather than the full keyboard.
          inputMode := "numeric",
          controlled(value <-- typed.signal, onInput.mapToValue --> typed),
          modifiers
        )

    /** What can be played in this game right now: the open challenges, and the form that offers one. A game that needs
      * characters needs one of this player's before either is possible, so that form stands in for both until there is
      * one.
      */
    private def gameChallenges(game: Game): HtmlElement =
        if (game.gameType == GameType.Plain)
            div(
              child <-- currentPlayer.map {
                  case None         => p(cls := "empty", "Loading…")
                  case Some(player) => challengePanel(game, player, Seq.empty)
              }
            )
        else {
            /* What the last "check again" found. Said here, outside the panel it is about, because a
             * check that finds a character replaces that panel with the challenge panel -- taking any
             * message it held, and the button that had focus, with it. */
            val checked = Var(Option.empty[String])
            var status: Option[dom.html.Element] = None

            div(
              child <-- currentPlayer.combineWith(Store.charactersByGame.signal).map {
                  case (None, _) => p(cls := "empty", "Loading…")
                  case (Some(player), byGame) =>
                      byGame.get(game.gameId) match {
                          case None => p(cls := "empty", "Loading…")
                          // A character is needed before this player can either offer or accept a
                          // challenge, so there is nothing to show until there is one.
                          // An admin of the game gets the challenge panel as well: they may offer a
                          // match for other players' characters without one of their own.
                          case Some(Nil) =>
                              val admin = Store.administers(game.gameId, player)
                              div(
                                characterPrompt(game, admin, checked, () => status.foreach(_.focus())),
                                child <-- admin.map(
                                  if (_) challengePanel(game, player, Seq.empty) else emptyNode
                                )
                              )
                          case Some(characters) =>
                              div(
                                // Characters are renamed where they were made, so the way to do it
                                // is the game's own page, as making one is.
                                game.characterUrl.fold(emptyNode)(url =>
                                    button(
                                      cls := "link",
                                      s"Manage your characters in ${game.displayName}",
                                      onClick --> (_ => openSignedIn(url))
                                    )
                                ),
                                challengePanel(game, player, characters)
                              )
                      }
              },
              // Focusable but not tabbable: focus is put here when the button that had it goes away.
              p(
                role := "status",
                tabIndex := -1,
                onMountCallback(context => status = Some(context.thisNode.ref)),
                onUnmountCallback(_ => status = None),
                child.text <-- checked.signal.map(_.getOrElse(""))
              )
            )
        }

    /** Where a player with no character in this game is sent to make one.
      *
      * Characters are made in their game engine, not here: the engine builds one with the player and then reports it to
      * matchmaker, which is how it comes to be in [[Store.charactersByGame]]. So all this offers is the way there — the
      * game's `characterUrl`, opened beside this page — and a way to look again once the player is back. A game nobody
      * has given a url says so rather than offering a button that goes nowhere.
      *
      * What a check finds is said in `checked`, which [[gameChallenges]] shows outside this panel; `focusStatus` moves
      * focus there before a successful check replaces the panel.
      */
    private def characterPrompt(
        game: Game,
        admin: Signal[Boolean],
        checked: Var[Option[String]],
        focusStatus: () => Unit
    ): HtmlElement =
        div(
          cls := "card",
          h3(s"Make Your Character for ${game.displayName}"),
          // An admin of the game has the challenge panel below this, so for them a character is
          // needed only to play, not to offer a match. Text rather than a re-rendered card, so the
          // buttons here keep focus if the admin list arrives while one has it.
          p(
            child.text <-- admin.map {
                case true =>
                    s"You need a character in ${game.displayName} to play or to accept a challenge. " +
                        "As an admin of this game you can still host a match for other players' characters below. "
                case false =>
                    s"You need a character in ${game.displayName} before you can offer or accept a challenge. "
            },
            "Characters are made in the game itself, which tells matchmaker once yours exists."
          ),
          game.characterUrl match {
              case Some(url) =>
                  div(
                    button(
                      s"Make a character in ${game.displayName}",
                      onClick --> (_ => openSignedIn(url))
                    ),
                    busyButton("I've made one — check again", classes = Some("link")) { busy =>
                        // Cleared first, so that a second "no character yet" is a change the live
                        // region announces rather than the same text it already holds.
                        checked.set(None)
                        // The session that asked, checked when the answer comes rather than when it is
                        // used: an answer that outlives it -- a sign-out, or somebody else signed in
                        // since -- is about the player who is gone, and must not reload, announce or
                        // move focus for the one who is here. It comes back as `None` whether it
                        // succeeded or failed, so not even its failure is reported to them.
                        val signIn = Store.currentSignIn
                        val asked = ApiClient.characters(game.gameId).transform {
                            case _ if !Store.isStillSignedInAs(signIn) => scala.util.Success(None)
                            case answered                              => answered.map(Some(_))
                        }
                        Store.run(asked, busy) {
                            case None => ()
                            // Nothing found is what the store already holds, so there is nothing to
                            // reload -- and reloading would only re-render this panel for no reason.
                            case Some(found) if found.isEmpty =>
                                checked.set(Some("No character yet. Finish making one in the game."))
                            case Some(found) =>
                                checked.set(
                                  Some(
                                    s"Found ${found.map(_.name).mkString(", ")}. " +
                                        "You can now offer or accept a challenge."
                                  )
                                )
                                focusStatus()
                                // Through the store's own load, which guards its own answer the same way.
                                Store.refreshCharacters(game.gameId)
                        }
                    }
                  )
              case None =>
                  p(cls := "detail", "This game has no page to make a character on yet; ask an administrator.")
          }
        )

    /* `characters` is every character this player has in the game, empty for a plain game. A challenge
     * is offered, or entered, as whichever of them the player chooses. */
    private def challengePanel(game: Game, player: Player, characters: Seq[Character[String]]): HtmlElement = {
        // One flag for both lists, held out here rather than inside either of them: they are two
        // views of one request, and this element is rebuilt when that request answers.
        val refreshingChallenges = Var(false)

        div(
          child <-- Store.challengesByGame.signal.map(_.get(game.gameId)).map {
              case None             => p(cls := "empty", "Loading challenges…")
              case Some(challenges) =>
                  // `ui.txt` asks for the player's own challenges in a separate list, because what you
                  // can do with them is different: start or cancel yours, enter someone else's.
                  val (mine, others) = challenges.partition(_.summary.isOwner)
                  div(
                    // A button rather than a form standing open: offering a challenge is one of several
                    // things to do on this screen, and a form is what the screen looks like it is for.
                    // The form itself is a dialog over the screen, so the lists stay where they were.
                    button(
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded <-- Store.showChallengeForm.signal,
                      "Create Challenge",
                      onMountCallback(context => challengeTrigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => challengeTrigger = None),
                      onClick --> (_ => Store.showChallengeForm.set(true))
                    ),
                    // Built from the slot as it stands when the form opens: a challenge is composed
                    // for one player, and one being looked up while the form is open is a different
                    // challenge, offered by opening it again.
                    child <-- Store.showChallengeForm.signal.map {
                        if (_) challengeDialog(newChallengeForm(game, player, characters, Store.invitee.now()))
                        else emptyNode
                    },
                    refreshableSection(
                      "Your Open Challenges",
                      refreshingChallenges,
                      () => Store.reloadChallenges(game.gameId),
                      subsection = true
                    )(
                      if (mine.isEmpty) p(cls := "empty", "You have none open.")
                      else ul(mine.map(myChallengeRow(game, _)))
                    ),
                    refreshableSection(
                      "Open Challenges",
                      refreshingChallenges,
                      () => Store.reloadChallenges(game.gameId),
                      subsection = true
                    )(
                      if (others.isEmpty) p(cls := "empty", "Nobody is waiting for an opponent.")
                      else ul(others.map(openChallengeRow(game, _, characters, player)))
                    )
                  )
          }
        )
    }

    /* The button the challenge dialog is opened from, so closing it can put focus back. A dialog
     * opened from somebody's page (`Store.showGameToInvite`) returns focus here too: it is where the
     * form lives on this screen. */
    private var challengeTrigger: Option[dom.html.Element] = None

    /* Closing the form abandons the invitation it was opened for. Left set, it would address the next
     * challenge offered from this screen to whoever was looked at before -- and nothing on the closed
     * form would say so. */
    private def closeChallengeForm(): Unit = {
        Store.invitee.set(None)
        Store.showChallengeForm.set(false)
        challengeTrigger.foreach(_.focus())
    }

    /* The offer form as a modal dialog: the same scrim and card the notification cascade uses, closed
     * by Escape, by a click on the backdrop, or by its own Close button. */
    private def challengeDialog(form: HtmlElement): HtmlElement =
        div(
          cls := "modal-scrim",
          Modal.inertBehind,
          onClick --> (event => if (event.target == event.currentTarget) closeChallengeForm()),
          form.amend(
            cls := "modal",
            role := "dialog",
            htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
            aria.labelledBy := "offer-challenge-heading",
            tabIndex := -1,
            inContext(node => onMountCallback(_ => node.ref.focus())),
            onKeyDown.filter(_.key == "Escape") --> { event =>
                event.stopPropagation()
                closeChallengeForm()
            },
            errorBanner,
            div(
              cls := "alternatives",
              button(tpe := "button", cls := "link", "Close", onClick --> (_ => closeChallengeForm()))
            )
          )
        )

    /** Who has entered a challenge, by name: shown on every row, since who you would be playing is most of what decides
      * whether to enter.
      */
    private def entrantsDetail(c: ChallengeSummary): Node =
        if (c.entries.isEmpty) div(cls := "detail", "nobody has entered yet")
        else
            div(
              cls := "detail",
              "entered: " + c.entries
                  .map(e => e.characterName.fold(e.nickname)(name => s"$name (${e.nickname})"))
                  .mkString(", ")
            )

    /** One of the reader's own open challenges: its terms, who is in it, and what its owner may do — start it once
      * enough have entered, cancel it, and change who is invited.
      */
    private def myChallengeRow(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        val least = math.max(2, game.requiredSeats)
        li(
          cls := "row",
          div(cls := "title", challengeTerms(t, c.entries.size)),
          entrantsDetail(c),
          parameterDetail(game, t.settings.gameSettings),
          if (t.settings.isPublic) div(cls := "detail", "public") else emptyNode,
          if (t.settings.isFriendly) friendlyLabel() else emptyNode,
          // A game's admin offering a match for others to play: said, because nothing else on the row
          // tells it from one they are seated in.
          if (!c.summary.isEntered) div(cls := "detail", "you are not playing in it") else emptyNode,
          // Starting is the owner's call, once enough have entered to fill the game's required roles: a
          // challenge that seats more may be worth starting without them. The players then choose their
          // roles, the strongest first.
          if (c.entries.size >= least)
              busyButton("Start") { busy =>
                  Store.run(ApiClient.tournamentStep(game.gameId, t.tournamentId, "start"), busy, challengeStale(t))(
                    _ => reloadAfterStart(game.gameId)
                  )
              }
          else div(cls := "detail", s"needs at least $least players to start"),
          busyButton("Cancel") { busy =>
              Store.run(ApiClient.tournamentStep(game.gameId, t.tournamentId, "cancel"), busy, challengeStale(t))(_ =>
                  reloadChallengeSections(game.gameId)
              )
          },
          invitedList(game, c),
          invitePanel(game, c)
        )
    }

    /** Who has been asked to this challenge already, and its owner's way of taking an invitation back. An invitation is
      * permission and nothing more, so one taken up stays here; revoking it then changes nothing about the entry.
      */
    private def invitedList(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        if (c.invited.isEmpty && c.invitedCharacters.isEmpty) div()
        else
            div(
              cls := "detail-panel",
              h4("Invited"),
              ul(
                c.invited.map { invitation =>
                    val entered = c.entries.exists(_.playerId.contains(invitation.playerId))
                    li(
                      cls := "row",
                      div(cls := "title", invitation.nickname),
                      div(cls := "detail", if (entered) "entered" else "not answered yet"),
                      if (entered) emptyNode
                      else
                          busyButton("Revoke", classes = Some("link")) { busy =>
                              Store.run(
                                ApiClient.revokeTournamentInvitation(game.gameId, t.tournamentId, invitation.playerId),
                                busy,
                                challengeStale(t)
                              )(_ => Store.refreshChallenges(game.gameId))
                          }
                    )
                } ++ c.invitedCharacters.map { invitation =>
                    val id = invitation.character.characterId
                    val entered = c.entries.exists(_.characterId.contains(id))
                    li(
                      cls := "row",
                      div(cls := "title", invitation.character.name),
                      div(
                        cls := "detail",
                        invitation.owner.fold("")(o => s"${o.nickname}'s · ") +
                            (if (entered) "entered" else "not answered yet")
                      ),
                      if (entered) emptyNode
                      else
                          busyButton("Revoke", classes = Some("link")) { busy =>
                              Store.run(
                                ApiClient.revokeTournamentCharacterInvitation(game.gameId, t.tournamentId, id),
                                busy,
                                challengeStale(t)
                              )(_ => Store.refreshChallenges(game.gameId))
                          }
                    )
                }
              )
            )
    }

    /** Inviting somebody to a challenge that already exists: find them, and ask.
      *
      * The other half of [[inviteControl]], which invites at the moment a challenge is created. This one exists because
      * an owner's mind changes after the fact — somebody who should have been asked in the first place — and because
      * the alternative is cancelling the challenge and offering it again.
      *
      * Its own search box rather than the one on "Find Players": that box and its results belong to that screen, and
      * are deliberately kept across a visit to a player's page, so borrowing them here would clear a search somebody
      * means to come back to. The panel is rebuilt, and so collapses, when the challenge list is re-read — which a
      * successful invitation causes, and which is the intended end of it: the invitation now shows in the list above.
      */
    private def invitePanel(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        val open = Var(false)
        val prefix = Var("")
        val found = Var(Option.empty[PlayerSearchResult])
        // Nobody already invited or in it, and not its owner: none of them has anything to be asked to, so a name that
        // cannot be acted on is left out of the results. In a character game it is characters that are asked, so a
        // player stays findable, and the characters already asked or in it are what is left out.
        val characterGame = game.gameType == GameType.Character
        val alreadyAsked =
            if (characterGame) Set(t.owner)
            else c.invited.map(_.playerId).toSet ++ c.entries.flatMap(_.playerId) + t.owner
        val charactersAsked =
            c.invitedCharacters.map(_.character.characterId).toSet ++ c.entries.flatMap(_.characterId)

        div(
          button(
            tpe := "button",
            cls := "link",
            aria.expanded <-- open.signal,
            child.text <-- open.signal.map(if (_) "Close" else "Invite a player"),
            onClick --> { _ =>
                // Emptied on the way out, so re-opening it is a fresh question.
                if (open.now()) { prefix.set(""); found.set(None) }
                open.update(!_)
            }
          ),
          child <-- open.signal.map { showing =>
              if (!showing) emptyNode
              else
                  div(
                    cls := "detail-panel",
                    // How the search went, from a region mounted with the panel: the results list is built at the
                    // moment it has something to say, and a region nobody has seen yet has no change to report.
                    p(
                      cls := "sr-only",
                      role := "status",
                      aria.live := "polite",
                      child.text <-- found.signal.map {
                          case None => ""
                          case Some(result) =>
                              val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                              if (askable.isEmpty) "Nobody new by that name."
                              else if (askable.size == 1) "One player found."
                              else s"${askable.size} players found."
                      }
                    ),
                    field(
                      "Find a player",
                      input(tpe := "search", controlled(value <-- prefix.signal, onInput.mapToValue --> prefix))
                    ),
                    busyButton("Search", disabledWhen = prefix.signal.map(_.trim.isEmpty)) { busy =>
                        // Guarded by the sign-in counter, because this writes into the store: an answer after a
                        // sign-out would teach the next player's session who the previous one was looking for.
                        val signIn = Store.currentSignIn
                        Store.run(ApiClient.searchPlayers(prefix.now().trim), busy) { result =>
                            if (Store.isStillSignedInAs(signIn)) {
                                Store.remember(result.players)
                                found.set(Some(result))
                            }
                        }
                    },
                    child <-- found.signal.map {
                        case None => emptyNode
                        case Some(result) =>
                            val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                            if (askable.isEmpty)
                                p(
                                  cls := "empty",
                                  "Nobody new by that name. Anyone already invited is not listed again."
                                )
                            else
                                ul(
                                  askable.map(candidate =>
                                      if (characterGame)
                                          li(
                                            cls := "row",
                                            div(cls := "title", candidate.nickname),
                                            characterInviteButtons(game.gameId, candidate, charactersAsked) {
                                                (ch, busy) =>
                                                    Store.run(
                                                      ApiClient.inviteCharacterToTournament(
                                                        game.gameId,
                                                        t.tournamentId,
                                                        ch.characterId
                                                      ),
                                                      busy,
                                                      challengeStale(t)
                                                    )(_ => Store.refreshChallenges(game.gameId))
                                            }
                                          )
                                      else
                                          li(
                                            cls := "row",
                                            div(cls := "title", candidate.nickname),
                                            busyButton(s"Invite ${candidate.nickname}", classes = Some("link")) {
                                                busy =>
                                                    Store.run(
                                                      ApiClient.inviteToTournament(
                                                        game.gameId,
                                                        t.tournamentId,
                                                        candidate.playerId
                                                      ),
                                                      busy,
                                                      challengeStale(t)
                                                    )(_ => Store.refreshChallenges(game.gameId))
                                            }
                                          )
                                  )
                                )
                    }
                  )
          }
        )
    }

    /** A challenge somebody else is offering, and this player's way into it — or out of it, once they are in.
      *
      * Entering names nothing but, in a character game, the character: a challenge's players choose their roles once it
      * starts. A character already in it is not offered again, and a player with none left to enter is told so.
      */
    private def openChallengeRow(
        game: Game,
        c: ChallengeSummary,
        characters: Seq[Character[String]],
        me: Player
    ): HtmlElement = {
        val t = c.summary.tournament
        val characterGame = game.gameType == GameType.Character
        val mine = c.entries.find(_.playerId.contains(me.playerId))
        // Not already in it, and -- by invitation, in a character game -- invited: it is the character that is.
        val eligible = characters
            .filterNot(ch => c.entries.exists(_.characterId.contains(ch.characterId)))
            .filter(ch =>
                !t.settings.isInvitational || c.invitedCharacters.exists(_.character.characterId == ch.characterId) ||
                    !characterGame
            )
        val character = Var(eligible.headOption.map(_.characterId))
        val full = t.settings.maxEntrants.exists(c.entries.size >= _)

        li(
          cls := "row",
          div(cls := "title", s"${c.summary.ownerNickname}'s challenge"),
          div(cls := "detail", challengeTerms(t, c.entries.size)),
          entrantsDetail(c),
          parameterDetail(game, t.settings.gameSettings),
          if (t.settings.isFriendly) friendlyLabel() else emptyNode,
          if (c.summary.isInvited && mine.isEmpty) div(cls := "detail", "you were invited") else emptyNode,
          mine match {
              case Some(entry) =>
                  div(
                    div(cls := "detail", "you have entered"),
                    busyButton("Withdraw", classes = Some("link")) { busy =>
                        Store.run(
                          ApiClient.withdrawFromTournament(game.gameId, t.tournamentId, entry.entrantId),
                          busy,
                          challengeStale(t)
                        )(_ => reloadChallengeSections(game.gameId))
                    }
                  )
              case None if full => div(cls := "detail", "it is full")
              case None if characterGame && characters.isEmpty =>
                  div(cls := "detail", "you need a character in this game to enter")
              case None if characterGame && eligible.isEmpty && t.settings.isInvitational =>
                  div(cls := "detail", "none of your characters is invited")
              case None if characterGame && eligible.isEmpty =>
                  div(cls := "detail", "your characters in this game have all entered it")
              case None =>
                  div(
                    // Shown even when there is only one, so the player can see who they are entering.
                    if (characterGame)
                        field(
                          "Enter as",
                          select(
                            onChange.mapToValue --> { raw =>
                                character.set(
                                  raw.toLongOption
                                      .map(CharacterId.apply)
                                      .filter(id => eligible.exists(_.characterId == id))
                                )
                            },
                            value <-- character.signal.map(_.map(_.value.toString).getOrElse("")),
                            eligible.map(ch => option(value := ch.characterId.value.toString, ch.name))
                          )
                        )
                    else emptyNode,
                    busyButton("Enter", disabledWhen = character.signal.map(_.isEmpty && characterGame)) { busy =>
                        Store.run(
                          ApiClient
                              .enterTournament(game.gameId, t.tournamentId, character.now().filter(_ => characterGame)),
                          busy,
                          challengeStale(t)
                        ) { _ =>
                            // Entering may have filled one that starts itself: the start's reloads cover both.
                            reloadAfterStart(game.gameId)
                        }
                    }
                  )
          }
        )
    }

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
    private def countdown(deadline: java.time.Instant): HtmlElement = {
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

    /** What every player has left of a chess-clock budget.
      *
      * The player on the clock is shown their deadline, counting down, because their balance is being spent as it is
      * read; everybody else is shown a balance, which is not moving and would be a lie if it ticked. That is the same
      * distinction `PlayerClock.deadline` draws, and it is drawn there rather than here so the server and the page
      * cannot disagree about who is spending.
      */
    private def clockTable(clocks: Seq[PlayerClock]): HtmlElement =
        ul(
          cls := "clocks",
          clocks.map { clock =>
              li(
                cls := "clock",
                span(cls := "who", clock.nickname),
                clock.deadline match {
                    case Some(deadline) => countdown(deadline)
                    // Said as a balance rather than as a countdown: "6:00 left" of a clock that is not
                    // running is a fact, and it stays true until this player moves again.
                    case None => span(cls := "detail", Format.remaining(clock.remaining.toMillis))
                }
              )
          }
        )

    /** A captioned text field whose explanation is a tip beside it rather than a line under it: [[withTip]] around a
      * `label.field`, with the input pointed at the tip so that it is read out with the field.
      */
    private[ui] def tipField(id: String, caption: String, text: String)(control: HtmlElement): HtmlElement =
        withTip(id, caption, text)(label(cls := "field", caption, control.amend(aria.describedBy := id)))

    /** A control with a tip beside it: a "?" button that shows `text` on hover, on keyboard focus, and on a tap, which
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

    /** "friendly" under a match or a challenge that is one, with what that means a tap away. Said only when it is: a
      * rated match is the ordinary kind, and needs no saying.
      */
    private val friendlyMeaning =
        "A friendly match is played for fun: it does not change either player's Elo rating or place in the " +
            "rankings. Every other match is rated."

    private def friendlyLabel(): HtmlElement =
        withTip(
          freshTipId("friendly-tip"),
          "friendly",
          friendlyMeaning
        )(div(cls := "detail", "friendly")).amend(cls := "inline-tip")

    /** The "Friendly" column's heading: the word itself showing what a friendly match is, as the Elo column's
      * "friendly" does. And the word again as plain text, for the narrow layout: there the heading row is hidden from
      * sight but kept for a screen reader, and its buttons are taken out so that nothing unseen takes the focus --
      * which, the word being the button, would leave the column with no name.
      */
    private def friendlyHeading(): HtmlElement =
        span(
          span(cls := "narrow-heading", "Friendly"),
          tipWord(freshTipId("friendly-tip"), "Friendly", friendlyMeaning)
        )

    private[ui] def withTip(id: String, subject: String, text: String)(control: HtmlElement): HtmlElement =
        tipped(id, text, control)(cls := "tip-toggle", aria.label := s"About $subject", "?")

    /** A word that is its own tip: hovered, focused or tapped, it shows what it means, as a "?" beside it would. For a
      * cell too narrow to hold both a word and a "?" -- the heading of a running match's Friendly column. A button, so
      * that a keyboard and a finger can reach the tip as well as a mouse can, but set as the word with a dotted line
      * under it.
      */
    private def tipWord(id: String, word: String, text: String): HtmlElement =
        tipped(id, text, emptyNode)(cls := "tip-toggle tip-word", word).amend(cls := "inline-tip")

    /* The tip, and the button that shows it after `control`: the "?" of `withTip`, or `tipWord`'s word. */
    private def tipped(id: String, text: String, control: Modifier[HtmlElement])(
        toggle: Modifier[HtmlElement]*
    ): HtmlElement = {
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
          text
        )
        // In a table that scrolls sideways, the scrolling box would clip a tip hanging below its heading, so there the
        // tip is pinned to the window instead (`.table-scroll .tip`), under its "?" as it is about to show.
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
            // `open`, or by the CSS, which shows it for keyboard focus and hover without telling `open` -- and a
            // hovered tip's Escape comes from wherever focus is, not from this button. So it is heard on the document,
            // while capturing, which is before anything on the way to that focus can hear it.
            inContext(node =>
                documentEvents(_.onKeyDown.useCapture)
                    .filter(event =>
                        event.key == "Escape" && !dismissed.now() &&
                            (open.now() || node.ref.matches(":focus-visible") || node.ref.matches(":hover"))
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

    /** One of a game's parameters as a challenger picks it: `name` is the key it is stored and sent to the engine
      * under, `label` what the challenger is shown for it.
      */
    private[ui] case class ParameterChoice(name: String, label: String, values: Seq[String], default: Option[String])

    /** The values a challenger may pick for each of the game's parameters, in the order [[Format.parameterValues]] puts
      * them. Parameters with no values to choose between are left out: there is nothing to offer.
      */
    private[ui] def parameterChoices(game: Game): Seq[ParameterChoice] =
        game.parameters
            .map(p =>
                ParameterChoice(
                  p.name,
                  p.displayName,
                  Format.parameterValues(p.values.map(_.value.toString)),
                  p.defaultValue.map(_.toString)
                )
            )
            .filter(_.values.nonEmpty)

    /** A challenge's parameter choices as its `settings` carries them: `{"rounds":"12"}`. The same flat object of
      * strings `ChallengeSettings` reads on the server.
      */
    private[ui] def settingsOf(choices: Map[String, String]): String =
        ujson.write(ujson.Obj.from(choices.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

    /** What was chosen for each of the game's parameters, read back out of `settings`, as the parameter's label and the
      * value chosen.
      */
    private def chosenParameters(game: Game, settings: String): Seq[(String, String)] = {
        val stored = storedChoices(settings)
        parameterChoices(game).flatMap(choice => stored.get(choice.name).map(choice.label -> _))
    }

    /** The choices `settings` holds, by parameter name: the flat object of strings [[settingsOf]] writes, with a whole
      * number read as one too. Empty for settings that are not such an object.
      */
    private[ui] def storedChoices(settings: String): Map[String, String] =
        try
            ujson.read(settings) match {
                case o: ujson.Obj =>
                    o.value.toMap.collect {
                        case (k, ujson.Str(v))              => k -> v
                        case (k, ujson.Num(n)) if n.isWhole => k -> n.toLong.toString
                    }
                case _ => Map.empty[String, String]
            }
        catch { case _: Throwable => Map.empty[String, String] }

    /** Each of the game's parameters' value for a form to start from: what `settings` chose, where it is still a value
      * the game allows, else the game's default, else its first value.
      */
    private[ui] def startingChoices(game: Game, settings: String = "{}"): Map[String, String] = {
        val stored = storedChoices(settings)
        parameterChoices(game).map { choice =>
            choice.name -> stored
                .get(choice.name)
                .filter(choice.values.contains)
                .orElse(choice.default.filter(choice.values.contains))
                .getOrElse(choice.values.head)
        }.toMap
    }

    /** One select per game parameter, captioned with what players are shown for it and keyed by the name the engine is
      * sent, writing into `parameters`.
      */
    private[ui] def parameterFields(game: Game, parameters: Var[Map[String, String]]): Seq[HtmlElement] =
        parameterChoices(game).map { choice =>
            field(
              if (choice.label == choice.name) choice.name.capitalize else choice.label,
              select(
                onChange.mapToValue --> (chosen => parameters.update(_.updated(choice.name, chosen))),
                value <-- parameters.signal.map(_.getOrElse(choice.name, "")),
                choice.values.map(v => option(value := v, v))
              )
            )
        }

    /** The terms a challenge was offered on beyond its clock — "rounds: 12" — so that whoever accepts it knows what
      * they are agreeing to. Nothing at all for a game with no parameters, or a challenge that chose none.
      */
    private def parameterDetail(game: Game, settings: String): Node =
        chosenParameters(game, settings) match {
            case Seq()  => emptyNode
            case chosen => div(cls := "detail", chosen.map((label, v) => s"$label: $v").mkString(" · "))
        }

    /** The clock something is played under, said in full wherever it is said at all.
      *
      * Both halves matter and neither is guessable from the other: ten minutes per turn and ten minutes for the whole
      * match are very different games. On a challenge it is the terms being offered; on a running match it is the rule
      * behind the deadline, which the deadline alone does not give away — the same "due" line comes of a fresh per-turn
      * limit and of a budget with a minute left in it.
      *
      * Said even when there is no limit, because "nothing here about a clock" and "no clock" are otherwise the same
      * sight.
      */
    private def timeLimitDetail(
        limit: Option[java.time.Duration],
        kind: TimeLimitKind,
        unit: TimeLimitUnit,
        live: Boolean
    ): HtmlElement =
        div(
          cls := "detail",
          limit match {
              case None => "no time limit"
              // A live match's clock is the game's rather than matchmaker's: a player who runs out
              // loses the match there and then.
              case Some(limit) =>
                  val terms = kind match {
                      case TimeLimitKind.PerTurn => s"${Format.duration(limit, unit)} per turn"
                      case TimeLimitKind.Total   => s"${Format.duration(limit, unit)} each for the whole match"
                  }
                  if (live) s"live: $terms" else terms
          }
        )

    /** The form that offers a challenge, and — when `invitee` is set — invites one player to it in the same request.
      *
      * `invitee` arrives from that player's own page, which is the only screen that can name somebody: see
      * [[inviteControl]]. With one, the challenge is closed, because the reason to invite a particular player is
      * usually that the game is for them.
      *
      * A challenge names no role: its players choose theirs once it starts, the strongest first. What it does name is
      * how many it seats — between the game's required roles and all of them — which is also when it is full.
      */
    private def newChallengeForm(
        game: Game,
        player: Player,
        characters: Seq[Character[String]],
        invitee: Option[PublicPlayer] = None
    ): HtmlElement = {
        val isPublic = Var(false)
        // In a character game it is one of the invitee's characters that is invited, chosen here.
        val inviteeCharacter = Var(Option.empty[CharacterId])
        // Whether it starts by itself once it is full, instead of waiting for this challenger to press Start. On by
        // default: the challenge is an offer to play, and having to come back and press Start once everybody has said
        // yes is a step most challengers do not want. One who would go ahead with fewer turns it off.
        val autoStart = Var(true)
        // How long the match may take, blank for no limit, in the unit beside it. Blank by default because an unlimited
        // game is the one nobody can lose by walking away from their desk.
        val timeLimit = Var("")
        val timeLimitUnit = Var(TimeLimitUnit.Hours)
        // Whether the match is played live: the game engine runs every turn against the clock, so a time limit is then
        // required. Off by default -- it asks the players to be there together.
        val live = Var(false)
        // When it is meant to be played, as the browser's date and time input gives it; blank for none.
        val plannedStart = Var("")
        // How many it seats: the game's R to start with, and anything from enough to fill its required roles to every
        // one of its roles.
        val least = math.max(2, game.requiredSeats)
        val most = game.roles.size
        val seats = Var(math.min(most, math.max(least, game.poolSeats)))
        // Whether this player administers the game, which is what lets them offer a match they will not play in, or
        // one that is not friendly: `Store.administers`, held here so that the button can read it when it is pressed.
        val administers = Var(player.isAdmin)
        // Whether the challenger plays. Only a game's admin is offered the choice. In a character game a player plays
        // as one of their characters, so an admin with none has no place to take.
        val canPlay = game.gameType != GameType.Character || characters.nonEmpty
        val plays = Var(canPlay)
        val friendly = Var(true)
        val seated: Signal[Boolean] = plays.signal.combineWith(administers.signal).map((p, a) => p || !a)
        def isSeatedNow: Boolean = plays.now() || !administers.now()
        val characterGame = game.gameType == GameType.Character
        // The challenger's own character: the first of them to start with, which is the only one for most players,
        // but chosen here rather than assumed.
        val ownCharacter = Var(characters.headOption.map(_.characterId))
        // One value per game parameter the challenger may choose — how many rounds a bout is, say — starting from the
        // game's default, or its first value when it names none. Sent as the challenge's settings, and handed to the
        // engine in place of the default when the match starts.
        val parameters = Var(startingChoices(game))

        div(
          cls := "card",
          Store.administers(game.gameId, player) --> administers,
          h3(idAttr := "offer-challenge-heading", "Offer a Challenge"),
          if (most < least)
              p(cls := "empty", s"${game.displayName} has too few roles to be played as a challenge.")
          else emptyNode,
          // Shown even when there is only one, so the challenger can see who they are playing as -- and not at all to
          // an admin who will not play.
          if (characterGame)
              child <-- seated.map(inIt =>
                  if (!inIt) emptyNode
                  else
                      field(
                        "Your character",
                        select(
                          onChange.mapToValue --> { raw =>
                              ownCharacter.set(
                                raw.toLongOption
                                    .map(CharacterId.apply)
                                    .filter(id => characters.exists(_.characterId == id))
                              )
                          },
                          value <-- ownCharacter.signal.map(_.map(_.value.toString).getOrElse("")),
                          characters.map(c => option(value := c.characterId.value.toString, c.name))
                        )
                      )
              )
          else emptyNode,
          // What only a game's admin may offer. Both boxes in one place, because they are the two ways this challenge
          // can differ from one anybody else could make.
          child <-- administers.signal.map { admin =>
              if (!admin) emptyNode
              else
                  div(
                    if (!canPlay)
                        p(
                          cls := "detail",
                          "You have no character in this game, so this is a match you will not play in."
                        )
                    else
                        withTip(
                          "plays-tip",
                          "I will play in this match",
                          "Leave this unticked to offer a match for other players to play, which you can invite " +
                              "them to, but have no place in."
                        )(
                          label(
                            input(
                              tpe := "checkbox",
                              aria.describedBy := "plays-tip",
                              controlled(checked <-- plays.signal, onClick.mapToChecked --> plays)
                            ),
                            "I will play in this match"
                          )
                        ),
                    withTip(
                      "friendly-tip",
                      "Friendly",
                      "A friendly match does not move its players' ratings; untick for a rated one. As an admin of " +
                          "this game you can change it later, until the match is completed."
                    )(
                      label(
                        input(
                          tpe := "checkbox",
                          aria.describedBy := "friendly-tip",
                          controlled(checked <-- friendly.signal, onClick.mapToChecked --> friendly)
                        ),
                        "Friendly"
                      )
                    )
                  )
          },
          // Offered only when there is a choice to make: a game whose roles are all required seats exactly that many.
          if (most > least)
              withTip(
                "seats-tip",
                "Players",
                s"How many play, from $least to $most. It is full, and starts if you ask it to, when that many have " +
                    "entered; you can also start it with fewer, as long as there are at least " + least + "."
              )(
                label(
                  cls := "field",
                  "Players",
                  select(
                    aria.describedBy := "seats-tip",
                    onChange.mapToValue --> (raw =>
                        raw.toIntOption.filter(n => n >= least && n <= most).foreach(seats.set)
                    ),
                    value <-- seats.signal.map(_.toString),
                    (least to most).map(n => option(value := n.toString, n.toString))
                  )
                )
              )
          else emptyNode,
          // Built once: the game's parameters do not change while the form is open.
          parameterFields(game, parameters),
          invitee.fold(emptyNode)(asked =>
              if (game.gameType == GameType.Character)
                  div(
                    cls := "detail-panel",
                    p(cls := "detail", s"Inviting ${asked.nickname}: only the character you choose may enter."),
                    characterChooser(game.gameId, asked, Set.empty, inviteeCharacter)
                  )
              else p(cls := "detail", s"Inviting ${asked.nickname}: only they may enter.")
          ),
          // The number and the unit it is in, in one field: they are one answer, and a caption over each would read as
          // two questions. How the time is spent is the game's: a chess clock shared out among the players of a game
          // of turns, and a limit on each turn in one where everybody moves at once.
          withTip(
            "time-limit-tip",
            "Time to play",
            "How long the match may take. In a game of turns each player gets an equal share of it as a chess " +
                "clock; in a game where everybody moves at once it is shared out among the turns. Leave it blank " +
                "for no limit."
          )(
            label(
              cls := "field",
              "Time to play",
              div(
                cls := "compound",
                input(
                  tpe := "number",
                  minAttr := "1",
                  stepAttr := "1",
                  aria.label := "Time to play, blank for no limit",
                  aria.invalid <-- timeLimit.signal
                      .combineWith(live.signal)
                      .map((raw, isLive) => limitProblem(raw, isLive).isDefined.toString),
                  aria.describedBy := "time-limit-tip time-limit-problem",
                  controlled(value <-- timeLimit.signal, onInput.mapToValue --> timeLimit)
                ),
                select(
                  aria.label := "Time to play unit",
                  onChange.mapToValue --> (raw => timeLimitUnit.set(TimeLimitUnit.fromCode(raw))),
                  value <-- timeLimitUnit.signal.map(_.code),
                  TimeLimitUnit.offered.map(unit => option(value := unit.code, unit.label))
                )
              )
            )
          ),
          // Mounted before it has anything to say, and it is the text that arrives: a message built at the moment it
          // becomes true is a region nothing was watching. A `div` that is empty whenever the field is valid, so that
          // it holds no margins open under the input.
          div(
            idAttr := "time-limit-problem",
            aria.live := "polite",
            cls <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => if (limitProblem(raw, isLive).isEmpty) "" else "empty"),
            child.text <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => limitProblem(raw, isLive).getOrElse(""))
          ),
          withTip(
            "live-tip",
            "Live",
            "Played in real time. The game engine runs the turns and keeps the clock itself, so the time to play " +
                "is required. A player who runs out of time loses the match."
          )(
            label(
              input(
                tpe := "checkbox",
                aria.describedBy := "live-tip",
                controlled(checked <-- live.signal, onClick.mapToChecked --> live)
              ),
              "Live"
            )
          ),
          withTip(
            "public-tip",
            "Public",
            "Anyone may watch the match, without signing in, and it is listed among the public matches on each " +
                "player's page. It does not change who may enter the challenge."
          )(
            label(
              input(
                tpe := "checkbox",
                aria.describedBy := "public-tip",
                controlled(checked <-- isPublic.signal, onClick.mapToChecked --> isPublic)
              ),
              "Public"
            )
          ),
          withTip(
            "planned-start-tip",
            "When to play",
            "When you mean the match to be played, shown to anyone deciding whether to enter. It starts the match " +
                "no sooner and no later. Leave it blank to say nothing."
          )(
            label(
              cls := "field",
              "When to play (optional)",
              input(
                tpe := "datetime-local",
                aria.describedBy := "planned-start-tip",
                controlled(value <-- plannedStart.signal, onInput.mapToValue --> plannedStart)
              )
            )
          ),
          // Ticked and fixed for a challenger who will not play: nobody would be watching for it to fill, so it has to
          // start without them, and the server refuses one that would not. Disabled rather than hidden, so that what
          // the challenge will do is said.
          child <-- seated.map { inIt =>
              div(
                label(
                  input(
                    tpe := "checkbox",
                    disabled := !inIt,
                    aria.describedBy := (if (inIt) "" else "auto-start-why"),
                    controlled(checked <-- autoStart.signal.map(_ || !inIt), onClick.mapToChecked --> autoStart)
                  ),
                  "Start when full"
                ),
                if (inIt) emptyNode
                else
                    p(
                      idAttr := "auto-start-why",
                      cls := "detail",
                      "Always, for a match you will not play in: nobody would be there to start it."
                    )
              )
          },
          busyButton(
            "Create Challenge",
            disabledWhen = timeLimit.signal
                .combineWith(live.signal, ownCharacter.signal, seated, inviteeCharacter.signal)
                .map { case (limit, isLive, own, inIt, asked) =>
                    most < least || limitProblem(limit, isLive).isDefined ||
                    // A character game's challenger plays as one of their characters.
                    (characterGame && inIt && own.isEmpty) ||
                    // And its invitee is asked through one of theirs: until one is chosen there is nothing to send.
                    (characterGame && invitee.isDefined && asked.isEmpty)
                }
          ) { busy =>
              val request = ChallengeRequest(
                characterId = if (characterGame && isSeatedNow) ownCharacter.now() else None,
                isPlaying = isSeatedNow,
                poolSize = Some(seats.now()),
                roundDuration = durationOf(timeLimit.now(), timeLimitUnit.now()),
                gameSettings = settingsOf(parameters.now()),
                isLive = live.now(),
                isFriendly = !administers.now() || friendly.now(),
                isPublic = isPublic.now(),
                start = startOf(plannedStart.now()),
                isAutoStarted = autoStart.now() || !isSeatedNow,
                isOpen = invitee.isEmpty,
                invitations = if (characterGame) Nil else invitee.map(_.playerId).toList,
                characterInvitations = if (characterGame) inviteeCharacter.now().toList else Nil
              )
              // Taken before the request, and checked before the store is written: this callback closes the form,
              // clears the invitee and re-reads a game's challenges, and all three of those belong to whoever was on
              // this screen when the button was clicked. `Store.run` drops nothing by design -- a click is always
              // worth an answer -- so the guard belongs here, which is what `currentSignIn` is visible outside the
              // store for.
              val signIn = Store.currentSignIn
              Store.run(ApiClient.createChallenge(game.gameId, request), busy) { _ =>
                  timeLimit.set("")
                  autoStart.set(true)
                  live.set(false)
                  plays.set(true)
                  friendly.set(true)
                  plannedStart.set("")
                  if (Store.isStillSignedInAs(signIn) && Store.page.now() == Store.Page.OneGame(game.gameId)) {
                      // The challenge it was open for now exists and is in the list below it. The invitation went
                      // with it, so the slot is spent.
                      Store.showChallengeForm.set(false)
                      Store.invitee.set(None)
                      Store.refreshChallenges(game.gameId)
                      Store.reloadMyChallenges()
                  }
              }
          }
        )
    }

    /** When to play as the browser's date and time input gives it -- the local time, with no zone -- as an instant, or
      * `None` for blank or anything the browser could not read.
      */
    private[ui] def startOf(raw: String): Option[java.time.Instant] =
        Option(raw.trim).filter(_.nonEmpty).flatMap { local =>
            val millis = new scala.scalajs.js.Date(local).getTime()
            Option.when(!millis.isNaN)(java.time.Instant.ofEpochMilli(millis.toLong))
        }

    /** An instant as the browser's date and time input shows it: the local time, to the minute. */
    private[ui] def localInputOf(at: java.time.Instant): String = {
        val d = new scala.scalajs.js.Date(at.toEpochMilli.toDouble)
        def two(n: Double) = f"${n.toInt}%02d"
        s"${d.getFullYear().toInt}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}"
    }

    /** A time limit as it was typed — `None` for blank, and `None` for anything that is not a positive whole number,
      * which the form treats as not yet a limit rather than as zero.
      *
      * Separate from [[durationOf]] because the two are asked at different moments: whether what has been typed is a
      * number at all is checked on every keystroke, and what it comes to is only wanted when the challenge is made.
      */
    private def amountOf(raw: String): Option[Int] =
        raw.trim match {
            case ""   => None
            case text => text.toIntOption.filter(_ > 0)
        }

    /** What is wrong with the time limit as typed, if anything: it must be a whole number above zero when there is one,
      * and a live match must have one.
      */
    private def limitProblem(raw: String, live: Boolean): Option[String] =
        if (raw.trim.isEmpty) Option.when(live)("A live match needs a time to play.")
        else Option.when(amountOf(raw).isEmpty)("A time limit is a whole number, more than zero.")

    /** A time limit typed in some unit, as a `Duration`. */
    private def durationOf(raw: String, unit: TimeLimitUnit): Option[java.time.Duration] =
        amountOf(raw).map(amount => unit.perUnit.multipliedBy(amount.toLong))

    private def currentPlayer: Signal[Option[Player]] = Store.currentPlayer
}

/** Formatting that has to be readable rather than exact. */
object Format {

    /** A parameter's values in the order a dropdown should offer them.
      *
      * Values are stored as text and come back in no order of their own, so text order is what they fell into — which
      * for numbers is wrong: 3 to 25 rounds would read 10, 11, ... 19, 20, ... 25, 3, 4, ... 9. When every value is a
      * number they are sorted as numbers, low to high. Anything else — board sizes like "3x3", or a mix of words and
      * numbers — is sorted as text, which is the order it was already being shown in.
      */
    def parameterValues(values: Seq[String]): Seq[String] = {
        val numbers = values.map(v => v.trim.toDoubleOption)
        if (values.nonEmpty && numbers.forall(_.isDefined))
            values.zip(numbers.flatten).sortBy((text, number) => (number, text)).map(_._1)
        else values.sorted
    }

    /** `Instant.toString` is ISO-8601 in UTC, which is precise and unpleasant to read. This trims it to the minute and
      * marks it as UTC rather than pretending to know the user's zone — Scala.js has no time-zone database unless one
      * is bundled, and a wrong local time is worse than an explicit UTC one.
      */
    def instant(value: java.time.Instant): String =
        value.toString.replace("T", " ").takeWhile(_ != '.').stripSuffix("Z") + " UTC"

    /** The date alone, for a time whose hour is of no interest — when a match was completed reads as a day in a
      * history, not as a deadline. No UTC marker: a bare date carries none of the precision that would make the zone
      * worth mentioning.
      */
    def date(value: java.time.Instant): String =
        value.toString.takeWhile(_ != 'T')

    /** What is left on a clock, as a clock reads: `4:31`, or `1:02:09` once there is an hour of it. Whole seconds,
      * rounded up, so a countdown reaches "0:00" as the time runs out rather than a second before it.
      *
      * Nothing left is said in words rather than as `0:00`, which would go on being displayed however long the turn
      * stayed unplayed and would read as time still on the clock.
      */
    def remaining(millis: Long): String =
        if (millis <= 0) "time is up"
        else {
            val seconds = (millis + 999) / 1000
            val (hours, minutes, secs) = (seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            val clock =
                if (hours > 0) f"$hours:$minutes%02d:$secs%02d"
                else f"$minutes:$secs%02d"
            s"$clock left"
        }

    /** Time spent, as a clock reads it — `4:31`, or `1:02:09` once there is an hour of it.
      *
      * The same shape as [[remaining]] without its trailing word, since a total at the end of a match is not counting
      * towards anything and "left" would be wrong. Zero is `0:00` here rather than a phrase: in a table of times it is
      * a time like the others, and it means the player never moved.
      */
    /** A seat's Elo rating as its match began, and the match's change to it when it was rated: "Elo 1500 (+16)". The
      * change rather than where it ended up, because that is what this match did — the player's rating by the time it
      * finished may also carry their other matches. A minus sign that reads out as one, rather than a hyphen.
      */
    def elo(start: Int, delta: Option[Int]): String =
        delta.fold(s"Elo $start") { d =>
            val signed = if (d > 0) s"+$d" else if (d < 0) s"\u2212${-d}" else "\u00b10"
            s"Elo $start ($signed)"
        }

    def spent(value: java.time.Duration): String = {
        val seconds = value.getSeconds
        val (hours, minutes, secs) = (seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        if (hours > 0) f"$hours:$minutes%02d:$secs%02d" else f"$minutes:$secs%02d"
    }

    /** A time limit, in the unit it was offered in.
      *
      * Said back the way the challenger said it: 48 hours and 2 days are the same offer, and which of them to show is
      * not a calculation but a fact that travelled with the challenge. The unit is only ignored when it does not divide
      * the limit evenly — an API caller can set 90 seconds and call it minutes — in which case the largest unit that
      * does divide it is used, which is what every limit was displayed in before the unit was recorded.
      */
    def duration(value: java.time.Duration, unit: TimeLimitUnit): String = {
        val perUnit = unit.perUnit.getSeconds
        val chosen = if (perUnit > 0 && value.getSeconds % perUnit == 0) unit else TimeLimitUnit.bestFor(value)
        val amount = value.getSeconds / chosen.perUnit.getSeconds
        // The enum's label is plural, which is what it is nearly always read as; one of anything is
        // the exception and drops the 's'.
        val label = if (amount == 1) chosen.label.stripSuffix("s") else chosen.label
        if (chosen == TimeLimitUnit.Minutes && value.getSeconds % 60 != 0)
            s"${value.getSeconds} second${if (value.getSeconds == 1) "" else "s"}"
        else s"$amount $label"
    }

    /** A score reported by a game engine, which may be any JSON value.
      *
      * Strings are unquoted and whole numbers lose their `.0`, since upickle reads every JSON number as a Double and
      * "moves: 5.0" reads as a mistake. Anything structured is rendered as the JSON it is — a game that reports a
      * nested object is unusual, and showing it raw beats inventing a layout for a shape we cannot know.
      */
    def jsonValue(value: ujson.Value): String = value match {
        case ujson.Str(s)              => s
        case ujson.Num(n) if n.isWhole => n.toLong.toString
        case ujson.Num(n)              => n.toString
        case ujson.Bool(b)             => b.toString
        case ujson.Null                => "—"
        case other                     => other.render()
    }
}
