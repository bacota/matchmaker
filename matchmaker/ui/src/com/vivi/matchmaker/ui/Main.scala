package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** Entry point.
  *
  * The whole UI is one page with no router: every screen in `ui.txt` is a section of it, and the
  * only URL that ever matters is the one Cognito redirects back to.
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
        case Some(signIn) =>
          signIn.onComplete { outcome =>
            outcome.failed.foreach(Store.report)
            Store.signedIn.set(Auth.isSignedIn)
            if (Auth.isSignedIn) Store.loadAll()
          }
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
    * Every one of these calls the API, and some of those calls are slow enough that a click with
    * no visible answer reads as a button that did nothing — so the button disables itself and
    * shows a spinner until the request comes back. The handler is handed the flag to pass to
    * `Store.run`, which clears it however the request ends: a failure re-enables the button
    * rather than leaving it spinning on an answer that already arrived.
    *
    * `disabledWhen` is combined here rather than passed in as another `disabled` binding, since
    * two bindings writing the same property would fight over it.
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
    * Wrapping rather than `for`/`id`: it needs no id to be unique across a page that renders the
    * same form more than once. A placeholder is not a label — it is a hint that disappears at the
    * first keystroke, and leaves a screen reader with an unnamed box — so every field that had
    * only a placeholder gets one of these instead.
    */
  private def field(caption: String, control: HtmlElement): HtmlElement =
    label(cls := "field", caption, control)

  /** A section with a refresh button of its own.
    *
    * Every list here can go stale while it is being looked at — somebody else accepts a
    * challenge, an engine finishes a match — and the only remedy used to be reloading the page,
    * which throws away every other section to reload one. This re-fetches just this section's
    * list and leaves the rest of the screen alone.
    *
    * The body dims and comes back while the request is in flight, so it is clear which part of
    * the page the button acted on. A fast request would flash too briefly to register, so the
    * dimming is held for a moment; a slow one holds it until the answer arrives.
    */
  private def refreshableSection(
      heading: String,
      reload: () => Future[Unit],
      subsection: Boolean = false
  )(content: Modifier[HtmlElement]*): HtmlElement = {
    val refreshing = Var(false)

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
  }

  /** How long the dimming is held for, whether or not the request takes that long. Short enough
    * not to be in the way, long enough to be seen.
    */
  private val blinkMillis = 400L

  private def refresh(refreshing: Var[Boolean], reload: () => Future[Unit]): Unit =
    if (!refreshing.now()) {
      refreshing.set(true)
      val startedAt = System.currentTimeMillis()
      reload().onComplete { _ =>
        val remaining = math.max(0L, blinkMillis - (System.currentTimeMillis() - startedAt))
        dom.window.setTimeout(() => refreshing.set(false), remaining.toDouble)
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

  /** The sign-in form itself, not a button that navigates to one: signing in happens on this
    * page now, so that the password is asked for first. Sign-up and password reset are still
    * links out to the hosted pages, from inside `SignIn.view`.
    */
  private def signedOutBody: HtmlElement = SignIn.view

  /** Either registration or the application proper, decided by whether this Cognito identity has
    * a player. `ui.txt`: self-registration in Cognito triggers a player set-up.
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
    * Deliberately not the registration form: the player may well exist, and inviting them to
    * register again would be wrong as well as confusing. A retry is all this can honestly offer.
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

    div(
      cls := "card",
      h2("Choose a Nickname"),
      p("You are signed in, but you do not have a player yet. Your nickname is what other players see."),
      field("Nickname", input(controlled(value <-- nickname.signal, onInput.mapToValue --> nickname))),
      busyButton("Create player", disabledWhen = nickname.signal.map(_.trim.isEmpty)) { busy =>
        Store.run(ApiClient.register(nickname.now().trim), busy) { player =>
          Store.player.set(Store.PlayerState.Registered(player))
          Store.refreshMatches()
          Store.refreshGames()
        }
      }
    )
  }

  /** The application proper: a menu down the left, a screen to the right of it.
    *
    * The menu is the only navigation there is — the wire-frame asks for a link to the main page
    * and a link per game — so it is rendered once here and outlives whatever screen is showing,
    * rather than being part of each screen and redrawn with it.
    */
  private def home: HtmlElement =
    div(
      cls := "layout",
      menu,
      div(
        cls := "screen",
        child <-- Store.page.signal.map {
          case Store.Page.Home             => mainPage
          case Store.Page.OneGame(gameId)  => gamePage(gameId)
          case Store.Page.NewGame          => newGamePage
        }
      )
    )

  /** The left-hand menu: the main page, then every game, then — for an admin — the form that
    * adds one.
    *
    * The games come from the same list the home screen already loads, so opening the menu costs
    * no request. Only the games themselves are links; the entry for the current screen is marked
    * rather than removed, so the menu does not change shape as it is used.
    */
  private def menu: HtmlElement =
    navTag(
      cls := "menu",
      menuItem("Main page", Store.Page.Home),
      child <-- Store.games.signal.map {
        case Nil => p(cls := "empty", "No games yet.")
        case games => div(games.map(game => menuItem(game.name, Store.Page.OneGame(game.gameId))))
      },
      // Only for admins, because only an admin can create a game: the server answers anyone else
      // with a 403, and a menu entry that always fails is worse than no entry.
      child <-- currentPlayer.map {
        case Some(player) if player.isAdmin => menuItem("Add a game", Store.Page.NewGame)
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

  /** The main page: what is waiting on this player, what they are playing, and what lately
    * finished. No games list — the menu is the games list now — and the completed matches are
    * trimmed to the most recent few, because the whole history of a game lives on that game's
    * own screen.
    */
  private def mainPage: HtmlElement =
    div(
      readyToStartSection,
      dueSection,
      myMatchesSection,
      pendingAcceptances,
      recentlyCompletedSection
    )

  // -------------------------------------------------------------------------
  // Matches
  // -------------------------------------------------------------------------

  /** The challenges this player offered that have filled up and are waiting on them to start.
    *
    * Above "Your turn" because it is the one thing here that nobody else can do and that nothing
    * else will do on its own: a full challenge sits there until its challenger starts it. The
    * section is absent rather than empty when there is nothing to start — an empty call to action
    * at the top of the page is just something to scroll past.
    *
    * Both facts it selects on come from the acceptances response, so this needs nothing loaded
    * per game to draw itself.
    */
  private def readyToStartSection: HtmlElement =
    refreshableSection("Ready to Start", () => Store.reloadAcceptances())(
      child <-- Store.acceptances.signal
        .combineWith(Store.games.signal, currentPlayer)
        .map { (acceptances, games, player) =>
          val mine = player.toSeq.flatMap { me =>
            acceptances.filter(p => p.readyToStart && p.challenger == me.playerId)
          }
          // Shown empty rather than absent, now that the section carries its own refresh button:
          // a button that only appears once there is something to find is no use to someone
          // checking whether there is.
          if (mine.isEmpty) p(cls := "empty", "Nothing is waiting for you to start it.")
          else {
            val namesById = games.map(game => game.gameId -> game.name).toMap
            ul(mine.map(pending => readyToStartRow(pending, namesById.get(pending.acceptance.gameId))))
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
          Store.refreshMatches()
          // The challenge is no longer open, so the game's list is stale if it is on screen.
          if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
            Store.refreshChallenges(acceptance.gameId)
        }
      }
    )
  }

  /** "List of all matches a player has a turn due" — the first thing in `ui.txt`, and the only
    * list shown expanded from the start, because it is the one that needs acting on.
    */
  private def dueSection: HtmlElement =
    refreshableSection("Your Turn", () => Store.reloadDue())(
      child <-- Store.due.signal.map {
        case Nil     => p(cls := "empty", "Nothing is waiting on you.")
        case matches => ul(matches.map(matchRow(_, showDue = true)))
      }
    )

  /** The matches still being played. Expanded rather than behind a toggle: the wire-frame lists
    * it as one of the four things the main page shows, and a section that has to be opened to
    * find out whether it is empty is not shown.
    */
  private def myMatchesSection: HtmlElement =
    refreshableSection("Current Matches", () => Store.reloadActive())(
      child <-- Store.active.signal.map {
        case Nil     => p(cls := "empty", "You are not in any matches.")
        case matches => ul(matches.map(matchRow(_, showDue = false)))
      }
    )

  /** "Also shows pending acceptances with option to back out."
    *
    * These are challenges the player has accepted that have not yet filled up into a match, which
    * is why they appear beside the matches rather than in them. The game name is looked up from
    * the games list; an acceptance whose game is not in that list — an inactive game, say — still
    * shows, named by its id rather than dropped.
    *
    * The ones this player could start right now are left out: they have their own section at the
    * top of the page, and listing them twice would offer the same Start button in two places.
    */
  private def pendingAcceptances: HtmlElement =
    refreshableSection("Waiting to Start", () => Store.reloadAcceptances(), subsection = true)(
      child <-- Store.acceptances.signal.combineWith(Store.games.signal, currentPlayer).map { (acceptances, games, player) =>
        val waiting = acceptances.filterNot(p => p.readyToStart && player.exists(_.playerId == p.challenger))
        if (waiting.isEmpty) p(cls := "empty", "You have not accepted anything that is still waiting.")
        else {
          val namesById = games.map(game => game.gameId -> game.name).toMap
          ul(waiting.map(pending => acceptanceRow(pending, namesById.get(pending.acceptance.gameId))))
        }
      }
    )

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
            Store.run(ApiClient.withdraw(acceptance.gameId, acceptance.challengeId, player.playerId), busy) { _ =>
              Store.refreshMatches()
              // The challenge is open again, so the game's list is stale if it is on screen.
              if (Store.page.now() == Store.Page.OneGame(acceptance.gameId))
                Store.refreshChallenges(acceptance.gameId)
            }
          }
      }
    )
  }

  /** The last few finished matches, whatever game they were played in.
    *
    * The list arrives most recently finished first, so the most recent few are simply its first
    * few — no sorting here, and nothing that would disagree with the game screens, which show
    * the same list filtered rather than a differently ordered one.
    */
  private def recentlyCompletedSection: HtmlElement =
    refreshableSection("Recently Completed", () => Store.reloadCompleted())(
      child <-- Store.completed.signal.map(_.take(Store.recentlyCompleted)).map {
        case Nil     => p(cls := "empty", "Nothing finished yet.")
        case matches => ul(matches.map(matchRow(_, showDue = false)))
      }
    )

  private def matchRow(summary: MatchSummary, showDue: Boolean): HtmlElement =
    li(
      cls := "row",
      div(cls := "title", summary.gameName),
      div(cls := "detail", summary.description),
      if (showDue) summary.due.map(when => div(cls := "due", s"due ${Format.instant(when)}")).getOrElse(emptyNode)
      else emptyNode,
      // `pending` means it is this player's turn: it is the flag the "Your turn" list selects
      // on, so saying it there would repeat the heading on every row. Said here only for the
      // matches still being played — a finished match has no turn to be waiting for.
      if (showDue || summary.completed || summary.cancelled) emptyNode
      else if (summary.pending) div(cls := "pending", "your turn")
      else div(cls := "detail", "waiting for the other players"),
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
            timeTag(htmlAttr("datetime", com.raquo.laminar.codecs.StringAsIsCodec) := when.toString, Format.date(when))
          )
        )
        .getOrElse(emptyNode),
      // Play and Refresh are for a match still being played. A finished one has no turn to take
      // and nothing left for the engine to tell us, so it shows how it ended instead.
      if (summary.completed || summary.cancelled) resultTable(summary)
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
            Store.run(ApiClient.refreshMatch(summary.gameId, summary.matchId), busy)(_ => Store.refreshMatches())
          }
        ),
      // Only the creator's, and only while there is still something to call off. The engine is
      // not told — its board stays playable — so the confirmation says what actually happens.
      if (summary.isCreator && !summary.completed && !summary.cancelled)
        busyButton("Cancel", classes = Some("link")) { busy =>
          if (dom.window.confirm("Cancel this match? It will stop counting here, but the game board stays open."))
            Store.run(ApiClient.cancelMatch(summary.gameId, summary.matchId), busy)(_ => Store.refreshMatches())
        }
      else emptyNode
    )

  /** How a finished match ended: every seat, the winner first.
    *
    * The rows are already ordered by rank by the query, so the order of the list is the standing
    * and the rank itself does not need saying — but winning is marked from `isWinner` rather
    * than from position, because a game may have no winner at all (a draw, a cancelled match)
    * and first place would otherwise invent one.
    *
    * A cancelled match has no engine-reported result, so its rows usually have no rank/scores (rather than being absent).
    * Saying so beats rendering an empty-looking table.
    */
  private def resultTable(summary: MatchSummary): HtmlElement =
    div(
      cls := "results",
      child <-- Store.resultsByMatch.signal.map(_.getOrElse(summary.matchId, Seq.empty)).map {
        case Seq() =>
          p(cls := "empty", if (summary.cancelled) "Called off before it finished." else "No result was reported.")
        case rows =>
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
                // Whatever else the engine chose to report. Which keys exist is the game's
                // business, so they are shown as they came rather than being named here.
                if (row.scores.isEmpty) emptyNode
                else
                  span(
                    cls := "scores",
                    " — ",
                    row.scores.toSeq.sortBy(_._1).map((key, value) => s"$key: ${Format.jsonValue(value)}").mkString(", ")
                  )
              )
            }
          )
      }
    )

  // -------------------------------------------------------------------------
  // Games and challenges
  // -------------------------------------------------------------------------

  /** One game's screen: its open challenges, a way to offer one, and this player's history in
    * it. An admin also gets the edit form.
    *
    * Taken from the games list rather than fetched, so a game id the list does not know about —
    * an inactive game, or a menu that has outlived a reload — says so instead of showing an
    * empty screen that looks like a game with nothing in it.
    */
  private def gamePage(gameId: GameId): HtmlElement =
    div(
      child <-- Store.games.signal.map(_.find(_.gameId == gameId)).map {
        case None => p(cls := "empty", "Loading…")
        case Some(game) =>
          div(
            h2(game.name),
            p(cls := "detail", game.description),
            editGamePanel(game),
            gameChallenges(game),
            gameHistory(game)
          )
      }
    )

  /** The admin's edit form for a game, opened from a link on the game's own screen. Nothing for
    * anyone else: the server answers a non-admin with a 403, so the link is not there to press.
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
                Store.editingGame.update(current => if (current.contains(game.gameId)) None else Some(game.gameId))
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
    * The same list the main page shows the top of, filtered rather than fetched again: it is
    * already in the order this asks for, and a second request would only be a second chance for
    * the two screens to disagree.
    */
  private def gameHistory(game: Game): HtmlElement =
    refreshableSection("Your Completed Matches", () => Store.reloadCompleted())(
      child <-- Store.completed.signal.map(_.filter(_.gameId == game.gameId)).map {
        case Nil     => p(cls := "empty", "You have not finished a match of this yet.")
        case matches => ul(matches.map(matchRow(_, showDue = false)))
      }
    )

  /** The admin's add-a-game screen, reached from the menu. The same form the edit link opens,
    * with nothing to start from.
    */
  private def newGamePage: HtmlElement =
    div(
      h2("Add a Game"),
      child <-- currentPlayer.map {
        case Some(player) if player.isAdmin => newGameForm
        case _ => p(cls := "empty", "Only an administrator can add a game.")
      }
    )

  /** "An admin user should be able to create a new game."
    *
    * The form asks for everything a game is: its own fields, the roles a player can be seated in,
    * and the parameters the game engine is configured with. Roles are not optional extras — every
    * acceptance names one, so a game with none is a game nothing can be offered for, and the
    * server refuses it.
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

  /** The possible values of a parameter, as typed: one comma-separated list, because a parameter
    * with three values is a sentence an admin can type and a list of three inputs is not.
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
            aria.label <-- draft.name.signal.map(n => if (n.trim.isEmpty) "Remove this role" else s"Remove role $n"),
            "Remove",
            onClick --> (_ => roles.update(_.filterNot(_ eq draft)))
          )
          else emptyNode
        )
      }),
      button(cls := "link", "Add a role", onClick --> (_ => roles.update(_ :+ emptyRole)))
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
            aria.label <-- draft.name.signal.map(n => if (n.trim.isEmpty) "Remove this parameter" else s"Remove parameter $n"),
            "Remove",
            onClick --> (_ => parameters.update(_.filterNot(_ eq draft)))
          )
        )
      }),
      button(cls := "link", "Add a parameter", onClick --> (_ => parameters.update(_ :+ emptyParameter)))
    )

  /** The drafted roles as the model, or the first thing wrong with them.
    *
    * The same rules the server checks, checked here so that a typo is answered by the form rather
    * than by a round trip — the server is still the one that decides, since nothing stops a
    * request being made without this form.
    */
  private def rolesOf(drafts: List[RoleDraft]): Either[String, Seq[GameRole]] = {
    val all = drafts.map(d => (d.gameRoleId, d.name.now().trim, d.optional.now()))
    // A blank new row is one the admin added and did not fill in, and is dropped. A blank
    // existing row is a role whose name has been cleared -- dropping that would ask the server to
    // delete a role, which it refuses, so it is answered here as what it is.
    val (blank, named) = all.partition(_._2.isEmpty)
    if (blank.exists(_._1 != GameRoleId.unassigned)) Left("A role that already exists cannot be left without a name.")
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
    * The two are the same form because a game is the same thing either way, and every field is
    * editable in both: name, description, url, whether characters are required, the roles and
    * the parameters. There is no player count to set — a game's roles are its seats, so adding
    * one in [[roleEditor]] is how a game gets bigger. What edit cannot do is delete a role.
    *
    * Two fields are never shown. `externalId` is the game's own credential: kept as it is when
    * editing, generated when creating, and in neither case something to type. `active` is not on
    * this form at all, so editing preserves it and creating sets it true.
    */
  private def gameForm(existing: Option[Game]): HtmlElement = {
    val name = Var(existing.map(_.name).getOrElse(""))
    val description = Var(existing.map(_.description).getOrElse(""))
    val url = Var(existing.map(_.url).getOrElse(""))
    // Plain by default: requiring characters is the additional commitment, so it is the box an
    // admin ticks rather than the one they have to remember to untick.
    val gameType: Var[GameType] = Var(existing.map(_.gameType).getOrElse(GameType.Plain))
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
          onClick --> (_ => gameType.update(gt => if (gt == GameType.Character) GameType.Plain else GameType.Character))
        )
      ),
      roleEditor(roles),
      parameterEditor(parameters),
      busyButton(
        if (existing.isDefined) "Save changes" else "Create game",
        disabledWhen = name.signal.map(_.trim.isEmpty)
      ) { busy =>
        val drafted = for {
          roleModels <- rolesOf(roles.now())
          parameterModels <- parametersOf(parameters.now())
        } yield (roleModels, parameterModels)

        drafted match {
          case Left(problem) => Store.error.set(Some(problem))
          case Right((roleModels, parameterModels)) =>
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
            externalId = existing.map(_.externalId).getOrElse(Pkce.newSecret())
          )

          Store.run(ApiClient.createGame(game), busy) { saved =>
            if (existing.isEmpty) {
              name.set("")
              description.set("")
              url.set("")
              roles.set(List(emptyRole))
              parameters.set(Nil)
              // Straight to the game that was just created: it is now in the menu, and its own
              // screen is where anything else is done with it.
              Store.show(Store.Page.OneGame(saved.gameId))
            } else {
              // Re-drafted from what came back, so that roles added by this save carry the ids
              // the insert gave them — without which saving twice would ask to add them again.
              roles.set(saved.roles.map(draftOf).toList)
              parameters.set(saved.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList)
              Store.editingGame.set(None)
            }
            Store.refreshGames()
          }
        }
      }
    )
  }

  /** What can be played in this game right now: the open challenges, and the form that offers
    * one. A game that needs characters needs one of this player's before either is possible, so
    * that form stands in for both until there is one.
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
                         case None              => p(cls := "empty", "Loading…")
                         // A character is needed before this player can either offer or accept a
                         // challenge, so there is nothing to show until there is one.
                         case Some(Nil)         => characterForm(game, player)
                         case Some(characters)  => challengePanel(game, player, Some(characters.head.characterId))
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
      busyButton("Create character", disabledWhen = name.signal.map(_.trim.isEmpty)) { busy =>
        val created =
          ApiClient.createCharacter(game.gameId, name.now().trim, description.now().trim, player.externalId)
        Store.run(created, busy)(_ => Store.refreshCharacters(game.gameId))
      }
    )
  }

  private def challengePanel(game: Game, player: Player, characterId: Option[CharacterId]): HtmlElement =
    div(
      child <-- Store.challengesByGame.signal.combineWith(Store.acceptances.signal).map { (byGame, acceptances) =>
        byGame.get(game.gameId) match {
          case None => p(cls := "empty", "Loading challenges…")
          case Some(challenges) =>
            // `ui.txt` asks for the player's own challenges in a separate list, because what you
            // can do with them is different: delete yours, accept someone else's.
            val (mine, others) = challenges.partition(_.challenge.challenger == player.playerId)
            // A challenge this player has already accepted stays open until it fills up, but
            // offering it again would only produce a duplicate acceptance the service rejects —
            // so it is dropped from the list rather than shown with an Accept that cannot work.
            val accepted = acceptances.map(a => (a.acceptance.gameId, a.acceptance.challengeId)).toSet
            val available = others.filterNot(c => accepted.contains((c.challenge.gameId, c.challenge.challengeId)))
            div(
              // A button rather than a form standing open: offering a challenge is one of several
              // things to do on this screen, and a form is what the screen looks like it is for.
              button(
                aria.expanded <-- Store.showChallengeForm.signal,
                child.text <-- Store.showChallengeForm.signal.map(if (_) "Close" else "Create challenge"),
                onClick --> (_ => Store.showChallengeForm.update(!_))
              ),
              child <-- Store.showChallengeForm.signal.map {
                if (_) newChallengeForm(game, player, characterId) else emptyNode
              },
              refreshableSection(
                "Your Open Challenges",
                () => Store.reloadChallenges(game.gameId),
                subsection = true
              )(
                if (mine.isEmpty) p(cls := "empty", "You have none open.")
                else ul(mine.map(myChallengeRow(game, _)))
              ),
              refreshableSection(
                "Open Challenges",
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

  private def myChallengeRow(game: Game, summary: OpenChallengeSummary): HtmlElement = {
    val challenge = summary.challenge
    li(
      cls := "row",
      div(cls := "title", challenge.message),
      div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
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
            Store.refreshMatches()
          }
        }
      else div(cls := "detail", s"waiting for ${unfilledRoles(game, summary).map(_.name).mkString(", ")}"),
      busyButton("Delete") { busy =>
        Store.run(ApiClient.deleteChallenge(game.gameId, challenge.challengeId), busy)(_ =>
          Store.refreshChallenges(game.gameId)
        )
      }
    )
  }

  private def openChallengeRow(game: Game, summary: OpenChallengeSummary, characterId: Option[CharacterId]): HtmlElement = {
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
      roleSelect(free, role),
      if (free.isEmpty) div(cls := "detail", "every role is taken")
      else
        busyButton("Accept") { busy =>
          val chosen = role.now().getOrElse(free.head.gameRoleId)
          Store.run(ApiClient.accept(game.gameId, challenge.challengeId, characterId, chosen), busy) { _ =>
            // Accepting may complete the challenge into a match, which changes the match lists
            // as well as this one, so both are reloaded.
            Store.refreshChallenges(game.gameId)
            Store.refreshMatches()
          }
        }
    )
  }

  /** The roles of `game` that no acceptance of `summary` has claimed yet. */
  private def freeRoles(game: Game, summary: OpenChallengeSummary): Seq[GameRole] =
    game.roles.filterNot(r => summary.takenRoles.contains(r.gameRoleId))

  /** The roles a start is still waiting for: required, and unclaimed. Optional roles are exactly
    * the ones a match need not wait for, so they are not counted here even when free.
    */
  private def unfilledRoles(game: Game, summary: OpenChallengeSummary): Seq[GameRole] =
    freeRoles(game, summary).filterNot(_.optional)

  /** A picker for the role a player will play, which matchmaker passes on to the game engine.
    *
    * `choices` are the roles still available. There is no "any role" entry: every seat names a
    * role, so the first available one stands pre-selected and the picker only changes which.
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

  private def newChallengeForm(game: Game, player: Player, characterId: Option[CharacterId]): HtmlElement = {
    val message = Var("")
    val isPublic = Var(false)
    // A challenge is its challenger's own acceptance, so it names a role like any other. Nothing
    // has been claimed yet, so every role of the game is on offer and the first stands selected.
    val role = Var(game.roles.headOption.map(_.gameRoleId))

    div(
      cls := "card",
      h3("Offer a Challenge"),
      field("Message", input(controlled(value <-- message.signal, onInput.mapToValue --> message))),
      roleSelect(game.roles, role),
      // Public means anyone may watch the match, which the game engine implements by issuing a
      // url that needs no sign-in. It is decided here because it is a property of the game being
      // offered, not of any one player's part in it.
      label(
        input(
          tpe := "checkbox",
          controlled(checked <-- isPublic.signal, onClick.mapToChecked --> isPublic)
        ),
        "anyone may watch"
      ),
      busyButton(
        "Create challenge",
        // A game with no roles at all has nothing an acceptance could name, so no challenge for
        // it can be created. The server refuses one; this keeps the button from offering it.
        disabledWhen = message.signal.combineWith(role.signal).map { case (m, r) => m.trim.isEmpty || r.isEmpty }
        // `foreach` rather than a fallback role: with no role there is no challenge to make, and
        // the disabled button above is what keeps that from being reachable.
      ) { busy =>
        role.now().foreach { chosen =>
          // The server assigns the id; this is the same unassigned-sentinel convention the
          // service layer uses on create.
          val challenge: OpenChallenge = characterId match {
            case Some(cid) =>
              CharacterOpenChallenge(
                challengeId = ChallengeId(0),
                challenger = player.playerId,
                message = message.now().trim,
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                characterId = cid,
                isPublic = isPublic.now(),
                gameRoleId = chosen
              )
            case None =>
              PlainOpenChallenge(
                challengeId = ChallengeId(0),
                challenger = player.playerId,
                message = message.now().trim,
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                isPublic = isPublic.now(),
                gameRoleId = chosen
              )
          }

          Store.run(ApiClient.createChallenge(challenge), busy) { _ =>
            message.set("")
            // The challenge it was open for now exists and is in the list below it.
            Store.showChallengeForm.set(false)
            Store.refreshChallenges(game.gameId)
          }
        }
      }
    )
  }

  private def currentPlayer: Signal[Option[Player]] = Store.currentPlayer
}

/** Formatting that has to be readable rather than exact. */
object Format {

