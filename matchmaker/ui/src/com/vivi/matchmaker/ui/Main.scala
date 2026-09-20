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
        subsection: Boolean = false
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
        subsection: Boolean
    )(content: Modifier[HtmlElement]*): HtmlElement =
        sectionTag(
          cls := "refreshable",
          cls("refreshing") <-- refreshing.signal,
          div(
            cls := "section-head",
            if (subsection) h3(heading) else h2(heading),
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
        )

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
              div(games.map(game => menuItem(game.name, Store.Page.OneGame(game.gameId))))
          ),
          // Only for admins, because only an admin can create a game: the server answers anyone else
          // with a 403, and a menu entry that always fails is worse than no entry.
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin => menuItem("Add a Game", Store.Page.NewGame)
              case _                              => emptyNode
          }
        )

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
          child <-- Store.invitations.signal.map { invitations =>
              if (invitations.isEmpty) emptyNode
              else
                  refreshableSection(
                    "You Have Been Invited",
                    refreshingInvitations,
                    () => Store.reloadInvitations(),
                    subsection = false
                  )(ul(invitations.map(invitationRow)))
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
        // A character game's acceptance must name a character, and this row has none loaded; a
        // role-less invitation is an offer of any free seat, and which are free is not in this
        // response either.
        val acceptableHere = invitation.gameRoleId.isDefined && invited.gameType != GameType.Character

        li(
          cls := "row",
          div(cls := "title", invited.message),
          div(cls := "detail", s"${invited.challengerNickname} invited you to ${invited.gameName}"),
          div(
            cls := "detail",
            invited.roleName.fold("as any seat that is free")(role => s"as $role")
          ),
          if (acceptableHere)
              busyButton("Accept") { busy =>
                  // `get` is safe under `acceptableHere`, which is what this branch is selected by.
                  val role = invitation.gameRoleId.get
                  Store.run(
                    ApiClient.accept(invitation.gameId, invitation.challengeId, None, role),
                    busy,
                    invitationGone(invitation)
                  ) { _ =>
                      answeredInvitation(invitation)
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
                ApiClient.rejectInvitation(invitation.gameId, invitation.challengeId),
                busy,
                invitationGone(invitation)
              ) { _ =>
                  answeredInvitation(invitation)
              }
          }
        )
    }

    /** Both lists an answered invitation changes, reloaded the way their own buttons reload them.
      *
      * The invitation is gone either way — accepted or declined, the row goes — and an acceptance has also joined what
      * this player is waiting on. Dimmed and re-read rather than edited in place, for the reason
      * `reloadAcceptanceSections` says: a list that silently loses a row is a list that might have lost the wrong one.
      */
    private def answeredInvitation(invitation: Invitation): Unit = {
        refresh(refreshingInvitations, () => Store.reloadInvitations())
        reloadAcceptanceSections()
        // The challenge itself has changed — a seat taken, or one released for whoever else may
        // accept — so the game's list is stale if that screen is the one behind this.
        if (Store.page.now() == Store.Page.OneGame(invitation.gameId))
            Store.refreshChallenges(invitation.gameId)
    }

    /** What to do when answering an invitation is refused because it is no longer there to answer.
      *
      * The shape `alreadyStarted` has, and for the same reason: this row is on screen because the list was fetched
      * before the challenger withdrew it, or started the challenge, or before this player accepted from another tab.
      * 404 and 409 are both the server saying so, so both are treated as the news they are — the list is re-read, and
      * the banner says what happened in words rather than in the ids the server's message names.
      *
      * Anything else is left as `Store.run` reported it. A 5xx says nothing about whether the invitation is still
      * there, and a list that looked corrected on the strength of one would be worse than a plain failure.
      */
    private def invitationGone(invitation: Invitation)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) =>
            answeredInvitation(invitation)
            Store.error.set(Some("That invitation is no longer open. The list has been brought up to date."))
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
                      val namesById = games.map(game => game.gameId -> game.name).toMap
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
          }
        )
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
              if (game.isDefined) "Nothing is waiting on you in this game." else "Nothing is waiting on you."
            )
          )(matches => ul(matches.map(matchRow(_, showDue = true))))
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
              if (game.isDefined) "You are not in any matches of this." else "You are not in any matches."
            )
          )(matches => ul(matches.map(matchRow(_, showDue = false))))
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
                      val namesById = games.map(game => game.gameId -> game.name).toMap
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
            reloadAfterStart()
            // A started challenge is no longer offered either, so the game screen's list is as stale as
            // this row was — the same refresh the success path does, for the same reason.
            if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                Store.refreshChallenges(acceptance.gameId)
            Store.error.set(Some("That match has already started, so there is nothing left to back out of."))
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

    /* Which page of the finished matches is being looked at, counted from 0 at the most recent.
     *
     * Owned here rather than inside the section for the same reason `refreshingCompleted` is: the
     * section's own reload rebuilds nothing, but a sign-out and a re-fetch both change the list
     * under it, and a page number thrown away with the element would send the reader back to the
     * top every time. It is deliberately not reset by a refresh either -- somebody reading page
     * three and asking whether it is still true means to stay on page three.
     *
     * Never trusted as-is: what it holds can be past the end of a list that has since been
     * re-fetched shorter, so every use of it goes through `completedWindow`, which clamps. */
    private val completedPage: Var[Int] = Var(0)

    /** What the completed list shows right now: the rows on this page, the page they are (clamped), and how many
      * finished matches there are altogether.
      *
      * Derived rather than stored, so the held page number can be stale without anything on screen being wrong: a
      * reload that shortens the list moves the reader to the last page that exists instead of showing them a blank one,
      * and the clamp is in one place rather than at each of the three things that read it.
      */
    private val completedWindow: Signal[(Seq[MatchSummary], Int, Int)] =
        Store.completed.signal.combineWith(completedPage.signal).map { case (all, wanted) =>
            val page = clampCompletedPage(wanted, all.length)
            val from = page * Store.recentlyCompleted
            (all.slice(from, from + Store.recentlyCompleted), page, all.length)
        }

    /** The nearest page that exists to the one asked for: 0 when the list is empty or shorter than a page. */
    private def clampCompletedPage(wanted: Int, total: Int): Int =
        math.min(math.max(wanted, 0), math.max(0, (total - 1) / Store.recentlyCompleted))

    /** Moves the completed list a page towards the older matches (`by` positive) or the newer ones.
      *
      * Clamped against the list as it stands now rather than as it stood when the button was drawn, because a reload
      * may have landed in between — and clamped on the way in as well as on the way out, so stepping back from a page
      * that no longer exists lands beside it rather than somewhere further past the end.
      */
    private def stepCompleted(by: Int): Unit = {
        val total = Store.completed.now().length
        completedPage.set(clampCompletedPage(clampCompletedPage(completedPage.now(), total) + by, total))
    }

    /** The finished matches, whatever game they were played in, a page at a time.
      *
      * The list arrives most recently finished first, so the first page is the most recent matches — no sorting here,
      * and nothing that would disagree with the game screens, which show the same list filtered rather than a
      * differently ordered one.
      *
      * Paged rather than truncated because the whole list is already here: `completedMatches()` answers with all of
      * them, the older ones were simply being dropped on the floor, and paging through what is already loaded costs no
      * request at all. The page is still a page — a hundred finished matches at the bottom of the home screen is
      * something to scroll past, which is what the truncation was right about.
      */
    private def recentlyCompletedSection: HtmlElement =
        refreshableSection(
          "Recently Completed",
          refreshingCompleted,
          () => Store.reloadCompleted(),
          subsection = false
        )(
          listing(completedWindow.map(_._1), Store.loading(Store.Fetch.Completed))(
            p(cls := "empty", "Nothing finished yet.")
          )(matches => ul(matches.map(matchRow(_, showDue = false)))),
          completedPager
        )

    /** The two buttons and the position line under the completed list.
      *
      * One element that is always mounted and hidden while everything fits on a page, rather than one that appears and
      * disappears: the position line is a live region, and a live region created at the moment its text changes is
      * announced by nothing. Hidden, it says nothing either — which is right, because with a single page there is no
      * position to be in.
      *
      * "Newer" and "Older" rather than "Previous" and "Next": the list is ordered by when a match finished, and which
      * direction "next" goes in is exactly the thing the reader would have to work out.
      */
    private def completedPager: HtmlElement =
        div(
          cls := "pager",
          hidden <-- completedWindow.map { case (_, _, total) => total <= Store.recentlyCompleted },
          button(
            tpe := "button",
            disabled <-- completedWindow.map { case (_, page, _) => page == 0 },
            onClick --> (_ => stepCompleted(-1)),
            "Newer"
          ),
          // Which matches these are, in the terms the reader can see: rows counted from the most
          // recent, not a page number they would have to multiply out for themselves.
          div(
            cls := "detail",
            aria.live := "polite",
            child.text <-- completedWindow.map { case (rows, page, total) =>
                val from = page * Store.recentlyCompleted
                if (rows.isEmpty) s"$total finished" else s"${from + 1}–${from + rows.length} of $total"
            }
          ),
          button(
            tpe := "button",
            disabled <-- completedWindow.map { case (_, page, total) =>
                (page + 1) * Store.recentlyCompleted >= total
            },
            onClick --> (_ => stepCompleted(1)),
            "Older"
          )
        )

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
        li(
          cls := "row",
          button(
            tpe := "button",
            cls := "link",
            player.nickname,
            onClick --> (_ => Store.show(Store.Page.OnePlayer(player)))
          )
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
          refreshableSection(
            "Public Matches",
            refreshingPublicMatches,
            () => Store.reloadPublicMatches(player.playerId),
            subsection = false
          )(
            listing(Store.games.signal, Store.loading(Store.Fetch.Games))(p(cls := "empty", "No games yet."))(games =>
                ul(games.map(publicGameRow))
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
                        games.map(game => option(value := game.gameId.value.toString, game.name))
                      )
                    ),
                    button(
                      tpe := "button",
                      disabled <-- chosen.signal.map(_.isEmpty),
                      s"Offer ${invitee.nickname} a challenge",
                      onClick --> { _ =>
                          chosen.now().foreach { gameId =>
                              // Set before the navigation, so the form is built with it already in hand
                              // rather than opening blank and gaining a name a moment later.
                              Store.invitee.set(Some(invitee))
                              Store.showChallengeForm.set(true)
                              Store.show(Store.Page.OneGame(gameId))
                          }
                      }
                    )
                  )
              }
          }
        )
    }

    /** One game on a player's page: its name, how much of it they have played, and — when opened — the matches.
      *
      * The counts are drawn whether or not the row is open, and they are what makes a page of every game readable: a
      * row saying "0 being played, 0 finished" is one nobody needs to open, and that is most of them for most players.
      */
    private def publicGameRow(game: Game): HtmlElement = {
        val expanded = Store.expandedPublicGame.signal.map(_.contains(game.gameId))
        val running = Store.publicActive.signal.map(_.filter(_.gameId == game.gameId))
        val over = Store.publicCompleted.signal.map(_.filter(_.gameId == game.gameId))

        li(
          cls := "row",
          button(
            tpe := "button",
            cls := "toggle",
            // The state is announced rather than spelled into the label, so the label stays the name
            // of the game — which is what the reader is scanning the list for.
            aria.expanded <-- expanded,
            game.name,
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
            child.text <-- running.combineWith(over, Store.publicMatchesLoading.signal).map {
                case (active, finished, loading) if active.isEmpty && finished.isEmpty && loading => "loading…"
                case (active, finished, _) => s"${active.length} being played, ${finished.length} finished"
            }
          ),
          child <-- expanded.map {
              if (_)
                  div(
                    cls := "detail-panel",
                    h3("Current Matches"),
                    publicMatches(running, "None being played in public."),
                    h3("Completed Matches"),
                    publicMatches(over, "None finished in public.")
                  )
              else emptyNode
          }
        )
    }

    /* The same three states every other list on screen distinguishes -- still coming, empty, full --
     * and through the same `listing` helper. What stands in for the caller's `Fetch` flag is
     * `publicMatchesLoading`: a `Fetch` records that something answered once this session, and these
     * lists are re-fetched for every player whose page is opened. */
    private def publicMatches(matches: Signal[Seq[MatchSummary]], empty: String): Modifier[HtmlElement] =
        listing(matches, Store.publicMatchesLoading.signal)(p(cls := "empty", empty))(summaries =>
            ul(summaries.map(publicMatchRow))
        )

    /** One of another player's matches.
      *
      * Its own row rather than `matchRow`, which is written for the caller's own matches: it offers Play, Refresh and
      * Cancel, says "your turn", and shows the clocks a player spends — none of which mean anything on somebody else's
      * page, and the buttons would fail against a match the reader has no seat in. What is left is what a reader can
      * use: what the match is, when it happened, and who it is waiting on.
      */
    private def publicMatchRow(summary: MatchSummary): HtmlElement =
        li(
          cls := "row",
          // The game's name is the row this sits under, so what names the match here is what its
          // creator called it — and an unnamed match is said by its id rather than by a blank line.
          div(
            cls := "title",
            if (summary.description.trim.nonEmpty) summary.description else s"match ${summary.matchId.value}"
          ),
          div(cls := "detail", s"started ${Format.date(summary.start)}"),
          if (summary.cancelled) div(cls := "detail", "cancelled by its creator") else emptyNode,
          summary.completedAt
              .map(when => div(cls := "detail", s"completed ${Format.date(when)}"))
              .getOrElse(emptyNode),
          // Whose move it is, for a match still being played. Named, as on the caller's own rows,
          // and never "your turn": a turn on this page is somebody else's by construction.
          if (summary.completed || summary.cancelled) emptyNode
          else if (summary.whoseTurn.nonEmpty) div(cls := "detail", s"waiting for ${summary.whoseTurn.mkString(", ")}")
          else div(cls := "detail", "waiting for the other players"),
          // The board, for anyone who cares to look: this is what being public gets you, and the
          // engine issued the url when the match was created. Straight off the summary, with no
          // request behind the click — unlike "View final state" on the player's own rows, which has
          // to ask for a play url that is not on a summary.
          //
          // Absent when there is no url. A match is only listed here if it is public, but an engine
          // that serves no spectator page answers with none, and a button that opens nothing is
          // worse than no button.
          summary.publicUrl
              .map(url =>
                  button(
                    tpe := "button",
                    cls := "link",
                    "Watch",
                    onClick --> (_ => dom.window.open(url, "_blank", "noopener,noreferrer"))
                  )
              )
              .getOrElse(emptyNode)
        )

    private def matchRow(summary: MatchSummary, showDue: Boolean): HtmlElement =
        li(
          cls := "row",
          div(cls := "title", summary.gameName),
          div(cls := "detail", summary.description),
          if (showDue) summary.due.map(countdown).getOrElse(emptyNode)
          else emptyNode,
          // The rule behind that deadline, which the deadline itself does not give away: the same
          // "due" line comes of a per-turn limit and of a budget nearly spent, and they call for
          // opposite decisions. Shown only while the match is being played — the terms of a game
          // that is over are history nobody can act on, and the result table is what that row is
          // for.
          if (summary.completed || summary.cancelled) emptyNode
          else timeLimitDetail(summary.timeLimit, summary.timeLimitKind, summary.timeLimitUnit),
          // `pending` means it is this player's turn: it is the flag the "Your turn" list selects
          // on, so saying it there would repeat the heading on every row. Said here only for the
          // matches still being played — a finished match has no turn to be waiting for.
          if (showDue || summary.completed || summary.cancelled) emptyNode
          else if (summary.pending) div(cls := "pending", "your turn")
          // Named, rather than "the other players": in a match of three it is the difference
          // between knowing who to chase and knowing only that it is not you. The old wording is
          // still the fallback for a match matchmaker has not yet heard a turn for.
          else if (summary.whoseTurn.nonEmpty) div(cls := "detail", s"waiting for ${summary.whoseTurn.mkString(", ")}")
          else div(cls := "detail", "waiting for the other players"),
          // The clock on the turn now being taken, whoever is taking it. On the "Your turn" list it
          // is the caller's own and is drawn above from `due`; here it may be somebody else's, which
          // is the more useful thing to know about a match you cannot move in.
          if (showDue || summary.completed || summary.cancelled) emptyNode
          else summary.turnDue.map(countdown).getOrElse(emptyNode),
          // Under a chess clock the deadline above is only half the story: it says when this turn
          // runs out, not how much either player has to last the rest of the match on. Empty for
          // every other kind of limit, so nothing is drawn.
          if (showDue || summary.completed || summary.cancelled || summary.clocks.isEmpty) emptyNode
          else clockTable(summary.clocks),
          // A cancelled match is over and has no result, so it sits in the completed list; without
          // this it would be indistinguishable from one that was played to an end.
          if (summary.cancelled) div(cls := "detail", "cancelled by its creator") else emptyNode,
          // When it ended. The completed list is ordered by this, so the row says what it is sorted
          // on; a cancelled match has no completion time and simply says nothing here.
          summary.completedAt
              .map(when =>
                  div(
                    cls := "detail",
                    "completed ",
                    // The rendered date is a day with no zone on it; `dateTime` carries the instant it
                    // was trimmed from, so the date is machine-readable as well as legible.
                    timeTag(
                      htmlAttr("datetime", com.raquo.laminar.codecs.StringAsIsCodec) := when.toString,
                      Format.date(when)
                    )
                  )
              )
              .getOrElse(emptyNode),
          // Play and Refresh are for a match still being played. A finished one has no turn to take
          // and nothing left for the engine to tell us, so it shows how it ended instead.
          if (summary.completed || summary.cancelled)
              div(
                resultTable(summary),
                // A finished match still has a board, and the engine keeps it: this is how a player
                // goes and looks at how it ended. Fetched the same way "Play" fetches it — the urls
                // live on the match, not on the summary — and `publicUrl` is the fallback for a match
                // whose play url the engine has since stopped honouring for a game that is over.
                if (summary.completed)
                    busyButton("View final state", classes = Some("link")) { busy =>
                        Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                            m.playUrl.orElse(m.publicUrl) match {
                                case Some(url) => dom.window.open(url, "_blank", "noopener,noreferrer")
                                case None      => Store.error.set(Some("This match has no url to view."))
                            }
                        }
                    }
                else emptyNode
              )
          else
              div(
                // The play url lives on the match rather than the summary, and is the game engine's,
                // not matchmaker's — so it is fetched when asked for and opened directly.
                busyButton("Play", classes = Some("link")) { busy =>
                    Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                        m.playUrl match {
                            case Some(url) => dom.window.open(url, "_blank", "noopener,noreferrer")
                            case None      => Store.error.set(Some("This match has no play url yet."))
                        }
                    }
                },
                // Step 4 of the engine flow: any participant may ask matchmaker to re-check with the
                // engine, which is what recovers from a callback that never arrived.
                busyButton("Refresh", classes = Some("link")) { busy =>
                    Store.run(ApiClient.refreshMatch(summary.gameId, summary.matchId), busy)(
                      reloadAfterMatchRefresh
                    )
                }
              ),
          // Nothing to mute about a match that is over, so this goes with Play and Refresh rather
          // than with the result table.
          if (summary.completed || summary.cancelled) emptyNode else matchNotifications(summary),
          // Only the creator's, and only while there is still something to call off. The engine is
          // not told — its board stays playable — so the confirmation says what actually happens.
          if (summary.isCreator && !summary.completed && !summary.cancelled)
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
                  // `withDefault = false`, as on the game form and for the same reason: this match's
                  // seat answers every kind itself and there is nothing under it to defer to. The
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
    private def gamePage(gameId: GameId): HtmlElement =
        div(
          // Through `Store.game`, which answers from the active games and from the deactivated one
          // this screen may have been linked to -- an invitation outlives its game being deactivated,
          // and reading the active list alone left such a page saying "Loading…" for ever. The two
          // states are still told apart: "Loading…" while something is on its way, and a sentence
          // saying so once nothing is.
          child <-- Store
              .game(gameId)
              .combineWith(
                Store.loading(Store.Fetch.Games).combineWith(Store.loadingUnlistedGame.signal).map(_ || _)
              )
              .map {
                  case (None, true) => p(cls := "empty", "Loading…")
                  case (None, false) =>
                      p(
                        cls := "empty",
                        aria.live := "polite",
                        "That game is not available. It may have been withdrawn."
                      )
                  case (Some(game), _) =>
                      div(
                        h2(game.name),
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
                        gameHistory(game)
                      )
              }
        )

    /** The admin's edit form for a game, opened from a link on the game's own screen. Nothing for anyone else: the
      * server answers a non-admin with a 403, so the link is not there to press.
      */
    private def editGamePanel(game: Game): HtmlElement =
        div(
          child <-- currentPlayer.combineWith(Store.editingGame.signal).map {
              case (Some(player), editing) if player.isAdmin =>
                  div(
                    button(
                      cls := "link",
                      aria.expanded := editing.contains(game.gameId),
                      if (editing.contains(game.gameId)) "Done editing" else "Edit game",
                      onClick --> { _ =>
                          Store.editingGame
                              .update(current => if (current.contains(game.gameId)) None else Some(game.gameId))
                      }
                    ),
                    // Keyed on the game so that the form is rebuilt when a different game is opened:
                    // its fields are initialised from `game` once, not bound to it.
                    if (editing.contains(game.gameId)) gameForm(Some(game)) else emptyNode
                  )
              case _ => emptyNode
          }
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
          listing(
            Store.completed.signal.map(_.filter(_.gameId == game.gameId)),
            Store.loading(Store.Fetch.Completed)
          )(p(cls := "empty", "You have not finished a match of this yet."))(matches =>
              ul(matches.map(matchRow(_, showDue = false)))
          )
        )

    /** The admin's add-a-game screen, reached from the menu. The same form the edit link opens, with nothing to start
      * from.
      */
    private def newGamePage: HtmlElement =
        div(
          h2("Add a Game"),
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin => newGameForm
              case _                              => p(cls := "empty", "Only an administrator can add a game.")
          }
        )

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
    private case class RoleDraft(gameRoleId: GameRoleId, name: Var[String], optional: Var[Boolean])
    private case class ParameterDraft(name: Var[String], values: Var[String], default: Var[String])

    private def emptyRole: RoleDraft = RoleDraft(GameRoleId.unassigned, Var(""), Var(false))
    private def emptyParameter: ParameterDraft = ParameterDraft(Var(""), Var(""), Var(""))

    private def draftOf(role: GameRole): RoleDraft =
        RoleDraft(role.gameRoleId, Var(role.name), Var(role.optional))

    private def draftOf(parameter: GameParameter[String]): ParameterDraft =
        ParameterDraft(
          Var(parameter.name),
          Var(parameter.values.map(_.value).mkString(", ")),
          Var(parameter.defaultValue.getOrElse(""))
        )

    /** The possible values of a parameter, as typed: one comma-separated list, because a parameter with three values is
      * a sentence an admin can type and a list of three inputs is not.
      */
    private def splitValues(raw: String): Seq[String] =
        raw.split(',').map(_.trim).filter(_.nonEmpty).toSeq.distinct

    private def roleEditor(roles: Var[List[RoleDraft]]): HtmlElement =
        div(
          h4("Roles"),
          p(
            cls := "detail",
            "Every seat in a match names a role, so a game needs at least one. An optional role is one " +
                "a match does not wait to see filled before it can start. A role that already exists can " +
                "be renamed but not removed — acceptances and played matches name it, so retire one by " +
                "making it optional."
          ),
          children <-- roles.signal.map(_.map { draft =>
              div(
                cls := "row",
                // A caption per row would repeat "role name" down the whole editor, so these repeated
                // rows carry their name rather than showing it. The placeholder stays as the visible
                // hint it always was.
                input(
                  aria.label := "role name",
                  placeholder := "role name",
                  controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)
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

    private def parameterEditor(parameters: Var[List[ParameterDraft]]): HtmlElement =
        div(
          h4("Parameters"),
          p(
            cls := "detail",
            "How the game engine is configured when a match is created. A parameter's default has to " +
                "be one of its values, and a game may have none at all."
          ),
          children <-- parameters.signal.map(_.map { draft =>
              div(
                cls := "row",
                input(
                  aria.label := "parameter name",
                  placeholder := "parameter name",
                  controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)
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
        val all = drafts.map(d => (d.gameRoleId, d.name.now().trim, d.optional.now()))
        // A blank new row is one the admin added and did not fill in, and is dropped. A blank
        // existing row is a role whose name has been cleared -- dropping that would ask the server to
        // delete a role, which it refuses, so it is answered here as what it is.
        val (blank, named) = all.partition(_._2.isEmpty)
        if (blank.exists(_._1 != GameRoleId.unassigned))
            Left("A role that already exists cannot be left without a name.")
        else if (named.isEmpty) Left("A game needs at least one role: every player accepting a challenge takes one.")
        else if (named.map(_._2).distinct.sizeIs != named.size) Left("Two roles cannot have the same name.")
        else Right(named.map((id, name, optional) => GameRole(id, GameId.unassigned, name, optional)))
    }

    private def parametersOf(drafts: List[ParameterDraft]): Either[String, Seq[GameParameter[String]]] = {
        val named = drafts
            .map(d => (d.name.now().trim, splitValues(d.values.now()), d.default.now().trim))
            .filter(_._1.nonEmpty)
        val badDefault = named.find((_, values, default) => default.nonEmpty && !values.contains(default))
        if (named.map(_._1).distinct.sizeIs != named.size) Left("Two parameters cannot have the same name.")
        else
            badDefault match {
                case Some((name, _, default)) =>
                    Left(s"Parameter '$name' has default '$default', which is not one of its values.")
                case None =>
                    Right(named.map { (name, values, default) =>
                        GameParameter[String](
                          GameId.unassigned,
                          GameParameterId(0),
                          name,
                          Option(default).filter(_.nonEmpty),
                          values.map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
                        )
                    })
            }
    }

    private def newGameForm: HtmlElement = gameForm(None)

    /** The admin's game form, for creating one (`existing` is None) or editing one.
      *
      * The two are the same form because a game is the same thing either way, and every field is editable in both:
      * name, description, url, whether characters are required, the roles and the parameters. There is no player count
      * to set — a game's roles are its seats, so adding one in [[roleEditor]] is how a game gets bigger. What edit
      * cannot do is delete a role.
      *
      * Two fields are never shown. `externalId` is the game's own credential: kept as it is when editing, generated
      * when creating, and in neither case something to type. `active` is not on this form at all, so editing preserves
      * it and creating sets it true.
      */
    private def gameForm(existing: Option[Game]): HtmlElement = {
        val name = Var(existing.map(_.name).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val url = Var(existing.map(_.url).getOrElse(""))
        // Plain by default: requiring characters is the additional commitment, so it is the box an
        // admin ticks rather than the one they have to remember to untick.
        val gameType: Var[GameType] = Var(existing.map(_.gameType).getOrElse(GameType.Plain))
        // What happens when a player takes too long over a turn. A dropdown rather than a checkbox
        // because Forfeit is the first of several planned actions, not the only one there will ever
        // be — the control does not have to change when the second arrives, only the enum.
        val timeoutAction: Var[TimeoutAction] =
            Var(existing.map(_.timeoutAction).getOrElse(TimeoutAction.Forfeit))
        // What this game's players are emailed about unless they say otherwise. Held as
        // preferences rather than as defaults because that is what the controls edit -- an
        // unanswered question -- and a new game starts with all eight unanswered on purpose:
        // these are the end of the chain every player's settings fall back to, so they are the
        // admin's to decide rather than something to inherit from a form's initial state. The
        // submit button stays disabled until all eight are answered.
        val notifications: Var[NotificationPreferences] =
            Var(existing.map(_.notifications.asPreferences).getOrElse(NotificationPreferences.unset))
        // A new game starts with one empty role, because it cannot be created without one, and no
        // parameters, because plenty of games have none. An existing one starts with what it has.
        val roles = Var(existing.map(_.roles.map(draftOf).toList).getOrElse(List(emptyRole)))
        val parameters = Var(
          existing.map(_.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList).getOrElse(Nil)
        )

        div(
          cls := "card",
          field("Name", input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          field("Description", input(controlled(value <-- description.signal, onInput.mapToValue --> description))),
          field("Game engine url", input(tpe := "url", controlled(value <-- url.signal, onInput.mapToValue --> url))),
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
          field(
            "When a turn runs out",
            select(
              onChange.mapToValue --> (code => timeoutAction.set(TimeoutAction.fromCode(code))),
              value <-- timeoutAction.signal.map(_.code),
              TimeoutAction.values.toSeq.map(action => option(value := action.code, action.label))
            )
          ),
          roleEditor(roles),
          parameterEditor(parameters),
          div(
            cls := "card",
            h3("Notification Preferences"),
            p(
              cls := "detail",
              "What this game's players are emailed about unless they choose otherwise. " +
                  "Every question needs an answer: these are what a player's own settings fall back to."
            ),
            // No "Use Default" here, because this is the default: there is nothing below a game
            // for it to defer to.
            Notifications.editor(notifications, withDefault = false)
          ),
          busyButton(
            if (existing.isDefined) "Save Changes" else "Create Game",
            disabledWhen = name.signal
                .combineWith(notifications.signal)
                .map { case (gameName, chosen) => gameName.trim.isEmpty || chosen.unsaid.nonEmpty }
          ) { busy =>
              val drafted = for {
                  roleModels <- rolesOf(roles.now())
                  parameterModels <- parametersOf(parameters.now())
                  // The button is disabled while any is unanswered, so this is the same rule said
                  // where it can be enforced rather than only shown -- and it is what turns eight
                  // tri-state controls into the eight NOT NULL columns of `game`.
                  notificationDefaults <- notifications
                      .now()
                      .complete
                      .toRight("Answer every notification question before saving the game.")
              } yield (roleModels, parameterModels, notificationDefaults)

              drafted match {
                  case Left(problem) => Store.error.set(Some(problem))
                  case Right((roleModels, parameterModels, notificationDefaults)) =>
                      val game = Game(
                        // Unassigned means create and the server assigns the real id — the same sentinel
                        // the challenge form uses; a real id means update that game.
                        gameId = existing.map(_.gameId).getOrElse(GameId.unassigned),
                        gameType = gameType.now(),
                        name = name.now().trim,
                        description = description.now().trim,
                        url = url.now().trim,
                        // A game nobody can see is not what "create a game" means, and `refreshGames` only
                        // asks for active ones — creating it inactive would look like the button did nothing.
                        // Editing leaves it as it was, since this form has no control for it.
                        active = existing.map(_.active).getOrElse(true),
                        roles = roleModels,
                        parameters = parameterModels,
                        // The game's own shared secret, used to authorize requests the game makes on its own
                        // behalf. Generated rather than typed: it is a credential, and one an admin inventing
                        // it by hand would invent badly. An edit keeps the one the game already has —
                        // regenerating it would silently lock the game engine out.
                        externalId = existing.map(_.externalId).getOrElse(Pkce.newSecret()),
                        timeoutAction = timeoutAction.now(),
                        notifications = notificationDefaults
                      )

                      Store.run(ApiClient.createGame(game), busy) { saved =>
                          if (existing.isEmpty) {
                              name.set("")
                              description.set("")
                              url.set("")
                              roles.set(List(emptyRole))
                              parameters.set(Nil)
                              notifications.set(NotificationPreferences.unset)
                              // Straight to the game that was just created: it is now in the menu, and its own
                              // screen is where anything else is done with it.
                              Store.show(Store.Page.OneGame(saved.gameId))
                          } else {
                              // Re-drafted from what came back, so that roles added by this save carry the ids
                              // the insert gave them — without which saving twice would ask to add them again.
                              roles.set(saved.roles.map(draftOf).toList)
                              parameters.set(
                                saved.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList
                              )
                              Store.editingGame.set(None)
                          }
                          Store.refreshGames()
                      }
              }
          }
        )
    }

    /** What can be played in this game right now: the open challenges, and the form that offers one. A game that needs
      * characters needs one of this player's before either is possible, so that form stands in for both until there is
      * one.
      */
    private def gameChallenges(game: Game): HtmlElement =
        div(
          child <-- (if (game.gameType == GameType.Plain)
                         currentPlayer.map {
                             case None         => p(cls := "empty", "Loading…")
                             case Some(player) => challengePanel(game, player, None)
                         }
                     else
                         currentPlayer.combineWith(Store.charactersByGame.signal).map {
                             case (None, _) => p(cls := "empty", "Loading…")
                             case (Some(player), byGame) =>
                                 byGame.get(game.gameId) match {
                                     case None => p(cls := "empty", "Loading…")
                                     // A character is needed before this player can either offer or accept a
                                     // challenge, so there is nothing to show until there is one.
                                     case Some(Nil) => characterForm(game, player)
                                     case Some(characters) =>
                                         challengePanel(game, player, Some(characters.head.characterId))
                                 }
                         })
        )

    private def characterForm(game: Game, player: Player): HtmlElement = {
        val name = Var("")
        val description = Var("")

        div(
          cls := "card",
          h3(s"Create Your Character for ${game.name}"),
          p("You need a character in this game before you can offer or accept a challenge."),
          field("Name", input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          field("Description", input(controlled(value <-- description.signal, onInput.mapToValue --> description))),
          busyButton("Create Character", disabledWhen = name.signal.map(_.trim.isEmpty)) { busy =>
              val created =
                  ApiClient.createCharacter(game.gameId, name.now().trim, description.now().trim, player.externalId)
              Store.run(created, busy)(_ => Store.refreshCharacters(game.gameId))
          }
        )
    }

    private def challengePanel(game: Game, player: Player, characterId: Option[CharacterId]): HtmlElement = {
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
                        button(
                          aria.expanded <-- Store.showChallengeForm.signal,
                          child.text <-- Store.showChallengeForm.signal.map(if (_) "Close" else "Create Challenge"),
                          onClick --> { _ =>
                              // Closing the form abandons the invitation it was opened for. Left set, it
                              // would address the next challenge offered from this screen to whoever was
                              // looked at before -- and nothing on the closed form would say so.
                              if (Store.showChallengeForm.now()) Store.invitee.set(None)
                              Store.showChallengeForm.update(!_)
                          }
                        ),
                        // Built from the slot as it stands when the form opens: a challenge is composed
                        // for one player, and one being looked up while the form is open is a different
                        // challenge, offered by opening it again.
                        child <-- Store.showChallengeForm.signal.map {
                            if (_) newChallengeForm(game, player, characterId, Store.invitee.now()) else emptyNode
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
                          else ul(available.map(openChallengeRow(game, _, characterId)))
                        )
                      )
              }
          }
        )
    }

    private def myChallengeRow(game: Game, summary: ChallengeSummary): HtmlElement = {
        val challenge = summary.challenge
        li(
          cls := "row",
          div(cls := "title", challenge.message),
          div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
          timeLimitDetail(challenge),
          if (challenge.isPublic) div(cls := "detail", "public") else emptyNode,
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
          else div(cls := "detail", s"waiting for ${unfilledRoles(game, summary).map(_.name).mkString(", ")}"),
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
      */
    private def invitedList(game: Game, summary: ChallengeSummary): HtmlElement =
        div(
          child <-- Store.nicknames.signal.map { known =>
              if (summary.invitations.isEmpty) emptyNode
              else
                  div(
                    cls := "detail-panel",
                    h4("Invited"),
                    ul(
                      summary.invitations.map { invitation =>
                          val who =
                              known.getOrElse(invitation.playerId, s"player ${invitation.playerId.value}")
                          val seat = invitation.gameRoleId
                              .flatMap(role => game.roles.find(_.gameRoleId == role))
                              .fold("any seat that is free")(role => role.name)

                          li(
                            cls := "row",
                            div(cls := "title", who),
                            div(cls := "detail", s"holding $seat"),
                            busyButton("Revoke", classes = Some("link")) { busy =>
                                Store.run(
                                  ApiClient
                                      .revokeInvitation(
                                        game.gameId,
                                        summary.challenge.challengeId,
                                        invitation.playerId
                                      ),
                                  busy,
                                  // A 409 is the server saying that player has accepted since this list
                                  // was drawn, and a 404 that the invitation is already gone. Either way
                                  // the row is stale, so the list is re-read rather than corrected here.
                                  invitationStale(game.gameId)
                                )(_ => Store.refreshChallenges(game.gameId))
                            }
                          )
                      }
                    )
                  )
          }
        )

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
        val held = summary.invitations.flatMap(_.gameRoleId).toSet
        val offerable = freeRoles(game, summary).filterNot(role => held.contains(role.gameRoleId))

        // Nobody who is already invited, and not the challenger themselves: the first is the
        // invitation table's primary key and the second a rule of the service, so both come back as
        // errors rather than as invitations. A name that cannot be acted on is better left out of the
        // results than shown with a button that fails.
        val alreadyAsked = summary.invitations.map(_.playerId).toSet + summary.challenge.challenger

        div(
          button(
            tpe := "button",
            cls := "link",
            aria.expanded <-- open.signal,
            child.text <-- open.signal.map(if (_) "Close" else "Invite a player"),
            onClick --> { _ =>
                // Emptied on the way out, so re-opening it is a fresh question rather than the last
                // one's half-finished answer.
                if (open.now()) { prefix.set(""); found.set(None); chosen.set(None); seat.set(None) }
                open.update(!_)
            }
          ),
          child <-- open.signal.map { showing =>
              if (!showing) emptyNode
              else
                  div(
                    cls := "detail-panel",
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
                                  Store.run(ApiClient.searchPlayers(prefix.now().trim), busy) { result =>
                                      // Remembered for the invited list above, which has ids and no
                                      // names of its own.
                                      Store.remember(result.players)
                                      found.set(Some(result))
                                  }
                              },
                              child <-- found.signal.map {
                                  case None => emptyNode
                                  case Some(result) =>
                                      val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                                      if (askable.isEmpty)
                                          p(
                                            cls := "empty",
                                            aria.live := "polite",
                                            "Nobody new by that name. Anyone already invited is not listed again."
                                          )
                                      else
                                          ul(
                                            // A live region: the list appears without the page
                                            // reloading, and a reader who pressed Search is waiting to
                                            // be told what came back.
                                            aria.live := "polite",
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
                            div(
                              p(cls := "detail", aria.live := "polite", s"Inviting ${candidate.nickname}."),
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
                                  offerable.map(role => option(value := role.gameRoleId.value.toString, role.name))
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
                              busyButton("Send the invitation") { busy =>
                                  Store.run(
                                    ApiClient.invite(
                                      game.gameId,
                                      summary.challenge.challengeId,
                                      Invite(candidate.playerId, seat.now())
                                    ),
                                    busy,
                                    invitationStale(game.gameId)
                                  )(_ => Store.refreshChallenges(game.gameId))
                              },
                              button(
                                tpe := "button",
                                cls := "link",
                                "Somebody else",
                                onClick --> { _ =>
                                    chosen.set(None); seat.set(None)
                                }
                              )
                            )
                    }
                  )
          }
        )
    }

    /** What to do when inviting or revoking is refused because the challenge has moved on.
      *
      * 409 covers both races this panel has: a seat promised or accepted since the picker was drawn, and a player who
      * accepted between the list being fetched and a revoke being pressed. 404 is the challenge or the invitation
      * already gone. In every one of those the list on screen is what is wrong, so it is re-read — and the server's own
      * message is left standing, because it is the one that says which of them happened.
      */
    private def invitationStale(gameId: GameId)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) => Store.refreshChallenges(gameId)
        case _                      => ()
    }

    private def openChallengeRow(
        game: Game,
        summary: ChallengeSummary,
        characterId: Option[CharacterId]
    ): HtmlElement = {
        val challenge = summary.challenge
        // Only the roles nobody has claimed yet: accepting as a taken role is refused by the server,
        // and there is no reason to offer a choice that cannot work. A challenge with none left is
        // one that is full, and gets no Accept at all.
        val free = freeRoles(game, summary)
        val role = Var(free.headOption.map(_.gameRoleId))
        li(
          cls := "row",
          div(cls := "title", challenge.message),
          div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
          timeLimitDetail(challenge),
          roleSelect(free, role),
          if (free.isEmpty) div(cls := "detail", "every role is taken")
          else
              busyButton("Accept") { busy =>
                  val chosen = role.now().getOrElse(free.head.gameRoleId)
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
        timeLimitDetail(challenge.timeLimit, challenge.timeLimitKind, challenge.timeLimitUnit)

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
        unit: TimeLimitUnit
    ): HtmlElement =
        div(
          cls := "detail",
          limit match {
              case None => "no time limit"
              case Some(limit) =>
                  kind match {
                      case TimeLimitKind.PerTurn => s"${Format.duration(limit, unit)} per turn"
                      case TimeLimitKind.Total   => s"${Format.duration(limit, unit)} each for the whole match"
                  }
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
              choices.map(r => option(value := r.gameRoleId.value.toString, r.name))
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
        characterId: Option[CharacterId],
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
        // A challenge is its challenger's own acceptance, so it names a role like any other. Nothing
        // has been claimed yet, so every role of the game is on offer and the first stands selected.
        val role = Var(game.roles.headOption.map(_.gameRoleId))
        // Whether anybody may accept this, or only the players invited to it. Open unless somebody is
        // being invited, which is the case the flag exists for -- a challenge with nobody invited and
        // closed is one nobody can accept at all, and the server refuses it.
        val isOpen = Var(invitee.isEmpty)
        // The seat being held for the invited player, or `None` for "any that is still free". Their
        // own choice of role is what `None` leaves them, and it is the default: holding a particular
        // seat is the stronger statement of the two, so it is the one the challenger has to make.
        val inviteeRole = Var(Option.empty[GameRoleId])

        div(
          cls := "card",
          h3("Offer a Challenge"),
          field("Message", input(controlled(value <-- message.signal, onInput.mapToValue --> message))),
          roleSelect(game.roles, role),
          // Everything about the one player this is being offered to, and nothing at all when it is
          // being offered to whoever comes along.
          invitee.fold(emptyNode) { asked =>
              div(
                cls := "detail-panel",
                p(cls := "detail", s"Inviting ${asked.nickname}."),
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
                    children <-- role.signal.map(mine =>
                        game.roles
                            .filterNot(r => mine.contains(r.gameRoleId))
                            .map(r => option(value := r.gameRoleId.value.toString, r.name))
                    )
                  )
                ),
                // Ticked leaves the challenge open to anybody, with the invitation as a nudge; unticked
                // -- the default when inviting -- makes the invitations the only way in.
                label(
                  input(
                    tpe := "checkbox",
                    controlled(checked <-- isOpen.signal, onClick.mapToChecked --> isOpen)
                  ),
                  "Anyone may accept this, not only the players invited"
                )
              )
          },
          // The number and the unit it is in, in one field: they are one answer, and a caption
          // over each would read as two questions.
          field(
            "Time Limit",
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
                TimeLimitUnit.values.toSeq.map(unit => option(value := unit.code, unit.label))
              )
            )
          ),
          p(cls := "detail", "Leave it blank for no time limit."),
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
          child <-- timeLimit.signal.map { raw =>
              if (raw.trim.isEmpty || amountOf(raw).isDefined) emptyNode
              else p(cls := "empty", aria.live := "polite", "A time limit is a whole number, more than zero.")
          },
          // Public means anyone may watch the match, which the game engine implements by issuing a
          // url that needs no sign-in. It is decided here because it is a property of the game being
          // offered, not of any one player's part in it.
          label(
            input(
              tpe := "checkbox",
              controlled(checked <-- isPublic.signal, onClick.mapToChecked --> isPublic)
            ),
            "Public"
          ),
          // The same shape as the Public box above it: a box and a short caption, which is all
          // either of them needs.
          label(
            input(
              tpe := "checkbox",
              controlled(checked <-- autoStart.signal, onClick.mapToChecked --> autoStart)
            ),
            "Start when all seats filled"
          ),
          // The challenger changing their own role can leave the invitee holding the seat just taken,
          // which the server refuses. Released rather than refused here: the challenger's choice is
          // the one they just made, and the invitation falls back to "any seat that is free".
          role.signal --> { mine => if (mine.exists(inviteeRole.now().contains)) inviteeRole.set(None) },
          busyButton(
            "Create Challenge",
            // A game with no roles at all has nothing an acceptance could name, so no challenge for
            // it can be created. The server refuses one; this keeps the button from offering it.
            disabledWhen = message.signal.combineWith(role.signal, timeLimit.signal).map { case (m, r, limit) =>
                m.trim.isEmpty || r.isEmpty || (limit.trim.nonEmpty && amountOf(limit).isEmpty)
            }
            // `foreach` rather than a fallback role: with no role there is no challenge to make, and
            // the disabled button above is what keeps that from being reachable.
          ) { busy =>
              role.now().foreach { chosen =>
                  // The server assigns the id; this is the same unassigned-sentinel convention the
                  // service layer uses on create.
                  val challenge: Challenge = characterId match {
                      case Some(cid) =>
                          CharacterChallenge(
                            challengeId = ChallengeId(0),
                            challenger = player.playerId,
                            message = message.now().trim,
                            start = None,
                            timeLimit = durationOf(timeLimit.now(), timeLimitUnit.now()),
                            settings = "{}",
                            gameId = game.gameId,
                            characterId = cid,
                            isPublic = isPublic.now(),
                            gameRoleId = chosen,
                            timeLimitKind = timeLimitKind.now(),
                            timeLimitUnit = timeLimitUnit.now(),
                            autoStart = autoStart.now(),
                            isOpen = isOpen.now()
                          )
                      case None =>
                          PlainChallenge(
                            challengeId = ChallengeId(0),
                            challenger = player.playerId,
                            message = message.now().trim,
                            start = None,
                            timeLimit = durationOf(timeLimit.now(), timeLimitUnit.now()),
                            settings = "{}",
                            gameId = game.gameId,
                            isPublic = isPublic.now(),
                            gameRoleId = chosen,
                            timeLimitKind = timeLimitKind.now(),
                            timeLimitUnit = timeLimitUnit.now(),
                            autoStart = autoStart.now(),
                            isOpen = isOpen.now()
                          )
                  }

                  // One request rather than a create and then an invite: the server validates the
                  // invitations against the challenge and against each other, and a closed challenge
                  // created on its own would exist, briefly, as one nobody could accept.
                  val invitations =
                      invitee.map(asked => Invite(asked.playerId, inviteeRole.now())).toSeq

                  Store.run(ApiClient.createChallenge(challenge, invitations), busy) { _ =>
                      message.set("")
                      timeLimit.set("")
                      timeLimitUnit.set(TimeLimitUnit.Minutes)
                      timeLimitKind.set(TimeLimitKind.PerTurn)
                      autoStart.set(true)
                      // The challenge it was open for now exists and is in the list below it. The
                      // invitation went with it, so the slot is spent -- the next challenge offered
                      // from this screen is not addressed to the same player by default.
                      Store.showChallengeForm.set(false)
                      Store.invitee.set(None)
                      Store.refreshChallenges(game.gameId)
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

    /** A time limit typed in some unit, as a `Duration`. The unit itself travels with the challenge, so that the limit
      * is said back in the unit it was offered in rather than in whichever one happens to divide it.
      */
    private def durationOf(raw: String, unit: TimeLimitUnit): Option[java.time.Duration] =
        amountOf(raw).map(amount => unit.perUnit.multipliedBy(amount.toLong))

    private def currentPlayer: Signal[Option[Player]] = Store.currentPlayer
}

/** Formatting that has to be readable rather than exact. */
object Format {

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
