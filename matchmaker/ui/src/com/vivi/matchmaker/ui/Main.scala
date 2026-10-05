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
        if (Config.current.headerAuth) {
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
      * `disabledWhen` is combined here rather than passed in as another `disabled` binding, since two bindings writing
      * the same property would fight over it.
      */
    private def busyButton(
        label: String,
        classes: Option[String] = None,
        disabledWhen: Signal[Boolean] = Val(false)
    )(action: Var[Boolean] => Unit): HtmlElement = {
        val busy = Var(false)

        button(
          classes.map(cls := _).getOrElse(emptyMod),
          disabled <-- disabledWhen.combineWith(busy.signal).map { case (blocked, waiting) => blocked || waiting },
          // Before the label rather than after it, so the label does not shift as the spinner appears.
          child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
          label,
          // The disabled binding above already refuses a second click; this covers the instant
          // between the click and the flag being seen.
          onClick --> (_ => if (!busy.now()) action(busy))
        )
    }

    /** A form control with a visible caption tied to it.
      *
      * Wrapping rather than `for`/`id`: it needs no id to be unique across a page that renders the same form more than
      * once. A placeholder is not a label — it is a hint that disappears at the first keystroke, and leaves a screen
      * reader with an unnamed box — so every field that had only a placeholder gets one of these instead.
      */
    private def field(caption: String, control: HtmlElement): HtmlElement =
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
    private def listing[A](items: Signal[Seq[A]], fetching: Signal[Boolean])(
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
    private def refreshableSection(
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
    private def refreshableSection(
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
              disabled <-- refreshing.signal,
              span(aria.hidden := true, "\u21bb"),
              onClick --> (_ => refresh(refreshing, reload))
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
     * leave a match that now exists off a list until somebody asked again. The refresh button is
     * disabled while its section is refreshing, so the repeated-click case it guarded against
     * cannot arise from there anyway.
     *
     * Two reloads can share the one flag — a cross-refresh landing while the player has the
     * section's own button in flight, or the two challenge lists that are refreshed together — so
     * the flag is cleared by the last of them rather than the first. The flag is not just the
     * dimming: it also drives `aria.busy` and the refresh button's `disabled`, and clearing it
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
        div(
          cls := "header",
          h1("Matchmaker"),
          // Unmissable, because a page that looks like the real thing but authenticates nobody is
          // exactly the page you do not want to be confused about.
          if (Config.current.headerAuth)
              div(cls := "banner", s"local mode — signed in as ${Config.current.localExternalId}, no authentication")
          else emptyNode,
          child <-- Store.player.signal.map {
              case Store.PlayerState.Registered(player) =>
                  div(
                    cls := "who",
                    span(player.nickname),
                    // Where a page like this puts it: top right, next to who you are signed in as.
                    Account.view,
                    if (Config.current.headerAuth) emptyNode
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
                  if (Store.stillSignedInAs(signIn)) {
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
          div(
            cls := "screen",
            child <-- Store.page.signal.map {
                case Store.Page.Home              => mainPage
                case Store.Page.OneGame(gameId)   => gamePage(gameId)
                case Store.Page.NewGame           => newGamePage
                case Store.Page.FindPlayers       => findPlayersPage
                case Store.Page.OnePlayer(player) => playerPage(player)
            }
          )
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
        refresh(refreshingAcceptances, () => Store.reloadAcceptances())
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
          pendingAcceptances(),
          recentlyCompletedSection
        )

    // -------------------------------------------------------------------------
    // Matches
    // -------------------------------------------------------------------------

    /** The challenges this player offered that have filled up and are waiting on them to start.
      *
      * Above "Your turn" because it is the one thing here that nobody else can do and that nothing else will do on its
      * own: a full challenge sits there until its challenger starts it. The section is absent rather than empty when
      * there is nothing to start — an empty call to action at the top of the page is just something to scroll past.
      *
      * Both facts it selects on come from the acceptances response, so this needs nothing loaded per game to draw
      * itself.
      */
    /** Shared by both sections drawn from the acceptances list — "Ready to Start" and "Waiting to Start" are one
      * response split by who may act on it, so either button reloads both and both have to say so. Held at this level
      * rather than passed down because the two sections are siblings on the main page with nothing between them to own
      * it.
      */
    private val refreshingAcceptances: Var[Boolean] = Var(false)

    /* And for the invitations. Its own flag rather than sharing the acceptances' one: they are two
     * requests, and answering an invitation changes both lists -- which is why the actions below
     * refresh both, each through its own flag, rather than dimming one section on the other's
     * behalf. */
    private val refreshingInvitations: Var[Boolean] = Var(false)

    /* The same, for the two match lists. Shared by the home screen's copy of each section and the
     * game screen's, which are never both on screen — and, more to the point, by the sections
     * themselves and by the actions below that change what belongs in them. */
    private val refreshingDue: Var[Boolean] = Var(false)
    private val refreshingActive: Var[Boolean] = Var(false)

    /* And for the completed list, which a refresh can move a row *into*. Shared by the home screen's
     * "Recently Completed" and the game screen's "Your Completed Matches": they are two views of the
     * one list, only one of them is ever on screen, and `reloadCompleted` fills both. */
    private val refreshingCompleted: Var[Boolean] = Var(false)

    /** Reloads the sections a start has just changed, the way their own refresh buttons reload them.
      *
      * Starting a challenge is the moment a match comes into being: it belongs in "Current Matches" immediately, and in
      * "Your Turn" as well if the engine says the first move is this player's. The challenge that became it is no
      * longer waiting for anybody, so it leaves "Ready to Start" and "Waiting to Start" at the same time.
      *
      * Through each section's own flag rather than through `Store.refreshMatches`, so that the lists visibly reload —
      * dimmed, and marked `aria-busy` while they do — instead of quietly growing a row the player has to notice for
      * themselves. The completed list is not touched: a match that has just started has not finished.
      */
    private def reloadAfterStart(): Unit = {
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        refresh(refreshingAcceptances, () => Store.reloadAcceptances())
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
        if (refreshed.completed || refreshed.cancelled) refresh(refreshingCompleted, () => Store.reloadCompleted())
    }

    /** Reloads what accepting or backing out has just changed: the acceptances, which are both "Ready to Start" and
      * "Waiting to Start".
      *
      * Only those. Neither action creates or ends a match — a challenge becomes one when its challenger starts it, and
      * never on its own — so the match lists are the same lists they were, and reloading them would be three requests
      * to be told so.
      *
      * Backing out is the one that most needs to be seen: the row the player just acted on disappears, and a list that
      * silently loses a row is a list that might have lost the wrong one. Dimming it while it reloads says the section
      * was re-read rather than edited in place.
      */
    private def reloadAcceptanceSections(): Unit =
        refresh(refreshingAcceptances, () => Store.reloadAcceptances())

    /** What this player has been invited to and has not answered (V22).
      *
      * First on the page, and absent when there is nothing in it, for the reason "Ready to Start" is both: an
      * invitation is a thing somebody else did that is waiting on this player, not a list they came to check. Every
      * other section here is about a challenge or match they are already in — this is the only one about one they are
      * not, which is also why it names the challenger.
      */
    private def invitationsSection: HtmlElement =
        div(
          /* What the last answer to an invitation was, said once, to whoever is not watching the list.
           *
           * Answering one removes its row, and may remove the whole section with it — which is a
           * change nobody is told about by sight alone being gone. The error banner is `role="alert"`,
           * so a refusal already announces itself; this is the other half, and the half that was
           * silent.
           *
           * A one-line status rather than `aria-live` on the section, which is what was suggested for
           * this. A live region announces its whole subtree: on the first fetch of the session that
           * subtree is a heading, a refresh button and every row, read out unprompted at the moment
           * the player is reading something else — and none of the sibling sections, which appear and
           * disappear just as asynchronously, behaves that way. What `refreshableSection` already
           * does for a list being replaced is mark its body `aria-busy`, which is the mechanism for
           * "this is changing"; a sentence is the mechanism for "here is what you did".
           *
           * Present from the start and empty, because a live region has to exist before the text
           * arrives in it to be announced reliably. `sr-only` because the row vanishing says the same
           * thing to anybody who can see it, and a line of text left behind under a heading that may
           * itself be gone is not worth the clutter. The game is named so that two answers running do
           * not read as one unchanged sentence.
           */
          p(
            cls := "sr-only",
            role := "status",
            aria.live := "polite",
            // From the store, and stamped there with the session that said it: the words outlive
            // both this element and the sign-in that produced them, and neither belongs to whoever
            // is reading the page next. See `Store.invitationsSaid`.
            child.text <-- Store.invitationsSaid
          ),
          child <-- Store.invitations.signal
              .combineWith(
                Store.acceptances.signal,
                // A row needs both lists, so the section's state is the worse of their two states:
                // known when both are, and in need of asking again if either failed.
                Store.known(Store.Fetch.Invitations).combineWith(Store.known(Store.Fetch.Acceptances)).map(_ && _),
                Store.failed(Store.Fetch.Invitations).combineWith(Store.failed(Store.Fetch.Acceptances)).map(_ || _)
              )
              .map { (invitations, acceptances, bothKnown, eitherFailed) =>
                  /* An invitation the player has already accepted is still an invitation: it
                   * outlives the acceptance it led to, so that backing out and changing their mind
                   * again is something they may do -- see `Invitation`. What it is not is something
                   * still to answer, and both buttons on such a row are refused with a 409: `accept`
                   * because one player may hold one seat, and `reject` because the invitation is what
                   * permits the seat they are sitting in.
                   *
                   * So it is filtered out here rather than by the query, which would have to forget
                   * the invitation to forget the row. The same shape `challengePanel` uses to drop a
                   * challenge this player has accepted from the open list, and for the same reason:
                   * what is left is what they can still act on.
                   *
                   * It comes back if they back out, which is right -- they are invited again in the
                   * only sense that matters, and the acceptance leaving the list is what says so. */
                  val accepted =
                      acceptances.map(pending => (pending.acceptance.gameId, pending.acceptance.challengeId)).toSet
                  val unanswered = invitations.filterNot(invited =>
                      accepted.contains((invited.invitation.gameId, invited.invitation.challengeId))
                  )

                  /* Three states, not two, and emptiness tells none of them apart.
                   *
                   * A list that failed leaves nothing held and nothing in flight, so drawing the rows
                   * from what is held would put an empty section over a failure -- or worse, draw rows
                   * filtered against an acceptance list that never arrived, which is to filter against
                   * a blank and offer buttons that can only be refused.
                   *
                   * So: if either list failed, the section stands with a line saying to ask again, and
                   * its refresh button is what asks. It is also the *only* button on the page that
                   * reloads the acceptances when that list came back empty, since the sections drawn
                   * from that list are absent when it is empty, their own buttons with them -- hiding
                   * this one would leave a failure there unrecoverable without reloading the page,
                   * which is what these buttons exist to avoid.
                   *
                   * Then nothing at all until both have answered, which is a page still loading. And
                   * nothing once they have if there is nothing left to answer: an invitation accepted
                   * on a challenge nobody has started stays in the response on purpose, so a heading
                   * over "you have answered all of these" is a section about nothing to do. */
                  if (eitherFailed)
                      refreshableSection(
                        "You Have Been Invited",
                        refreshingInvitations,
                        () => Store.reloadInvitationsWithAcceptances(),
                        subsection = false
                      )(
                        p(cls := "empty", "Couldn't load your invitations just now. Refresh to try again.")
                      )
                  else if (!bothKnown || unanswered.isEmpty) emptyNode
                  else
                      refreshableSection(
                        "You Have Been Invited",
                        refreshingInvitations,
                        // Both lists, because which invitations are still to answer is a question
                        // about both: an invitation accepted in another tab stays in the invitations
                        // response on purpose, so reloading that alone leaves its row exactly where it
                        // was.
                        () => Store.reloadInvitationsWithAcceptances(),
                        subsection = false
                      )(ul(unanswered.map(invitationRow)))
              }
        )

    /** One invitation: who asked, to what, and as what — then the two answers.
      *
      * Accept is offered here only when this row holds everything an acceptance needs: the seat was named in the
      * invitation, and the game is not one played through characters. Otherwise the choice belongs on the game's own
      * screen, which is where the free roles and this player's characters are loaded — so the button goes there instead
      * of guessing at either. Declining needs neither, and is offered on every row.
      *
      * Every fact it decides on comes from the response, and none from `Store.games`. That list holds the *active*
      * games, and an invitation outlives its game being deactivated — `active` decides what is listed, not what may be
      * accepted — so a lookup there would quietly withdraw the Accept button from a challenge the server would still
      * honour, and only for the invitations least likely to be noticed.
      */
    private def invitationRow(invited: ChallengeInvitation): HtmlElement = {
        val invitation = invited.invitation
        // A character game's invitation names the character (V25), so the acceptance can name it too;
        // one that somehow arrives without it is sent to the game screen. A role-less invitation is an
        // offer of any free seat, and which are free is not in this response.
        val acceptableHere =
            invitation.gameRoleId.isDefined && (invited.gameType != GameType.Character || invited.character.isDefined)

        li(
          cls := "row",
          div(cls := "title", invited.message),
          div(
            cls := "detail",
            invited.character.fold(s"${invited.challengerNickname} invited you to ${invited.gameName}")(character =>
                s"${invited.challengerNickname} invited ${character.name} to ${invited.gameName}"
            )
          ),
          div(
            cls := "detail",
            invited.roleName.fold("as any seat that is free")(role => s"as $role")
          ),
          if (acceptableHere)
              busyButton("Accept") { busy =>
                  // `get` is safe under `acceptableHere`, which is what this branch is selected by.
                  val role = invitation.gameRoleId.get
                  Store.run(
                    ApiClient
                        .accept(invitation.gameId, invitation.challengeId, invited.character.map(_.characterId), role),
                    busy,
                    invitationGone(invited)
                  ) { _ =>
                      acceptedInvitation(invited)
                  }
              }
          else
              button(
                tpe := "button",
                cls := "link",
                "Open the game",
                onClick --> (_ => Store.show(Store.Page.OneGame(invitation.gameId)))
              ),
          busyButton("Decline", classes = Some("link")) { busy =>
              Store.run(
                invited.character.fold(ApiClient.rejectInvitation(invitation.gameId, invitation.challengeId))(
                  character =>
                      ApiClient
                          .rejectCharacterInvitation(invitation.gameId, invitation.challengeId, character.characterId)
                ),
                busy,
                invitationGone(invited)
              ) { _ =>
                  declinedInvitation(invited)
              }
          }
        )
    }

    /** Everything accepting an invitation from this list can have changed, reloaded the way each section's own button
      * reloads it.
      *
      * Four lists, not two. The acceptance may have taken the last required seat of an auto-starting challenge, in
      * which case the server has already created the match, this player is in it, and it may be their turn in it. So
      * the match lists are re-read as well, through `reloadAfterStart` — the same set a Start reloads, because this may
      * *have been* the start.
      *
      * Unconditionally, unlike `openChallengeRow`, which works out whether its own acceptance started the match and
      * reloads accordingly. That row can: it holds the challenge, its roster and its `autoStart` flag. This one holds
      * none of them — a `ChallengeInvitation` is an invitation and the names needed to draw it — and the response says
      * only that the acceptance was made. Three reloads that find nothing new cost a dimmed second; a match missing
      * from "Current Matches" costs the player a turn they did not know was theirs.
      *
      * Dimmed and re-read rather than edited in place, for the reason `reloadAcceptanceSections` says: a list that
      * silently loses a row is a list that might have lost the wrong one.
      */
    private def acceptedInvitation(invited: ChallengeInvitation): Unit = {
        // Named, so that two answers running do not read as one sentence that never changed — a live
        // region announces a change in its text, and "Invitation accepted." twice over is no change.
        Store.sayAboutInvitations(
          s"Invitation to ${invited.gameName} accepted."
        )
        reloadAfterAnsweringInvitation(invited)
    }

    /** The reloads an answered invitation calls for, without saying anything about how it went.
      *
      * Shared by the success path above and the failure path below, which want the same lists re-read and emphatically
      * not the same thing said: a refusal is not an acceptance, and the banner the server wrote is the account of it.
      */
    private def reloadAfterAnsweringInvitation(invited: ChallengeInvitation): Unit = {
        // The invitations alone, not `reloadInvitationsWithAcceptances`: `reloadAfterStart` below
        // re-reads the acceptances, and asking for the same list twice in one beat is two requests
        // whose answers race -- the store drops the loser, so the only thing the second one buys is
        // the section dimming twice.
        refresh(refreshingInvitations, () => Store.reloadInvitations())
        reloadAfterStart()
        // The challenge itself has changed — a seat taken — so the game's list is stale if that screen
        // is the one behind this.
        if (Store.page.now() == Store.Page.OneGame(invited.invitation.gameId))
            Store.refreshChallenges(invited.invitation.gameId)
    }

    /** And what declining changes, which is less: the invitation is gone, and the seat it held is free for whoever else
      * may accept.
      *
      * No match can have come of it and no acceptance changed — declining is the answer of somebody who has not
      * accepted, which the server enforces — so the lists that would say otherwise are left alone rather than dimmed
      * for nothing.
      */
    private def declinedInvitation(invited: ChallengeInvitation): Unit = {
        val invitation = invited.invitation
        Store.sayAboutInvitations(s"Invitation to ${invited.gameName} declined.")
        refresh(refreshingInvitations, () => Store.reloadInvitationsWithAcceptances())
        if (Store.page.now() == Store.Page.OneGame(invitation.gameId))
            Store.refreshChallenges(invitation.gameId)
    }

    /** What to do when answering an invitation is refused because it is no longer there to answer.
      *
      * The shape `alreadyStarted` has, and for the same reason: this row is on screen because the list was fetched
      * before the challenger withdrew it, or started the challenge, or before this player accepted from another tab.
      * 404 and 409 are both the server saying so, so both are treated as the news they are and the lists are re-read.
      *
      * The banner is left exactly as the server wrote it, which `alreadyStarted` does not do. There, one thing can have
      * happened — the match has started — and it can be said in better words than the ids the message names. Here
      * several can: the challenge is gone, the invitation was withdrawn, this player has already accepted, somebody
      * else took the seat. Only the server knows which, and a single sentence covering all of them would be wrong about
      * most.
      *
      * Anything else is left as `Store.run` reported it. A 5xx says nothing about whether the invitation is still
      * there, and a list that looked corrected on the strength of one would be worse than a plain failure.
      *
      * The accepting reload is used for either button, because a refusal says less than a success does: a 409 on a
      * decline can mean this player has already accepted it, or that the challenge has been started, and both of those
      * are news for the match lists. The wider reload is the one that cannot be wrong here.
      *
      * The server's account of it is left standing, and nothing here has to arrange that: a reload is not an action,
      * and a fetch succeeding does not clear an action's banner — see `Store.reportProblem`. Which matters here more
      * than anywhere, because every reload this starts is expected to succeed; they succeed *because* the refusal was
      * real.
      */
    private def invitationGone(invited: ChallengeInvitation)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) =>
            // The reloads only. Nothing is announced: the banner is the message, and it is the
            // server's own -- several things produce a 409 on these buttons and only it knows which.
            // This used to go through `acceptedInvitation`, which announced an acceptance that had
            // just been refused, contradicting the banner beside it.
            reloadAfterAnsweringInvitation(invited)
        case _ => ()
    }

    /* Absent altogether when there is nothing ready to start, heading and refresh button with it.
     *
     * Every other list here says so when it is empty, because "no matches" is an answer to a
     * question somebody asked. This one is not a question anybody asks: a challenge becomes
     * startable when the last player accepts it, which is something that happens *to* the
     * challenger rather than something they came to check. So it is a prompt, and a prompt with
     * nothing to prompt is noise on every screen that carries it.
     *
     * Nothing is stranded by the button going with it: the same acceptances are what "Waiting to
     * Start" lists, and its refresh reloads them — through the same `refreshingAcceptances` flag,
     * so a reload from there brings this section back the moment there is one to bring back. */
    private def readyToStartSection(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.acceptances.signal
              .combineWith(Store.games.signal, currentPlayer)
              .map { (acceptances, games, player) =>
                  val mine = player.toSeq.flatMap { me =>
                      acceptancesIn(game)(acceptances).filter(p => p.readyToStart && p.challenger == me.playerId)
                  }
                  if (mine.isEmpty) emptyNode
                  else {
                      val namesById = games.map(game => game.gameId -> game.displayName).toMap
                      refreshableSection(
                        "Ready to Start",
                        refreshingAcceptances,
                        () => Store.reloadAcceptances(),
                        subsection = false
                      )(ul(mine.map(pending => readyToStartRow(pending, namesById.get(pending.acceptance.gameId)))))
                  }
              }
        )

    private def readyToStartRow(pending: PendingAcceptance, gameName: Option[String]): HtmlElement = {
        val acceptance = pending.acceptance

        li(
          cls := "row",
          div(cls := "title", gameName.getOrElse(s"game ${acceptance.gameId.value}")),
          div(cls := "detail", "every role is taken"),
          busyButton("Start") { busy =>
              Store.run(ApiClient.startChallenge(acceptance.gameId, acceptance.challengeId), busy) { _ =>
                  reloadAfterStart()
                  // The challenge is no longer open, so the game's list is stale if it is on screen.
                  if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                      Store.refreshChallenges(acceptance.gameId)
              }
          },
          // Every seat being taken is no promise the challenger still wants the match, so the way out of it stays
          // here alongside the start.
          busyButton("Cancel") { busy =>
              Store.run(
                ApiClient.deleteChallenge(acceptance.gameId, acceptance.challengeId),
                busy,
                cancelStale(acceptance.gameId)
              ) { _ =>
                  reloadAcceptanceSections()
                  if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                      Store.refreshChallenges(acceptance.gameId)
              }
          }
        )
    }

    /** What a refused cancel of a ready challenge says about the lists on screen, in the shape of [[invitationStale]].
      *
      *   - 409 is a start that got there first: there is a match now, and the challenger is in it, so their match lists
      *     are stale along with the acceptances this row came from.
      *   - 404 is the challenge already gone. Nothing was started by that, so only the lists that showed the challenge
      *     are stale.
      *
      * Either way the game screen's challenge list, when it is up, showed the challenge too. Anything else -- a 5xx, a
      * dropped connection -- says nothing about the challenge, and is left as `Store.run` reported it.
      */
    private def cancelStale(gameId: GameId)(failure: Throwable): Unit = {
        def onItsPage(): Unit = if (Store.page.now() == Store.Page.OneGame(gameId)) Store.refreshChallenges(gameId)
        failure match {
            case ApiError(409, _) =>
                reloadAfterStart()
                onItsPage()
            case ApiError(404, _) =>
                reloadAcceptanceSections()
                onItsPage()
            case _ => ()
        }
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

    /* The same slice, over the acceptances. */
    private def acceptancesIn(game: Option[Game])(acceptances: Seq[PendingAcceptance]): Seq[PendingAcceptance] =
        game.fold(acceptances)(g => acceptances.filter(_.acceptance.gameId == g.gameId))

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
              showResult = false
            )
          )
        )

    /** "Also shows pending acceptances with option to back out."
      *
      * These are challenges the player has accepted that have not yet filled up into a match, which is why they appear
      * beside the matches rather than in them. The game name is looked up from the games list; an acceptance whose game
      * is not in that list — an inactive game, say — still shows, named by its id rather than dropped.
      *
      * The ones this player could start right now are left out: they have their own section at the top of the page, and
      * listing them twice would offer the same Start button in two places.
      */
    /* Absent when there is nothing waiting, like "Ready to Start" above and for the same reason:
     * what a player has accepted and is waiting on is news when there is some, and a heading over
     * a sentence saying there is none the rest of the time.
     *
     * This is the whole of what the section is — the Create Challenge button belongs to the
     * challenges panel further down the game page, which is a different section and is not
     * touched by this. */
    private def pendingAcceptances(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.acceptances.signal.combineWith(Store.games.signal, currentPlayer).map {
              (acceptances, games, player) =>
                  val waiting =
                      acceptancesIn(game)(acceptances)
                          .filterNot(p => p.readyToStart && player.exists(_.playerId == p.challenger))
                  if (waiting.isEmpty) emptyNode
                  else {
                      val namesById = games.map(game => game.gameId -> game.displayName).toMap
                      refreshableSection(
                        "Waiting to Start",
                        refreshingAcceptances,
                        () => Store.reloadAcceptances(),
                        subsection = true
                      )(ul(waiting.map(pending => acceptanceRow(pending, namesById.get(pending.acceptance.gameId)))))
                  }
          }
        )

    /** What to do when backing out is refused because the challenge has already been started.
      *
      * The server answers 409 there (see `AcceptanceService.delete`): the roster is participants in a match the engine
      * has been told about, so there is no acceptance left to withdraw. The row that was clicked is therefore not a row
      * at all any more — it is on screen only because the list was fetched before the challenger started the match —
      * and leaving it there invites the player to click a button that can never succeed.
      *
      * So this treats the refusal as the news it is: the same reload a start does, which drops the challenge from
      * "Waiting to Start" (`listForPlayer` excludes a started challenge) and picks the new match up in "Current
      * Matches", and in "Your Turn" if the first move is this player's. The banner is rewritten too — the server's
      * message names ids, where what the player needs to know is that the match exists and they are in it.
      *
      * Any other failure is left exactly as `Store.run` reported it: a 5xx or a dropped connection says nothing about
      * whether the acceptance is still there, and reloading on those would replace a plain "that failed, try again"
      * with a list that looks corrected and might not be.
      */
    private def alreadyStarted(acceptance: Acceptance)(failure: Throwable): Unit = failure match {
        case ApiError(409, _) =>
            // Raised before the reloads rather than after them, which is where it used to be: the
            // reloads are asynchronous, so one landing later cleared this message however late it was
            // written. What keeps it now is that it is an action's banner and those are fetches — see
            // `Store.reportProblem`.
            Store.reportProblem("That match has already started, so there is nothing left to back out of.")
            reloadAfterStart()
            // A started challenge is no longer offered either, so the game screen's list is as stale as
            // this row was — the same refresh the success path does, for the same reason.
            if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                Store.refreshChallenges(acceptance.gameId)
        case _ => ()
    }

    private def acceptanceRow(pending: PendingAcceptance, gameName: Option[String]): HtmlElement = {
        val acceptance = pending.acceptance

        li(
          cls := "row",
          div(cls := "title", gameName.getOrElse(s"game ${acceptance.gameId.value}")),
          // A challenge this player could start is not in this list at all — it is in "Ready to
          // start" above. What is left is either still filling up, or full and somebody else's to
          // start, which is worth saying rather than leaving them looking for a button that is not
          // theirs.
          if (pending.readyToStart) div(cls := "detail", "every role is taken — waiting for the challenger to start it")
          else div(cls := "detail", "accepted, waiting for the other players"),
          child <-- currentPlayer.map {
              case None => emptyNode
              // Their own challenge is not one to back out of -- the server refuses it, since it would
              // leave the challenge with no seat for its challenger -- but one to delete, from the game.
              case Some(player) if player.playerId == pending.challenger =>
                  div(cls := "detail", "your challenge: delete it from the game's page to call it off")
              case Some(player) =>
                  busyButton("Back out", classes = Some("link")) { busy =>
                      Store.run(
                        ApiClient.withdraw(acceptance.gameId, acceptance.challengeId, player.playerId),
                        busy,
                        alreadyStarted(acceptance)
                      ) { _ =>
                          reloadAcceptanceSections()
                          // The challenge is open again, so the game's list is stale if it is on screen.
                          if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                              Store.refreshChallenges(acceptance.gameId)
                      }
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
            matchTable(_, showGame = true, showParameters = false, outcome = playerOutcome, view = reviewControl)
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
                      disabled <-- busy,
                      caption,
                      onClick --> (_ => move(to))
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
              onSubmit.preventDefault --> (_ => runPlayerSearch()),
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
                disabled <-- searchingPlayers.signal
                    .combineWith(Store.playerSearch.signal)
                    .map { case (busy, typed) => busy || typed.trim.isEmpty },
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
              if (result.more)
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

    /** Asking this player for a game (V22): pick the game, and go and compose the challenge.
      *
      * Two steps rather than one, because a challenge is more than who it is for — it has a message, a clock, a seat
      * for its challenger — and all of that lives in the form on the game's screen. So this carries the one thing that
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
                    // Rebuilt per game, so the choice of challenge and seat starts over when the game does.
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
      * Lists the reader's own challenges there that have not started. In a plain game one that already invites them is
      * left out, since a second invitation is refused; in a character game the character picker leaves out whichever of
      * their characters are already asked, and says so when none is left.
      */
    private def addToExisting(game: Game, invitee: PublicPlayer): HtmlElement = {
        val picked = Var(Option.empty[ChallengeId])
        val seat = Var(Option.empty[GameRoleId])
        val character = Var(Option.empty[CharacterId])

        div(
          onMountCallback(_ => Store.refreshChallenges(game.gameId)),
          child <-- currentPlayer.combineWith(Store.challengesByGame.signal).map {
              case (Some(me), byGame) if byGame.contains(game.gameId) =>
                  val mine = byGame(game.gameId).filter(summary =>
                      summary.challenge.challenger == me.playerId &&
                          (game.gameType == GameType.Character ||
                              !summary.invitations.exists(_.playerId == invitee.playerId))
                  )
                  if (mine.isEmpty) emptyNode
                  else {
                      // Kept when the list is re-read, as long as the challenge is still in it.
                      if (!picked.now().exists(id => mine.exists(_.challenge.challengeId == id)))
                          picked.set(mine.headOption.map(_.challenge.challengeId))
                      div(
                        h4(s"Or add ${invitee.nickname} to a challenge you have already offered"),
                        field(
                          "Challenge",
                          select(
                            onChange.mapToValue --> { raw =>
                                seat.set(None)
                                picked.set(
                                  raw.toLongOption
                                      .map(ChallengeId.apply)
                                      .filter(id => mine.exists(_.challenge.challengeId == id))
                                )
                            },
                            value <-- picked.signal.map(_.map(_.value.toString).getOrElse("")),
                            // An unnamed challenge is numbered by where it is in the list, never by its id.
                            mine.zipWithIndex.map((summary, i) =>
                                option(
                                  value := summary.challenge.challengeId.value.toString,
                                  if (summary.challenge.message.trim.nonEmpty) summary.challenge.message
                                  else s"unnamed challenge ${i + 1}"
                                )
                            )
                          )
                        ),
                        child <-- picked.signal.map(
                          _.flatMap(id => mine.find(_.challenge.challengeId == id)) match {
                              case Some(summary) =>
                                  inviteCandidate(
                                    game,
                                    summary,
                                    invitee,
                                    seat,
                                    character,
                                    offerableSeats(game, summary),
                                    summary.invitedCharacters.map(_.invitation.characterId).toSet
                                  )()
                              case None => emptyNode
                          }
                        )
                      )
                  }
              case _ => emptyNode
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
      * Open Challenges" on the game's screen, so they can be started, deleted, or have their invitations changed from
      * here.
      *
      * Addressed to them means a player invitation in a plain game, and in a character game an invitation to any
      * character they own now -- which is why their characters are fetched: an invitation names the character, and the
      * owner is whoever holds it at the moment (V25). Nothing at all on the reader's own page.
      */
    private def offeredChallenges(game: Game, invitee: PublicPlayer): HtmlElement = {
        val characterGame = game.gameType == GameType.Character
        // Their characters in this game; `None` until known (and always `Some(empty)` for a plain game).
        val theirs = Var(if (characterGame) Option.empty[Set[CharacterId]] else Some(Set.empty[CharacterId]))
        // Which mount is current, as in `characterPicker`: an answer for a panel since closed is dropped.
        var mount = 0

        div(
          onMountCallback { _ =>
              mount += 1
              val asked = mount
              Store.refreshChallenges(game.gameId)
              if (characterGame) {
                  val signIn = Store.currentSignIn
                  ApiClient.characterNames(game.gameId, invitee.playerId).onComplete {
                      case scala.util.Success(names) if asked == mount && Store.stillSignedInAs(signIn) =>
                          theirs.set(Some(names.map(_.characterId).toSet))
                      case scala.util.Failure(_) if asked == mount && Store.stillSignedInAs(signIn) =>
                          theirs.set(Some(Set.empty))
                      case _ => ()
                  }
              }
          },
          onUnmountCallback(_ => mount += 1),
          child <-- currentPlayer
              .combineWith(Store.challengesByGame.signal, theirs.signal)
              .map {
                  case (Some(me), _, _) if me.playerId == invitee.playerId => emptyNode
                  case (Some(me), byGame, Some(characters)) if byGame.contains(game.gameId) =>
                      val offered = byGame(game.gameId).filter { summary =>
                          summary.challenge.challenger == me.playerId &&
                          (summary.invitations.exists(_.playerId == invitee.playerId) ||
                              summary.invitedCharacters.exists(c => characters.contains(c.invitation.characterId)))
                      }
                      div(
                        h3(s"Your Challenges to ${invitee.nickname}"),
                        if (offered.isEmpty) p(cls := "empty", "None waiting.")
                        else ul(offered.map(myChallengeRow(game, _)))
                      )
                  case _ => p(cls := "detail", "Loading your challenges…")
              }
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
        if (summary.completed && summary.archiveExpired) archiveExpiredNote
        else if (summary.completed)
            busyButton("Review game", classes = Some("link")) { busy =>
                Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                    m.playUrl.orElse(m.publicUrl) match {
                        case Some(url) => openSignedIn(url)
                        // Found expired by this very request: the list was read before it was.
                        case None if m.archiveExpired =>
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
        if (summary.archiveExpired) archiveExpiredNote
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
        showResult: Boolean = true
    ): HtmlElement = {
        val parameters =
            if (showParameters) matches.flatMap(_.parameters.map(_.displayName)).distinct else Seq.empty
        val columns = (if (showGame) 1 else 0) + (if (showResult) 1 else 0) + 4 + parameters.size

        def matchRow(summary: MatchSummary): Seq[HtmlElement] = {
            // Everyone else in it, each a way to their page; a match played alone has nobody to name.
            val against: Modifier[HtmlElement] =
                if (summary.opponents.isEmpty) "nobody"
                else
                    summary.opponents.zipWithIndex.map { (opponent, i) =>
                        span(if (i > 0) ", " else emptyNode, playerLink(opponent))
                    }
            val ended: Modifier[HtmlElement] =
                dated(summary) match {
                    case Some(when) =>
                        // The day, with the instant it was trimmed from kept machine-readable.
                        timeTag(
                          htmlAttr("datetime", com.raquo.laminar.codecs.StringAsIsCodec) := when.toString,
                          Format.date(when)
                        )
                    case None => if (summary.cancelled) "cancelled" else ""
                }
            val told = outcome(summary)
            Seq(
              tr(
                cls := "match",
                role := "row",
                if (showGame) td(role := "cell", cls := "game", summary.gameName) else emptyNode,
                // Who it was against heads its row, for a screen reader.
                th(scopeAttr := "row", role := "rowheader", span(cls := "opponent", against)),
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
                      if (summary.friendly) span(cls := "mark", aria.hidden := true, "✓") else emptyNode,
                      span(cls := "sr-only", if (summary.friendly) "friendly" else "rated")
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
                  if (summary.cancelled) div(cls := "detail", "Cancelled by its creator.") else emptyNode,
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
                if (showGame) heading("Game") else emptyNode,
                heading("Opponent"),
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

    /** A finished match's Elo cell: the change it made to the player's rating, signed, or "Friendly" -- with what that
      * means a tap away -- for a match that changed nobody's. Empty for one with no change recorded: called off, or
      * finished before ratings were kept. Labelled for the narrow layout, which has no heading row to show.
      */
    private def eloCell(summary: MatchSummary): HtmlElement =
        if (summary.friendly)
            td(
              role := "cell",
              cls := "elo",
              withTip(freshTipId("friendly-tip"), "friendly", friendlyMeaning)(span("Friendly"))
                  .amend(cls := "inline-tip")
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
          if (summary.friendly) friendlyLabel() else emptyNode,
          matchParameters(summary),
          summary.due.map(countdown).getOrElse(emptyNode),
          timeLimitDetail(summary.timeLimit, summary.timeLimitKind, summary.timeLimitUnit, summary.live),
          playControls(summary),
          matchNotifications(summary)
        )

    /** How a match the caller is playing stands, under its row in "Current Matches": whose turn it is, the clock on
      * that turn and the rule behind it, and what they want to hear about the match.
      */
    private def playingStatus(summary: MatchSummary): Modifier[HtmlElement] =
        div(
          // `pending` means it is this player's turn: it is the flag the "Your Turn" list selects on.
          if (summary.pending) div(cls := "pending", "your turn")
          // Matchmaker is never told whose turn it is in a live match -- the game keeps that -- so
          // "waiting for the other players" would be a guess, and usually a wrong one.
          else if (summary.live) div(cls := "detail", "being played live: open the game to see whose turn it is")
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
          timeLimitDetail(summary.timeLimit, summary.timeLimitKind, summary.timeLimitUnit, summary.live),
          matchNotifications(summary)
        )

    /** The caller's way into a match still being played, and the way out of it for its creator. */
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
          // Only the creator's. The engine is not told — its board stays playable — so the
          // confirmation says what actually happens.
          if (summary.isCreator)
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
            disabled <-- busy.signal,
            child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
            child.text <-- shown.signal.map(if (_) "Hide notifications" else "Notifications"),
            onClick --> { _ =>
                if (shown.now()) shown.set(false)
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
              if (summary.cancelled) "Called off before it finished." else "No result was reported."
            )
          )(rows =>
              div(
                // Said once above the table rather than on each line: a forfeit is how the match
                // ended, which is one fact about the match, not a separate fact about each seat.
                // The lines below still say which of the two things it meant for each player.
                if (rows.exists(_.forfeit))
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
                        if (!row.forfeit) emptyNode
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
                        pendingAcceptances(Some(game)),
                        gameChallenges(game),
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
                  case scala.util.Success(all) => found.set(Some(Right(all.filterNot(_.active))))
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
      * the game engine is configured with. Roles are not optional extras — every acceptance names one, so a game with
      * none is a game nothing can be offered for, and the server refuses it.
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
        optional: Var[Boolean]
    )
    private case class ParameterDraft(
        name: Var[String],
        displayName: Var[String],
        values: Var[String],
        default: Var[String]
    )

    private def emptyRole: RoleDraft = RoleDraft(GameRoleId.unassigned, Var(""), Var(""), Var(false))
    private def emptyParameter: ParameterDraft = ParameterDraft(Var(""), Var(""), Var(""), Var(""))

    private def draftOf(role: GameRole): RoleDraft =
        RoleDraft(role.gameRoleId, Var(role.name), Var(role.displayName), Var(role.optional))

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
            "Every seat in a match names a role, so a game needs at least one. An optional role is one " +
                "a match does not wait to see filled before it can start. A role that already exists can " +
                "be renamed but not removed — acceptances and played matches name it, so retire one by " +
                "making it optional."
          )(h4("Roles")),
          children <-- roles.signal.map(_.zipWithIndex.map { (draft, i) =>
              val nameTip = s"$formKey-role-$i-name-tip"
              val displayTip = s"$formKey-role-$i-display-tip"
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
                  input(
                    tpe := "checkbox",
                    controlled(checked <-- draft.optional.signal, onClick.mapToChecked --> draft.optional)
                  ),
                  "optional"
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
              div(
                cls := "row",
                input(
                  aria.label := "parameter name",
                  placeholder := "parameter name",
                  controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)
                ),
                input(
                  aria.label := "display name, what players see; left blank, the name",
                  placeholder := "display name (blank: the name)",
                  controlled(value <-- draft.displayName.signal, onInput.mapToValue --> draft.displayName)
                ),
                input(
                  aria.label := "possible values, comma separated",
                  placeholder := "values, comma separated",
                  controlled(value <-- draft.values.signal, onInput.mapToValue --> draft.values)
                ),
                input(
                  aria.label := "default value",
                  placeholder := "default value",
                  controlled(value <-- draft.default.signal, onInput.mapToValue --> draft.default)
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
            (d.gameRoleId, name, d.optional.now(), displayName)
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
        else
            Right(
              named.map((id, name, optional, displayName) =>
                  GameRole(id, GameId.unassigned, name, optional, displayName)
              )
            )
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
      * that leaves the field blank, and never shown, since nothing sends it back. `active` is asked only of an edit, as
      * "Disable Game": a new game is created active, and an edit may disable it, which takes it out of the menu and
      * every game picker. It hides the game rather than closing it — its challenges, invitations and matches are left
      * as they are, since `active` decides what is listed, not what may be played.
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
        val disabled = Var(existing.exists(!_.active))
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
              } yield (roleModels, parameterModels)

              drafted match {
                  case Left(problem) => Store.reportProblem(problem)
                  case Right((roleModels, parameterModels)) =>
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
                        active = existing.isEmpty || !disabled.now(),
                        roles = roleModels,
                        parameters = parameterModels,
                        // Who the engine is: the name its API key is filed under, which is what a callback
                        // from it is matched against.
                        externalId = engineIdentity.now().trim,
                        timeoutAction = timeoutAction.now(),
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
      * friendly (V36). Every match the player has a seat in, public or not — an admin manages them all — and nothing at
      * all for a reader who is not an admin of the game: not a heading, not a request beyond the one that finds out.
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
                if (asked == mount && Store.stillSignedInAs(signIn)) matches.set(Some(found))
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
        val friendly = Var(m.friendly)
        val busy = Var(false)
        li(
          cls := "row",
          div(cls := "title", if (m.description.trim.nonEmpty) m.description else "an unnamed match"),
          div(cls := "detail", if (m.players.isEmpty) "nobody seated yet" else m.players.mkString(", ")),
          div(
            cls := "detail",
            m.completedAt.map(when => s"completed ${Format.date(when)}").getOrElse {
                if (m.cancelled) "cancelled" else s"started ${Format.date(m.start)}"
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
                        friendly.set(saved.friendly)
                    )
                }
              )
            ),
            "Friendly"
          )
        )
    }

    /** The game's leaderboard (V45), [[Leaderboard.pageSize]] places at a time, best first, for anybody signed in; and
      * for its admins, a box on each row that sets that player's rating, and a form that rates a player who has none
      * yet.
      *
      * Held in the section rather than the store, as [[adminMatchList]] holds its matches and for the same reason: an
      * answer written into the store would rebuild the page this sits in, remount it, and ask again.
      */
    private def ratingsSection(game: Game): HtmlElement =
        div(child <-- currentPlayer.map(_.fold(emptyNode)(player => ratingsList(game, player))))

    private def ratingsList(game: Game, player: Player): HtmlElement = {
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
            def current = asked == request && Store.stillSignedInAs(signIn)
            mineRequest += 1
            val askedMine = mineRequest
            busy.set(true)
            ApiClient
                .standing(game.gameId, player.playerId)
                .map(Right(_))
                .recover {
                    case ApiError(404, _) => Left("You have no rating in this game yet.")
                    case error            => Left(s"Your standing could not be loaded: ${error.getMessage}")
                }
                .foreach(answer =>
                    if (askedMine == mineRequest && Store.stillSignedInAs(signIn)) mine.set(Some(answer))
                )
            ApiClient
                .leaderboard(game.gameId, at)
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

        val administers = Store.administers(game.gameId, player)
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
            "Player Rankings",
            refreshing,
            () => fetch(),
            subsection = false,
            tip = Some(
              s"ratings-tip-${game.gameId.value}" ->
                  (s"Players are ranked by Elo rating, and by rated matches between equal ratings. " +
                      s"Every rated match of this game moves its players' ratings; a friendly one does not. " +
                      s"A player's first rated match starts them at ${EloRating.initial}.")
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
                            caption(cls := "sr-only", s"Player rankings in ${game.name}"),
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
              step("Previous", "Previous 20 ranks", "backward", page.signal.map(_ > 0), _ - 1),
              step("Next", "Next 20 ranks", "forward", board.signal.map(_.exists(_.more)), _ + 1)
            ),
            findPlayer(game, found)
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
          td(standing.rank.fold[Modifier[HtmlElement]](span(aria.label := "not ranked yet", "—"))(_.toString)),
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
    private def findPlayer(game: Game, found: Var[Option[Either[String, Leaderboard]]]): HtmlElement = {
        val prefix = Var("")
        val busy = Var(false)
        val id = s"find-in-rankings-${game.gameId.value}"

        form(
          cls := "search inline-form",
          // Enter in the field submits, as on "Find Players".
          onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
              val typed = prefix.now().trim
              if (typed.nonEmpty)
                  Store.runSignedIn(ApiClient.findInRankings(game.gameId, typed), busy)(answer =>
                      found.set(Some(Right(answer)))
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
                          (if (some.more) " Type more of the nickname to narrow it." else "")
                    )
            }
          )
        )
    }

    /** A player's win–loss–draw record (V46), as the rankings show it: "12 (2)–5 (1)–3", the numbers in parentheses the
      * wins and losses that came by forfeit, and left out when there are none. Said in words to a screen reader, which
      * would otherwise read the dashes and parentheses as punctuation.
      */
    private def recordText(r: MatchRecord): HtmlElement = {
        def byForfeit(n: Int) = if (n > 0) s" ($n)" else ""
        def count(n: Int, one: String, many: String) = s"$n ${if (n == 1) one else many}"
        def spoken(said: String, forfeits: Int) = said + (if (forfeits > 0) s", $forfeits by forfeit" else "")
        span(
          aria.label := List(
            spoken(count(r.wins, "win", "wins"), r.forfeitWins),
            spoken(count(r.losses, "loss", "losses"), r.forfeitLosses),
            count(r.draws, "draw", "draws")
          ).mkString("; "),
          s"${r.wins}${byForfeit(r.forfeitWins)}–${r.losses}${byForfeit(r.forfeitLosses)}–${r.draws}"
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
                case None => span(aria.label := "not ranked yet", "—")
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
                            case _ if !Store.stillSignedInAs(signIn) => scala.util.Success(None)
                            case answered                            => answered.map(Some(_))
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

    /* `characters` is every character this player has in the game, empty for a plain game. Offering a
     * challenge is done as whichever of them the challenger chooses on the form; accepting one is done
     * as whichever of them was invited to it, if any was -- see `openChallengeRow`. */
    private def challengePanel(game: Game, player: Player, characters: Seq[Character[String]]): HtmlElement = {
        val characterIds = characters.map(_.characterId)
        // One flag for both lists, held out here rather than inside either of them: they are two
        // views of one request, and this element is rebuilt when that request answers.
        val refreshingChallenges = Var(false)

        div(
          child <-- Store.challengesByGame.signal.combineWith(Store.acceptances.signal).map { (byGame, acceptances) =>
              byGame.get(game.gameId) match {
                  case None             => p(cls := "empty", "Loading challenges…")
                  case Some(challenges) =>
                      // `ui.txt` asks for the player's own challenges in a separate list, because what you
                      // can do with them is different: delete yours, accept someone else's.
                      val (mine, others) = challenges.partition(_.challenge.challenger == player.playerId)
                      // A challenge this player has already accepted stays open until it fills up, but
                      // offering it again would only produce a duplicate acceptance the service rejects —
                      // so it is dropped from the list rather than shown with an Accept that cannot work.
                      val accepted = acceptances.map(a => (a.acceptance.gameId, a.acceptance.challengeId)).toSet
                      val available =
                          others.filterNot(c => accepted.contains((c.challenge.gameId, c.challenge.challengeId)))
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
                          if (available.isEmpty) p(cls := "empty", "Nobody is waiting for an opponent.")
                          else ul(available.map(openChallengeRow(game, _, characterIds, player.playerId)))
                        )
                      )
              }
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

    private def myChallengeRow(game: Game, summary: ChallengeSummary): HtmlElement = {
        val challenge = summary.challenge
        li(
          cls := "row",
          div(cls := "title", challenge.message),
          div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
          timeLimitDetail(challenge),
          parameterDetail(game, challenge),
          if (challenge.isPublic) div(cls := "detail", "public") else emptyNode,
          if (challenge.friendly) friendlyLabel() else emptyNode,
          // A game's admin offering a match for others to play: said, because nothing else on the row
          // tells it from one they are seated in.
          if (challenge.gameRoleId.isEmpty) div(cls := "detail", "you are not playing in it") else emptyNode,
          // Starting is the challenger's call rather than something that happens on the last
          // acceptance: a game whose remaining roles are optional may be worth starting without
          // them. With a required role nobody has taken the server refuses it outright — so there is
          // no point offering the button, and what the challenger is waiting for is said beside it
          // instead: somebody to play the roles still going begging.
          if (unfilledRoles(game, summary).isEmpty)
              busyButton("Start") { busy =>
                  Store.run(ApiClient.startChallenge(game.gameId, challenge.challengeId), busy) { _ =>
                      Store.refreshChallenges(game.gameId)
                      reloadAfterStart()
                  }
              }
          else div(cls := "detail", s"waiting for ${unfilledRoles(game, summary).map(_.displayName).mkString(", ")}"),
          busyButton("Delete") { busy =>
              Store.run(ApiClient.deleteChallenge(game.gameId, challenge.challengeId), busy)(_ =>
                  Store.refreshChallenges(game.gameId)
              )
          },
          invitedList(game, summary),
          invitePanel(game, summary)
        )
    }

    /** Who has been asked to this challenge already, and the challenger's way of taking it back.
      *
      * Above the invite panel because it is what the panel's answer has to be given against: whether there is a seat
      * left to hold for somebody depends on which are held already, and reading that off the list is how a challenger
      * knows what the picker below is offering them.
      *
      * A player is named when this session has heard their nickname — see `Store.nicknames` — and by their id when it
      * has not. An id is a poor thing to show and a worse thing to hide: the row is the only place a stray invitation
      * can be revoked from.
      *
      * An invitee who has accepted gets a different button, because accepting does not remove the invitation — the row
      * is what permits the seat — so this list goes on showing them, and `revoke` refuses an invitation that has been
      * taken up: the acceptance is what is holding the seat now, and it is what has to go. That is a real action the
      * challenger is allowed (`AcceptanceService.delete` admits the acceptor or the challenger), so the row offers it
      * rather than a Revoke that could only ever be answered with "remove their acceptance instead".
      */
    private def invitedList(game: Game, summary: ChallengeSummary): HtmlElement = {
        val challengeId = summary.challenge.challengeId

        /* One row per invitation, whichever kind: a plain game's name players, a character game's name
         * characters (V25). What differs is who is named and what Revoke addresses; the rest -- the
         * seat, and Remove for an acceptance -- reads the same. `acceptedBy` is the player whose
         * acceptance holds the seat, which is what Remove addresses in both cases. */
        case class Invited(
            who: Map[PlayerId, String] => String,
            seat: Option[GameRoleId],
            acceptedBy: Option[PlayerId],
            revoke: () => Future[Unit]
        )
        val invited =
            summary.invitations.map(invitation =>
                Invited(
                  known => known.getOrElse(invitation.playerId, s"player ${invitation.playerId.value}"),
                  invitation.gameRoleId,
                  Some(invitation.playerId).filter(summary.acceptedInvitees.contains),
                  () => ApiClient.revokeInvitation(game.gameId, challengeId, invitation.playerId)
                )
            ) ++ summary.invitedCharacters.map(character =>
                Invited(
                  _ => character.characterName,
                  character.invitation.gameRoleId,
                  character.acceptedBy,
                  () => ApiClient.revokeCharacterInvitation(game.gameId, challengeId, character.invitation.characterId)
                )
            )

        div(
          child <-- Store.nicknames.signal.map { known =>
              if (invited.isEmpty) emptyNode
              else
                  div(
                    cls := "detail-panel",
                    h4("Invited"),
                    ul(
                      invited.map { invitation =>
                          val seat = invitation.seat
                              .flatMap(role => game.roles.find(_.gameRoleId == role))
                              .fold("any seat that is free")(role => role.displayName)

                          li(
                            cls := "row",
                            div(cls := "title", invitation.who(known)),
                            div(
                              cls := "detail",
                              if (invitation.acceptedBy.isDefined) s"accepted, as $seat" else s"holding $seat"
                            ),
                            invitation.acceptedBy match {
                                case Some(acceptor) =>
                                    // Removing the acceptance, which is what is holding the seat. The
                                    // invitation is left where it is: it is still true that they were
                                    // asked, and leaving it means they can accept again without being
                                    // asked twice. Revoking it as well would be a second decision, and
                                    // the row stays in this list to be revoked once the seat is free.
                                    busyButton("Remove", classes = Some("link")) { busy =>
                                        Store.run(
                                          ApiClient.withdraw(game.gameId, challengeId, acceptor),
                                          busy,
                                          // A 404 is the acceptance being gone already and a 409 the
                                          // challenge being started, past which nobody can be removed.
                                          // Both say this row was drawn from a stale list.
                                          invitationStale(game.gameId)
                                        )(_ => Store.refreshChallenges(game.gameId))
                                    }
                                case None =>
                                    busyButton("Revoke", classes = Some("link")) { busy =>
                                        Store.run(
                                          invitation.revoke(),
                                          busy,
                                          // A 409 is the server saying they have accepted since this
                                          // list was drawn, and a 404 that the invitation is already
                                          // gone. Either way the row is stale, so the list is re-read
                                          // rather than corrected here.
                                          invitationStale(game.gameId)
                                        )(_ => Store.refreshChallenges(game.gameId))
                                    }
                            }
                          )
                      }
                    )
                  )
          }
        )
    }

    /** Which of `owner`'s characters to invite, in a character game (V25).
      *
      * A character game's invitation names a character, so choosing the player is half the answer. Their characters are
      * fetched when this is mounted — by name only, which is all another player may see of them — and the first one not
      * already invited is chosen for the challenger, since most players have one character in a game.
      *
      * The answer is dropped unless this same mount of this picker is still the one waiting for it, and the session
      * that asked is still signed in. `chosen` belongs to the form around the picker, not to the picker: switching to
      * another player replaces the picker but keeps the form, so a late answer about the previous player would
      * otherwise pick one of *their* characters on a form now showing somebody else — and send the invitation there. A
      * counter rather than a mounted flag, because a flag reads true again if the same picker is remounted before the
      * answer lands.
      */
    private def characterPicker(
        gameId: GameId,
        owner: PublicPlayer,
        exclude: Set[CharacterId],
        chosen: Var[Option[CharacterId]]
    ): HtmlElement = {
        // None while the request is out; Left when it failed.
        val loaded = Var(Option.empty[Either[String, Seq[CharacterName]]])
        // Which mount is current; bumped on unmount too, so nothing answers a picker that has gone.
        var mount = 0
        div(
          onMountCallback { _ =>
              mount += 1
              val asked = mount
              chosen.set(None)
              val signIn = Store.currentSignIn
              ApiClient.characterNames(gameId, owner.playerId).onComplete { answer =>
                  if (asked == mount && Store.stillSignedInAs(signIn)) answer match {
                      case scala.util.Success(names) =>
                          val offered = names.filterNot(c => exclude.contains(c.characterId))
                          loaded.set(Some(Right(offered)))
                          chosen.set(offered.headOption.map(_.characterId))
                      case scala.util.Failure(_) =>
                          loaded.set(Some(Left(s"Couldn't load ${owner.nickname}'s characters just now.")))
                  }
              }
          },
          onUnmountCallback(_ => mount += 1),
          // Announced, because it changes without a reload: what came back decides whether there is
          // anything to invite at all.
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
              case Some(Right(names)) if names.nonEmpty =>
                  field(
                    "Their character",
                    select(
                      onChange.mapToValue --> { raw =>
                          chosen.set(
                            raw.toLongOption.map(CharacterId.apply).filter(id => names.exists(_.characterId == id))
                          )
                      },
                      value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                      names.map(c => option(value := c.characterId.value.toString, c.name))
                    )
                  )
              case _ => emptyNode
          }
        )
    }

    /** Inviting somebody to a challenge that already exists: find them, choose the seat, ask.
      *
      * The other half of [[inviteControl]], which invites at the moment a challenge is created. This one exists because
      * a challenger's mind changes after the fact — a seat nobody has taken, somebody who should have been asked in the
      * first place — and because the alternative is deleting the challenge and offering it again.
      *
      * Its own search box rather than the one on "Find Players": that box and its results belong to that screen, and
      * are deliberately kept across a visit to a player's page, so borrowing them here would clear a search somebody
      * means to come back to.
      *
      * The panel is rebuilt, and so collapses, when the challenge list is re-read — which a successful invitation
      * causes. That is the intended end of the interaction: the invitation now shows in the list above, which is the
      * answer to the question the panel was asking.
      */
    private def invitePanel(game: Game, summary: ChallengeSummary): HtmlElement = {
        val open = Var(false)
        val prefix = Var("")
        val found = Var(Option.empty[PlayerSearchResult])
        val chosen = Var(Option.empty[PublicPlayer])
        // The seat to hold for them, `None` meaning any that is still free when they answer.
        val seat = Var(Option.empty[GameRoleId])
        // In a character game, which of their characters is being asked (V25).
        val characterGame = game.gameType == GameType.Character
        val character = Var(Option.empty[CharacterId])

        /* What may still be offered to somebody in particular, and the constraint this panel exists
         * to respect.
         *
         * Two things are gone, not one. A role somebody has accepted is taken, which `freeRoles`
         * already answers. A role another invitation is holding is *also* gone, even though nobody
         * has accepted it: `ChallengeService.invite` builds its `taken` set from the accepted roles
         * and the reserved ones together, so a second invitation naming a held seat is refused with
         * a 409 -- the seat is promised, and two players cannot both be honoured. The same rule is
         * enforced within one `create` by the pairwise check in `validateInvitations`.
         *
         * "Any seat that is free" is not subject to it and is always offered: several invitations may
         * name no role at all, since none of them is holding anything for anybody. */
        val offerable = offerableSeats(game, summary)

        // Nobody who is already invited, and not the challenger themselves: the first is the
        // invitation table's primary key and the second a rule of the service, so both come back as
        // errors rather than as invitations. A name that cannot be acted on is better left out of the
        // results than shown with a button that fails.
        //
        // In a character game it is characters that are asked, so a player stays findable while any of
        // their characters has not been -- the picker below leaves out the ones that have.
        val alreadyAsked = summary.invitations.map(_.playerId).toSet + summary.challenge.challenger
        val charactersAsked = summary.invitedCharacters.map(_.invitation.characterId).toSet

        div(
          button(
            tpe := "button",
            cls := "link",
            aria.expanded <-- open.signal,
            child.text <-- open.signal.map(if (_) "Close" else "Invite a player"),
            onClick --> { _ =>
                // Emptied on the way out, so re-opening it is a fresh question rather than the last
                // one's half-finished answer.
                if (open.now()) {
                    prefix.set(""); found.set(None); chosen.set(None); seat.set(None); character.set(None)
                }
                open.update(!_)
            }
          ),
          child <-- open.signal.map { showing =>
              if (!showing) emptyNode
              else
                  div(
                    cls := "detail-panel",
                    /* How the panel is getting on, announced from a region mounted with it.
                     *
                     * Neither the results list, nor the "nobody new" line, nor the "inviting" line
                     * can carry `aria-live` itself: each is built at the moment it has something to
                     * say, and a region a reader has never seen before has no change to report --
                     * which is exactly when the announcement matters, since a reader who pressed
                     * Search is waiting to be told what came back. So this region says how it went
                     * and those three go on saying what it was.
                     *
                     * At the panel rather than inside either branch, so that choosing somebody --
                     * which replaces the whole of one branch with the other -- is a change of text
                     * in a region that stays put rather than another region appearing.
                     *
                     * `sr-only`, because all three of those say the same thing to anybody who can
                     * see them. A count rather than the names: the names are in the list, one button
                     * each, and reading them twice is not worth the hearing.
                     */
                    p(
                      cls := "sr-only",
                      role := "status",
                      aria.live := "polite",
                      child.text <-- chosen.signal.combineWith(found.signal).map {
                          case (Some(candidate), _) => s"Inviting ${candidate.nickname}."
                          case (None, None)         => ""
                          case (None, Some(result)) =>
                              val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                              if (askable.isEmpty) "Nobody new by that name."
                              else if (askable.size == 1) "One player found."
                              else s"${askable.size} players found."
                      }
                    ),
                    child <-- chosen.signal.map {
                        case None =>
                            div(
                              field(
                                "Find a player",
                                input(
                                  tpe := "search",
                                  controlled(value <-- prefix.signal, onInput.mapToValue --> prefix)
                                )
                              ),
                              busyButton(
                                "Search",
                                disabledWhen = prefix.signal.map(_.trim.isEmpty)
                              ) { busy =>
                                  /* Guarded by the sign-in counter, because this writes into the store:
                                   * `Store.nicknames` outlives this panel, and an answer that arrives
                                   * after a sign-out would teach the next player's session who the
                                   * previous one had been looking for.
                                   *
                                   * `Store.run` is right for the request -- it is a button, and the
                                   * spinner belongs to a click somebody is waiting on -- and it
                                   * deliberately drops nothing, which is why the check is here rather
                                   * than in it. The same treatment `Account`'s rename takes, and the
                                   * reason those two methods are `private[ui]`. */
                                  val signIn = Store.currentSignIn
                                  Store.run(ApiClient.searchPlayers(prefix.now().trim), busy) { result =>
                                      if (Store.stillSignedInAs(signIn)) {
                                          // Remembered for the invited list above, which has ids and no
                                          // names of its own.
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
                                                li(
                                                  cls := "row",
                                                  button(
                                                    tpe := "button",
                                                    cls := "link",
                                                    candidate.nickname,
                                                    onClick --> (_ => chosen.set(Some(candidate)))
                                                  )
                                                )
                                            )
                                          )
                              }
                            )

                        case Some(candidate) =>
                            inviteCandidate(game, summary, candidate, seat, character, offerable, charactersAsked)(
                              button(
                                tpe := "button",
                                cls := "link",
                                "Somebody else",
                                onClick --> { _ =>
                                    chosen.set(None); seat.set(None); character.set(None)
                                }
                              )
                            )
                    }
                  )
          }
        )
    }

    /** The second half of inviting somebody to an existing challenge, once it is known who: which of their characters
      * (in a character game), which seat, and the button that sends it. Shared by the invite panel on the challenger's
      * own row and by the invited player's page. `after` is whatever the caller wants beneath the button.
      */
    private def inviteCandidate(
        game: Game,
        summary: ChallengeSummary,
        candidate: PublicPlayer,
        seat: Var[Option[GameRoleId]],
        character: Var[Option[CharacterId]],
        offerable: Seq[GameRole],
        charactersAsked: Set[CharacterId]
    )(after: Modifier[HtmlElement]*): HtmlElement = {
        val characterGame = game.gameType == GameType.Character
        div(
          p(cls := "detail", s"Inviting ${candidate.nickname}."),
          if (characterGame) characterPicker(game.gameId, candidate, charactersAsked, character)
          else emptyNode,
          field(
            "Their seat",
            select(
              onChange.mapToValue --> { raw =>
                  seat.set(
                    raw.toIntOption
                        .map(GameRoleId.apply)
                        .filter(id => offerable.exists(_.gameRoleId == id))
                  )
              },
              value <-- seat.signal.map(_.map(_.value.toString).getOrElse("")),
              // Always available, and the default: a role-less invitation holds
              // nothing, so any number of them can stand together.
              option(value := "", "any seat that is free"),
              offerable.map(role => option(value := role.gameRoleId.value.toString, role.displayName))
            )
          ),
          // Said rather than left to be inferred from a short list: a challenger
          // who means to hold a particular seat and cannot see it needs to know
          // whether it is taken or promised, which are different problems with
          // different remedies -- one waits, the other is a revoke above.
          if (offerable.isEmpty)
              p(
                cls := "detail",
                "Every role is either taken or already held for somebody, so this invitation " +
                    "can only be for any seat that comes free."
              )
          else emptyNode,
          busyButton(
            "Send the invitation",
            disabledWhen = character.signal.map(_.isEmpty && characterGame)
          ) { busy =>
              val sent: Future[Unit] = character.now() match {
                  case Some(asked) if characterGame =>
                      ApiClient
                          .inviteCharacter(
                            game.gameId,
                            summary.challenge.challengeId,
                            CharacterInvite(asked, seat.now())
                          )
                          .map(_ => ())
                  case _ =>
                      ApiClient
                          .invite(
                            game.gameId,
                            summary.challenge.challengeId,
                            Invite(candidate.playerId, seat.now())
                          )
                          .map(_ => ())
              }
              Store.run(sent, busy, invitationStale(game.gameId))(_ => Store.refreshChallenges(game.gameId))
          },
          after
        )
    }

    /* The seats that may still be held for somebody: free, and not already held by another invitation.
     * See the note on `held` in `invitePanel`. */
    private def offerableSeats(game: Game, summary: ChallengeSummary): Seq[GameRole] = {
        val held =
            (summary.invitations.flatMap(_.gameRoleId) ++ summary.invitedCharacters.flatMap(
              _.invitation.gameRoleId
            )).toSet
        freeRoles(game, summary).filterNot(role => held.contains(role.gameRoleId))
    }

    /** What to do when inviting or revoking is refused because the challenge has moved on.
      *
      * The server's own message is left standing in both cases, because it is the one that says which thing happened —
      * The reloads below cannot clear it: they are fetches, and a fetch's success leaves an action's banner alone.
      *
      * The two statuses are not the same news, so they do not get the same reload:
      *
      *   - 409 is the challenge having moved on under this panel, and one of the things that can mean is that it has
      *     *started*: `refuseStarted` refuses both of these calls on a started challenge with exactly this status. The
      *     challenger is a participant in the match that start created — creating a challenge accepts it — so their own
      *     match lists are as stale as this panel is, and a match they may already be on the clock in would otherwise
      *     be missing from "Current Matches" until something else asked. The other things a 409 means here — a seat
      *     promised or accepted since the picker was drawn, a player who accepted before the revoke landed — leave
      *     those lists alone, and three reloads that find nothing new cost a dimmed second.
      *   - 404 is the challenge or the invitation simply gone. Nothing was started by that, so the challenge list is
      *     the whole of what is stale.
      */
    private def invitationStale(gameId: GameId)(failure: Throwable): Unit = failure match {
        case ApiError(409, _) =>
            Store.refreshChallenges(gameId)
            reloadAfterStart()
        case ApiError(404, _) => Store.refreshChallenges(gameId)
        case _                => ()
    }

    /** A challenge somebody else is offering, and this player's way into it.
      *
      * `me` is here for the roles (V22). A seat is not free merely because nobody has accepted it: an invitation holds
      * one for the player it names, and an invitation *to this player* that names a seat is an offer of that seat and
      * no other. Both are refused by the server — the first with a 409, the second with a 400 — so both narrow the
      * picker rather than being left to fail on submit, which for the second would fail on the *default* selection and
      * so on the first click.
      */
    private def openChallengeRow(
        game: Game,
        summary: ChallengeSummary,
        myCharacters: Seq[CharacterId],
        me: PlayerId
    ): HtmlElement = {
        val challenge = summary.challenge
        // Which character this row accepts as. Only one not already seated here: a character accepted
        // and then transferred to this player holds a seat that is not theirs, and accepting as it again
        // is refused. Of those, the one invited to this challenge if there is one -- an invitation is to a
        // character (V25), and accepting as any other is refused on a closed challenge and is a different
        // seat on an open one. Otherwise their first eligible one. A player holds one seat per challenge,
        // so if two of theirs were invited, either will do.
        val eligible = myCharacters.filterNot(summary.seatedCharacters.contains)
        val invitedCharacter = summary.invitedCharacters.find(i => eligible.contains(i.invitation.characterId))
        val characterId = invitedCharacter.map(_.invitation.characterId).orElse(eligible.headOption)
        // A character game in which every one of this player's characters is seated already has no
        // Accept to offer at all.
        val noCharacterLeft = myCharacters.nonEmpty && eligible.isEmpty
        // Nor one in which they have no character at all: a game's admin sees these challenges without
        // one, to offer their own (see `gameChallenges`), and accepting needs a character to play.
        val noCharacterAtAll = game.gameType == GameType.Character && myCharacters.isEmpty
        // The roles nobody has claimed yet: accepting as a taken role is refused by the server, and
        // there is no reason to offer a choice that cannot work. A challenge with none left is one
        // that is full, and gets no Accept at all.
        val free = freeRoles(game, summary)
        // The seat held for this player, if they were invited to one. `accept` refuses any other role
        // for them, so it is not one choice among the free ones -- it is the only one.
        // In a character game the invitation is to the character this row would accept as (V25), not to
        // the player, so a seat held for another of their characters is held for somebody else.
        val (mySeat, heldForOthers) = characterId match {
            case Some(mine) =>
                val (asMe, others) = summary.invitedCharacters.partition(_.invitation.characterId == mine)
                (
                  asMe.headOption.flatMap(_.invitation.gameRoleId),
                  (others.flatMap(_.invitation.gameRoleId) ++ summary.invitations.flatMap(_.gameRoleId)).toSet
                )
            case None =>
                (
                  summary.invitations.find(_.playerId == me).flatMap(_.gameRoleId),
                  // And the seats held for everybody else, which are free of acceptances and still not on offer.
                  (summary.invitations.filterNot(_.playerId == me).flatMap(_.gameRoleId) ++
                      summary.invitedCharacters.flatMap(_.invitation.gameRoleId)).toSet
                )
        }

        val choices = mySeat match {
            case Some(seat) => free.filter(_.gameRoleId == seat)
            case None       => free.filterNot(role => heldForOthers.contains(role.gameRoleId))
        }

        // Pre-selected from what may actually be accepted, which is the half of this that a picker
        // alone would not fix: an invited player's default used to be the first free role, and their
        // invitation names a different one.
        val role = Var(choices.headOption.map(_.gameRoleId))

        li(
          cls := "row",
          div(cls := "title", challenge.message),
          div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
          timeLimitDetail(challenge),
          parameterDetail(game, challenge),
          if (challenge.friendly) friendlyLabel() else emptyNode,
          // A seat held for this player is said rather than offered: a picker with one entry asks a
          // question whose answer is already settled, and what they need to know is which seat they
          // were asked for.
          // Said when it is not the character this screen otherwise acts as, since the player did not
          // choose it here and it is the one the Accept below sends.
          invitedCharacter
              .filterNot(i => eligible.headOption.contains(i.invitation.characterId))
              .fold(emptyNode)(i => div(cls := "detail", s"${i.characterName} was invited")),
          mySeat.flatMap(seat => game.roles.find(_.gameRoleId == seat)) match {
              case _ if noCharacterLeft || noCharacterAtAll => emptyNode
              case Some(seat)                               => div(cls := "detail", s"invited as ${seat.displayName}")
              case None                                     => roleSelect(choices, role)
          },
          if (noCharacterAtAll) div(cls := "detail", "you need a character in this game to accept")
          else if (noCharacterLeft)
              div(cls := "detail", "your characters in this game already hold seats in this challenge")
          else if (choices.isEmpty)
              // Told apart, because the remedies differ: a full challenge is one to forget, where a
              // challenge whose free seats are all promised may still come to this player if one of
              // those invitations is turned down.
              div(
                cls := "detail",
                if (free.isEmpty) "every role is taken"
                else "every role still free is held for another player"
              )
          else
              busyButton("Accept") { busy =>
                  val chosen = role.now().getOrElse(choices.head.gameRoleId)
                  // Whether this acceptance is also the start: an auto-starting challenge whose last
                  // required role is the one being taken here. Worked out from what this row already
                  // knows rather than asked of the server, because the server answers an acceptance
                  // with the acceptance — the match it may have started is not in the reply.
                  //
                  // Optional roles are not counted, for the same reason the server does not count
                  // them: they are the ones a start need not wait for.
                  val starts = challenge.autoStart &&
                      unfilledRoles(game, summary).forall(_.gameRoleId == chosen)
                  Store.run(ApiClient.accept(game.gameId, challenge.challengeId, characterId, chosen), busy) { _ =>
                      // Two lists change: this one, which now shows the role as taken, and the acceptances
                      // — the challenge has joined what this player is waiting on, and if they are its
                      // challenger and it is now full, what they can start.
                      Store.refreshChallenges(game.gameId)
                      // And when the acceptance started the match, the match lists as well: the player
                      // is now in a match that is not on their screen, and it may already be their
                      // turn in it. The same three sections a Start reloads, for the same reason —
                      // this *was* the start.
                      if (starts) reloadAfterStart() else reloadAcceptanceSections()
                  }
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

    /** The clock something is played under, for a challenge — every row of both lists has one. */
    private def timeLimitDetail(challenge: Challenge): HtmlElement =
        timeLimitDetail(challenge.timeLimit, challenge.timeLimitKind, challenge.timeLimitUnit, challenge.live)

    /** A captioned text field whose explanation is a tip beside it rather than a line under it: [[withTip]] around a
      * `label.field`, with the input pointed at the tip so that it is read out with the field.
      */
    private def tipField(id: String, caption: String, text: String)(control: HtmlElement): HtmlElement =
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

    private def freshTipId(prefix: String): String = {
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

    /** The "Friendly" column's heading, with what a friendly match is a tap away. */
    private def friendlyHeading(): HtmlElement =
        withTip(freshTipId("friendly-tip"), "friendly matches", friendlyMeaning)(span("Friendly"))
            .amend(cls := "inline-tip")

    private def withTip(id: String, subject: String, text: String)(control: HtmlElement): HtmlElement = {
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
            cls := "tip-toggle",
            aria.label := s"About $subject",
            aria.expanded <-- open.signal,
            aria.controls := id,
            "?",
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
    private case class ParameterChoice(name: String, label: String, values: Seq[String], default: Option[String])

    /** The values a challenger may pick for each of the game's parameters, in the order [[Format.parameterValues]] puts
      * them. Parameters with no values to choose between are left out: there is nothing to offer.
      */
    private def parameterChoices(game: Game): Seq[ParameterChoice] =
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
    private def settingsOf(choices: Map[String, String]): String =
        ujson.write(ujson.Obj.from(choices.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

    /** What the challenger chose for each of the game's parameters, read back out of `settings`, as the parameter's
      * label and the value chosen.
      */
    private def chosenParameters(game: Game, challenge: Challenge): Seq[(String, String)] = {
        val stored =
            try
                ujson.read(challenge.settings) match {
                    case o: ujson.Obj =>
                        o.value.toMap.collect {
                            case (k, ujson.Str(v))              => k -> v
                            case (k, ujson.Num(n)) if n.isWhole => k -> n.toLong.toString
                        }
                    case _ => Map.empty[String, String]
                }
            catch { case _: Throwable => Map.empty[String, String] }
        parameterChoices(game).flatMap(choice => stored.get(choice.name).map(choice.label -> _))
    }

    /** The terms a challenge was offered on beyond its clock — "rounds: 12" — so that whoever accepts it knows what
      * they are agreeing to. Nothing at all for a game with no parameters, or a challenge that chose none.
      */
    private def parameterDetail(game: Game, challenge: Challenge): Node =
        chosenParameters(game, challenge) match {
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

    /** The roles of `game` that no acceptance of `summary` has claimed yet. */
    private def freeRoles(game: Game, summary: ChallengeSummary): Seq[GameRole] =
        game.roles.filterNot(r => summary.takenRoles.contains(r.gameRoleId))

    /** The roles a start is still waiting for: required, and unclaimed. Optional roles are exactly the ones a match
      * need not wait for, so they are not counted here even when free.
      */
    private def unfilledRoles(game: Game, summary: ChallengeSummary): Seq[GameRole] =
        freeRoles(game, summary).filterNot(_.optional)

    /** A picker for the role a player will play, which matchmaker passes on to the game engine.
      *
      * `choices` are the roles still available. There is no "any role" entry: every seat names a role, so the first
      * available one stands pre-selected and the picker only changes which.
      */
    private def roleSelect(choices: Seq[GameRole], selected: Var[Option[GameRoleId]]): Node =
        if (choices.isEmpty) emptyNode
        else
            select(
              // Named here rather than by a caption at each call site: one of the two is a control in
              // the middle of a challenge row, where a caption would be a word on its own line.
              aria.label := "the role you will play",
              onChange.mapToValue --> { raw =>
                  selected.set(raw.toIntOption.map(GameRoleId.apply).filter(id => choices.exists(_.gameRoleId == id)))
              },
              value <-- selected.signal.map(_.map(_.value.toString).getOrElse("")),
              choices.map(r => option(value := r.gameRoleId.value.toString, r.displayName))
            )

    /** The form that offers a challenge, and — when `invitee` is set — invites one player to it in the same request.
      *
      * `invitee` arrives from that player's own page, which is the only screen that can name somebody: see
      * [[inviteControl]]. With one, the challenge defaults to closed, because the reason to invite a particular player
      * is usually that the game is for them — and the box below says so and can be unticked, since an invitation to an
      * open challenge is a nudge rather than a gate, and a challenger who wants both is entitled to both.
      */
    private def newChallengeForm(
        game: Game,
        player: Player,
        characters: Seq[Character[String]],
        invitee: Option[PublicPlayer] = None
    ): HtmlElement = {
        val message = Var("")
        val isPublic = Var(false)
        // Whether the match begins on its own once every required role is taken, instead of waiting
        // for this challenger to press Start. On by default: the challenge is an offer to play, and
        // having to come back and press Start once everybody has said yes is a step most
        // challengers do not want. Optional roles are not waited for, so a challenger holding a
        // seat open for somebody in particular is the one who turns it off.
        val autoStart = Var(true)
        // How long a player gets, blank for no limit. Blank by default because an unlimited game
        // is the one nobody can lose by walking away from their desk, and the challenger who wants
        // a clock is the one who came here to set one.
        //
        // It belongs on the challenge rather than on the game: how long a turn may take is what the
        // players agree to, where what happens when one runs out is a rule of the game — that is
        // `Game.timeoutAction`, which an admin sets once.
        val timeLimit = Var("")
        // The unit that number is in. Minutes by default, which is the shortest of them and what
        // the field meant when it was the only one — a challenger who wants days has to say so, and
        // one who does not is not asked to.
        val timeLimitUnit = Var(TimeLimitUnit.Minutes)
        // And what that number is a limit on: each turn on its own, or the player's whole match, the
        // way a chess clock works. Per turn by default, which is what every limit meant before the
        // choice existed.
        val timeLimitKind = Var(TimeLimitKind.PerTurn)
        // Whether the match is played live: the game runs every turn against the limit above, per
        // turn or as a chess clock, and a player who runs out loses the match there and then. Off by
        // default -- it asks the players to be there together -- and when on, the limit is required,
        // since it is the clock the game plays against.
        val live = Var(false)
        // A challenge is its challenger's own acceptance, so it names a role like any other. Nothing
        // has been claimed yet, so every role of the game is on offer and the first stands selected.
        val role = Var(game.roles.headOption.map(_.gameRoleId))
        // Whether this player administers the game (V35), which is what lets them offer a match they
        // will not play in, or one that is not friendly: `Store.administers`, held here so that the
        // button can read it when it is pressed.
        val administers = Var(player.isAdmin)
        // Whether the challenger takes a seat. Only a game's admin is offered the choice. In a character
        // game the seat is played by one of their characters, so an admin with none has no seat to take.
        val canPlay = game.gameType != GameType.Character || characters.nonEmpty
        val plays = Var(canPlay)
        // Whether the match will be friendly, which is every match unless a game's admin says not.
        val friendly = Var(true)
        val seated: Signal[Boolean] = plays.signal.combineWith(administers.signal).map((p, a) => p || !a)
        def seatedNow: Boolean = plays.now() || !administers.now()
        // Whether anybody may accept this, or only the player invited to it. A challenge offered to
        // somebody in particular is theirs alone; one offered to nobody is open, since closed it is
        // one nobody could accept at all, and the server refuses it.
        val isOpen = invitee.isEmpty
        // The seat being held for the invited player, or `None` for "any that is still free". Their
        // own choice of role is what `None` leaves them, and it is the default: holding a particular
        // seat is the stronger statement of the two, so it is the one the challenger has to make.
        val inviteeRole = Var(Option.empty[GameRoleId])
        // In a character game the invitation names one of the invitee's characters (V25).
        val characterGame = game.gameType == GameType.Character
        val inviteeCharacter = Var(Option.empty[CharacterId])
        // And the challenge is offered as one of the challenger's own: the first of them to start
        // with, which is the only one for most players, but chosen here rather than assumed.
        val ownCharacter = Var(characters.headOption.map(_.characterId))
        // One value per game parameter the challenger may choose — how many rounds a bout is, say —
        // starting from the game's default, or its first value when it names none. Sent as the
        // challenge's settings, and handed to the engine in place of the default when the match starts.
        val parameters = Var(
          parameterChoices(game)
              .map(choice => choice.name -> choice.default.filter(choice.values.contains).getOrElse(choice.values.head))
              .toMap
        )

        div(
          cls := "card",
          Store.administers(game.gameId, player) --> administers,
          h3(idAttr := "offer-challenge-heading", "Offer a Challenge"),
          field("Message", input(controlled(value <-- message.signal, onInput.mapToValue --> message))),
          // Shown even when there is only one, so the challenger can see who they are offering -- and
          // not at all to an admin who will not play, who offers nobody's character.
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
          // What only a game's admin may offer. Both boxes in one place, because they are the two
          // ways this challenge can differ from one anybody else could make.
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
                              "them to and start, but have no seat in."
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
          // The challenger's own seat, which a game's admin who is not playing has none of.
          child <-- seated.map(if (_) roleSelect(game.roles, role) else emptyNode),
          // One picker per parameter, captioned with what players are shown for it and keyed by the
          // name the engine is sent. Built once: the game's parameters do not change while the form is open.
          parameterChoices(game).map { choice =>
              field(
                // A display name that is just the name -- what every parameter started with -- is
                // capitalized, as the name always was here.
                if (choice.label == choice.name) choice.name.capitalize else choice.label,
                select(
                  onChange.mapToValue --> (chosen => parameters.update(_.updated(choice.name, chosen))),
                  value <-- parameters.signal.map(_.getOrElse(choice.name, "")),
                  choice.values.map(v => option(value := v, v))
                )
              )
          },
          // Everything about the one player this is being offered to, and nothing at all when it is
          // being offered to whoever comes along.
          invitee.fold(emptyNode) { asked =>
              div(
                cls := "detail-panel",
                p(cls := "detail", s"Inviting ${asked.nickname}."),
                if (characterGame) characterPicker(game.gameId, asked, Set.empty, inviteeCharacter) else emptyNode,
                field(
                  "Their seat",
                  // Rebuilt as the challenger's own role changes, because the seat they take is not
                  // one they can also hold for somebody else -- the server refuses a role twice over,
                  // and offering it here would be offering a choice that fails on submit.
                  select(
                    onChange.mapToValue --> { raw =>
                        inviteeRole.set(
                          raw.toIntOption.map(GameRoleId.apply).filter(id => game.roles.exists(_.gameRoleId == id))
                        )
                    },
                    value <-- inviteeRole.signal.map(_.map(_.value.toString).getOrElse("")),
                    // The blank first entry is the default, and it means something: any seat still
                    // free when they answer, rather than one held for them.
                    option(value := "", "any seat that is free"),
                    children <-- role.signal
                        .combineWith(seated)
                        .map((mine, inIt) =>
                            game.roles
                                .filterNot(r => inIt && mine.contains(r.gameRoleId))
                                .map(r => option(value := r.gameRoleId.value.toString, r.displayName))
                        )
                  )
                )
              )
          },
          // The number and the unit it is in, in one field: they are one answer, and a caption
          // over each would read as two questions.
          field(
            "Time Limit (Leave blank for unlimited)",
            div(
              cls := "compound",
              input(
                tpe := "number",
                minAttr := "1",
                stepAttr := "1",
                aria.label := "how much time",
                controlled(value <-- timeLimit.signal, onInput.mapToValue --> timeLimit)
              ),
              select(
                aria.label := "the unit that time is in",
                onChange.mapToValue --> (raw => timeLimitUnit.set(TimeLimitUnit.fromCode(raw))),
                value <-- timeLimitUnit.signal.map(_.code),
                TimeLimitUnit.offered.map(unit => option(value := unit.code, unit.label))
              )
            )
          ),
          // Offered whatever the limit says, including blank: choosing the kind first and then
          // typing the number is at least as natural as the other order, and a kind with no limit
          // to apply it to simply does nothing.
          field(
            "How that time is spent",
            select(
              onChange.mapToValue --> (raw => timeLimitKind.set(TimeLimitKind.fromCode(raw))),
              value <-- timeLimitKind.signal.map(_.code),
              TimeLimitKind.values.toSeq.map(kind => option(value := kind.code, kind.label))
            )
          ),
          // The same rule the two regions above follow: the element carrying `aria-live` is mounted
          // before it has anything to say, and it is the text that arrives. A message built at the
          // moment it becomes true is a region nothing was watching, and a reader typing into the
          // field is told nothing about why the form will not submit.
          //
          // A `div` rather than a `p` for the reason `gamePage`'s is: it is empty whenever the field
          // is valid, which is most of the time, and an empty `p` would hold its margins open under
          // the input. The class comes and goes with the text.
          div(
            aria.live := "polite",
            cls <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => if (limitProblem(raw, isLive).isEmpty) "" else "empty"),
            child.text <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => limitProblem(raw, isLive).getOrElse(""))
          ),
          // A real label with the box inside it, like Public and the auto-start box below. The full
          // description is a tip beside it, tied to the box so that a reader hears what "live"
          // commits the players to.
          withTip(
            "live-tip",
            "Live",
            "Played in real time. The game engine runs the turns and keeps the clock itself, so the time " +
                "limit is required. Each player's clock starts once they have opened the board, and a " +
                "player who runs out of time loses the match."
          )(
            label(
              input(
                tpe := "checkbox",
                aria.describedBy := "live-tip",
                controlled(
                  checked <-- live.signal,
                  onClick.mapToChecked --> live
                )
              ),
              "Live"
            )
          ),
          // Public means anyone may watch the match, which the game engine implements by issuing a
          // url that needs no sign-in. It is decided here because it is a property of the game being
          // offered, not of any one player's part in it.
          withTip(
            "public-tip",
            "Public",
            "Anyone may watch the match, without signing in, and it is listed among the public matches " +
                "on each player's page. It does not change who may accept the challenge."
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
          // The same shape as the Public box above it: a box and a short caption, which is all
          // either of them needs. Ticked and fixed for a challenger who will not play: the match will
          // not be in any list of theirs, so it has to start without them, and the server refuses one
          // that would not. Disabled rather than hidden, so that what the challenge will do is said.
          child <-- seated.map { inIt =>
              div(
                label(
                  input(
                    tpe := "checkbox",
                    disabled := !inIt,
                    aria.describedBy := (if (inIt) "" else "auto-start-why"),
                    controlled(
                      checked <-- autoStart.signal.map(_ || !inIt),
                      onClick.mapToChecked --> autoStart
                    )
                  ),
                  "Start when all seats filled"
                ),
                if (inIt) emptyNode
                else
                    p(
                      idAttr := "auto-start-why",
                      cls := "detail",
                      "Always, for a match you will not play in: it will not appear among your matches."
                    )
              )
          },
          // The challenger changing their own role can leave the invitee holding the seat just taken,
          // which the server refuses. Released rather than refused here: the challenger's choice is
          // the one they just made, and the invitation falls back to "any seat that is free".
          // And the same when they take a seat again after offering the match without one: the role they
          // had chosen comes back with it, and may be the one held for the invitee meanwhile.
          role.signal.combineWith(seated) --> { (mine, inIt) =>
              if (inIt && mine.exists(inviteeRole.now().contains)) inviteeRole.set(None)
          },
          busyButton(
            "Create Challenge",
            // A game with no roles at all has nothing an acceptance could name, so no challenge for
            // it can be created. The server refuses one; this keeps the button from offering it.
            disabledWhen = message.signal
                .combineWith(
                  role.signal,
                  timeLimit.signal,
                  inviteeCharacter.signal,
                  live.signal,
                  ownCharacter.signal,
                  seated
                )
                .map { case (m, r, limit, asked, isLive, own, inIt) =>
                    m.trim.isEmpty || (inIt && r.isEmpty) || limitProblem(limit, isLive).isDefined ||
                    // A character game's challenge is offered as one of the challenger's characters.
                    (characterGame && inIt && own.isEmpty) ||
                    // An invitee in a character game is asked through a character, and until one is
                    // chosen there is nothing to send them.
                    (characterGame && invitee.isDefined && asked.isEmpty)
                }
            // No fallback role: a seated challenger with no role has no challenge to make, and the
            // disabled button above is what keeps that from being reachable. One who is not seated
            // names none.
          ) { busy =>
              val seat = if (seatedNow) role.now() else None
              val isFriendly = !administers.now() || friendly.now()
              if (seat.isDefined || !seatedNow) {
                  // The server assigns the id; this is the same unassigned-sentinel convention the
                  // service layer uses on create.
                  val challenge: Challenge =
                      if (characterGame)
                          CharacterChallenge(
                            challengeId = ChallengeId(0),
                            challenger = player.playerId,
                            message = message.now().trim,
                            start = None,
                            timeLimit = durationOf(timeLimit.now(), timeLimitUnit.now()),
                            settings = settingsOf(parameters.now()),
                            gameId = game.gameId,
                            // The challenger's character plays their seat, and there is none without one.
                            characterId = if (seat.isDefined) ownCharacter.now() else None,
                            isPublic = isPublic.now(),
                            gameRoleId = seat,
                            timeLimitKind = timeLimitKind.now(),
                            timeLimitUnit = timeLimitUnit.now(),
                            autoStart = autoStart.now() || !seatedNow,
                            isOpen = isOpen,
                            live = live.now(),
                            friendly = isFriendly
                          )
                      else
                          PlainChallenge(
                            challengeId = ChallengeId(0),
                            challenger = player.playerId,
                            message = message.now().trim,
                            start = None,
                            timeLimit = durationOf(timeLimit.now(), timeLimitUnit.now()),
                            settings = settingsOf(parameters.now()),
                            gameId = game.gameId,
                            isPublic = isPublic.now(),
                            gameRoleId = seat,
                            timeLimitKind = timeLimitKind.now(),
                            timeLimitUnit = timeLimitUnit.now(),
                            autoStart = autoStart.now() || !seatedNow,
                            isOpen = isOpen,
                            live = live.now(),
                            friendly = isFriendly
                          )

                  // One request rather than a create and then an invite: the server validates the
                  // invitations against the challenge and against each other, and a closed challenge
                  // created on its own would exist, briefly, as one nobody could accept.
                  val invitations =
                      if (characterGame) Seq.empty
                      else invitee.map(asked => Invite(asked.playerId, inviteeRole.now())).toSeq
                  val characterInvitations =
                      if (!characterGame || invitee.isEmpty) Seq.empty
                      else inviteeCharacter.now().map(asked => CharacterInvite(asked, inviteeRole.now())).toSeq

                  // Taken before the request, and checked before the store is written: this callback
                  // closes the form, clears the invitee and re-reads a game's challenges, and all
                  // three of those belong to whoever was on this screen when the button was clicked.
                  // A create answered after a sign-out would close a form the next player has opened
                  // and clear an invitee they had just chosen; answered after a walk to another game,
                  // it would re-read the challenges of the game they have left. `Store.run` drops
                  // nothing by design -- a click is always worth an answer -- so the guard belongs
                  // here, which is what `currentSignIn` is visible outside the store for.
                  val signIn = Store.currentSignIn
                  Store.run(ApiClient.createChallenge(challenge, invitations, characterInvitations), busy) { _ =>
                      // The fields are this form's own, so a stale answer resetting them costs
                      // nothing: the form it belongs to is gone, and a form still on screen is a
                      // newer one this cannot reach.
                      message.set("")
                      timeLimit.set("")
                      timeLimitUnit.set(TimeLimitUnit.Minutes)
                      timeLimitKind.set(TimeLimitKind.PerTurn)
                      autoStart.set(true)
                      live.set(false)
                      plays.set(true)
                      friendly.set(true)
                      if (Store.stillSignedInAs(signIn) && Store.page.now() == Store.Page.OneGame(game.gameId)) {
                          // The challenge it was open for now exists and is in the list below it. The
                          // invitation went with it, so the slot is spent -- the next challenge offered
                          // from this screen is not addressed to the same player by default.
                          Store.showChallengeForm.set(false)
                          Store.invitee.set(None)
                          Store.refreshChallenges(game.gameId)
                      }
                  }
              }
          }
        )
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
        if (raw.trim.isEmpty) Option.when(live)("A live match needs a time limit for each turn.")
        else Option.when(amountOf(raw).isEmpty)("A time limit is a whole number, more than zero.")

    /** A time limit typed in some unit, as a `Duration`. The unit itself travels with the challenge, so that the limit
      * is said back in the unit it was offered in rather than in whichever one happens to divide it.
      */
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