  /** `Instant.toString` is ISO-8601 in UTC, which is precise and unpleasant to read. This trims
    * it to the minute and marks it as UTC rather than pretending to know the user's zone —
    * Scala.js has no time-zone database unless one is bundled, and a wrong local time is worse
    * than an explicit UTC one.
    */
  def instant(value: java.time.Instant): String =
    value.toString.replace("T", " ").takeWhile(_ != '.').stripSuffix("Z") + " UTC"

  /** The date alone, for a time whose hour is of no interest — when a match was completed reads
    * as a day in a history, not as a deadline. No UTC marker: a bare date carries none of the
    * precision that would make the zone worth mentioning.
    */
  def date(value: java.time.Instant): String =
    value.toString.takeWhile(_ != 'T')

  /** A score reported by a game engine, which may be any JSON value.
    *
    * Strings are unquoted and whole numbers lose their `.0`, since upickle reads every JSON
    * number as a Double and "moves: 5.0" reads as a mistake. Anything structured is rendered as
    * the JSON it is — a game that reports a nested object is unusual, and showing it raw beats
    * inventing a layout for a shape we cannot know.
    */
  def jsonValue(value: ujson.Value): String = value match {
    case ujson.Str(s)                       => s
    case ujson.Num(n) if n.isWhole          => n.toLong.toString
    case ujson.Num(n)                       => n.toString
    case ujson.Bool(b)                      => b.toString
    case ujson.Null                         => "—"
    case other                              => other.render()
  }
}
