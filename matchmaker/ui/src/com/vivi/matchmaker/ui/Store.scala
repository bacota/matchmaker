package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.api.Json

/** What the UI knows, and how it reloads it.
  *
  * One `Var` per list the API serves, each reloaded by re-fetching rather than by patching the copy held here. The
  * lists are short and an action often changes more than the list it was taken from — accepting a challenge can create
  * a match, which changes three of them at once — so re-fetching is both simpler and less likely to show something that
  * is no longer true.
  */
object Store {

    /** What is known about the caller's player.
      *
      * Four states rather than an `Option`, because the differences matter to what is shown: `Loading` must not flash
      * the registration form at an existing player, and `Unavailable` must not either — a 500 or a dropped connection
      * is not evidence that the account does not exist, and telling a registered player to register would be actively
      * misleading.
      */
    enum PlayerState {
        case Loading
        case Unregistered
        case Registered(player: Player)
        case Unavailable(message: String)
    }

    val player: Var[PlayerState] = Var(PlayerState.Loading)

    /** The player, when there is one. Anything else — loading, unregistered, unreachable — is `None`, so views that
      * only need the player itself do not have to restate the distinction.
      */
    def currentPlayer: Signal[Option[Player]] = player.signal.map {
        case PlayerState.Registered(p) => Some(p)
        case _                         => None
    }

    /** Whether there is a usable token, mirrored into a `Var` because `sessionStorage` is not observable: without this
      * the UI would not notice a session ending until something re-rendered for another reason.
      */
    val signedIn: Var[Boolean] = Var(Auth.isSignedIn)

    /** The player asked to leave: revocation of the refresh token is triggered (best-effort), then the screen is
      * returned to the sign-in form the same way an expired session returns it.
      */
    def signOut(): Unit = {
        Auth.signOut()
        sessionExpired()
    }

    /* Which sign-in the UI is showing, counted rather than named.
     *
     * A request is made by one sign-in and answered some time later, by which point that sign-in may
     * be over: the player signed out, or the token expired, while it was in flight. The answer then
     * belongs to whoever was signed in when it was sent, and to nobody who is signed in now -- so
     * anything that holds onto an answer takes this number before the request and checks it before
     * committing, through `stillSignedInAs`.
     *
     * A counter rather than the player's id, because two sign-ins are two sessions even when they
     * are the same player: what went stale is the request, not the identity. */
    private var signIns: Int = 0

    /** The session as it stands. Taken before a request whose answer will be held.
      *
      * Visible to the rest of the UI, not just to this file, because a screen that writes back into the store does it
      * from a request of its own — `Account`'s rename is the one that does — and the answer to that can outlive its
      * session exactly as a fetch can.
      */
    private[ui] def currentSignIn: Int = signIns

    /** Whether the session that asked is still the session that is here. */
    private[ui] def stillSignedInAs(signIn: Int): Boolean = signIn == signIns

    /** The token has gone — expired, revoked, or signed out elsewhere. Everything derived from it is dropped, so no
      * stale list is left on screen behind the sign-in prompt.
      */
    def sessionExpired(): Unit = {
        // First, before anything is cleared: from here on, an answer to a request this session made
        // is an answer to a question nobody is asking any more.
        signIns += 1
        Auth.clearSession()
        signedIn.set(false)
        player.set(PlayerState.Loading)
        due.set(Seq.empty)
        active.set(Seq.empty)
        completed.set(Seq.empty)
        games.set(Seq.empty)
        unlistedGames.set(Map.empty)
        loadingUnlistedGame.set(false)
        challengesByGame.set(Map.empty)
        charactersByGame.set(Map.empty)
        acceptances.set(Seq.empty)
        invitations.set(Seq.empty)
        // Whoever was about to be invited was being invited by the player who has just gone.
        invitee.set(None)
        // Somebody else's page, and the search that found them: both are answers to questions the
        // previous session asked, and the next player starts from an empty box.
        playerSearch.set("")
        // One player's idea of who is who, learned from their searches: dropped with the rest, so the
        // next player is shown ids rather than names the session before them looked up.
        nicknames.set(Map.empty)
        playerResults.set(None)
        publicActive.set(Seq.empty)
        publicCompleted.set(Seq.empty)
        publicMatchesLoading.set(false)
        // Dropped with the rest: they are one player's answers, and the next player to sign in
        // must not be shown them, let alone save them back.
        notificationSettings.set(None)
        // Back to "nothing has answered yet", so the next player's sections say they are loading
        // rather than reporting this player's empty lists as theirs.
        fetched.set(Set.empty)
        page.set(Page.Home)
        showChallengeForm.set(false)
        editingGame.set(None)
        // Closed and emptied with the rest: it holds a half-typed address and a password field, and
        // neither belongs to whoever signs in next. `forget` rather than `close`, which deliberately
        // keeps an email change that is waiting for its code -- a sign-out is where that stops being
        // something to come back to.
        Account.forget()
    }

    /** The lists this store fetches, named so that a screen can tell "there is nothing here" from "nobody has told us
      * yet".
      *
      * Every one of them starts empty, and an empty list read a moment after sign-in means the second of those, not the
      * first — which is why a section cannot answer the question from the list alone.
      *
      * Not every fetch is here: the per-game challenges and characters are keyed by game in a `Map`, so a game that has
      * not been fetched is a missing key rather than an empty list, and those screens already say "Loading…" on it.
      */
    enum Fetch {
        case Due, Active, Completed, Results, Games, Acceptances, Invitations
    }

    /* Which of them have been answered in this session -- however they were answered.
     *
     * A request that failed is not still in flight: the section it would have filled says what it
     * knows, beside the banner that says what went wrong. Leaving it saying "Loading..." for ever
     * would be a worse lie than the one this exists to correct. */
    private val fetched: Var[Set[Fetch]] = Var(Set.empty)

    /** Whether a list is still on its way, which is to say nothing has answered for it yet this session. */
    def loading(what: Fetch): Signal[Boolean] = fetched.signal.map(!_.contains(what))

    val due: Var[Seq[MatchSummary]] = Var(Seq.empty)
    val active: Var[Seq[MatchSummary]] = Var(Seq.empty)
    val completed: Var[Seq[MatchSummary]] = Var(Seq.empty)
    val games: Var[Seq[Game]] = Var(Seq.empty)

    /** Games reachable by a link but absent from the list above: the deactivated ones.
      *
      * `games` holds the active games, because that is what every list and the menu should offer. A game can still be
      * *arrived at* after being deactivated — an invitation to a challenge in it, a match still being played — and its
      * screen drawn from the active list alone would say "Loading…" for ever, waiting for a game that list will never
      * hold.
      *
      * Fetched once per game, on the way to its screen, and kept for the session: a deactivated game does not come back
      * while somebody is looking at it. Separate from `games` rather than merged into it so the menu and every "which
      * game?" picker go on offering the active ones only.
      */
    val unlistedGames: Var[Map[GameId, Game]] = Var(Map.empty)

    /** Whether a look for one of those is in flight.
      *
      * Its own flag rather than a `Fetch`: a `Fetch` says a list has been answered once this session, and this is asked
      * per game and only for the games that are missing. Without it a screen would read "not yet answered" as "not
      * there" for as long as the request took, and say so.
      */
    val loadingUnlistedGame: Var[Boolean] = Var(false)

    /** Open challenges per game, filled in only for games the user has expanded — there is one request per expansion,
      * and games nobody opens cost nothing.
      */
    val challengesByGame: Var[Map[GameId, Seq[ChallengeSummary]]] = Var(Map.empty)

    /** The caller's characters per game, loaded alongside the challenges when a game is expanded. Both offering and
      * accepting a challenge need one.
      */
    val charactersByGame: Var[Map[GameId, Seq[Character[String]]]] = Var(Map.empty)

    /** What the caller has accepted and that has not yet become a match — what `ui.txt` calls the pending acceptances,
      * and the list "back out" acts on.
      */
    val acceptances: Var[Seq[PendingAcceptance]] = Var(Seq.empty)

    /** What the caller has been invited to and could still accept (V22), newest first and across every game.
      *
      * Beside `acceptances` rather than inside it: an invitation is a challenge the caller has *not* answered, where a
      * pending acceptance is one they have, and the two sections ask different things of them. It carries the game's
      * name and the challenger's nickname with each row, because the section that draws it spans every game and has
      * nothing to look either up from — see `ChallengeInvitation`.
      */
    val invitations: Var[Seq[ChallengeInvitation]] = Var(Seq.empty)

    /** The player a challenge is about to be offered to, carried from their page to a game's.
      *
      * Set by the invite button on somebody's page, which then shows the game screen with the challenge form open: the
      * form is where a challenge is composed, and who it is for is the one thing that screen cannot ask, since it has
      * no search box on it. Cleared when the form closes or the challenge is made, so the next challenge offered from
      * that screen is not quietly addressed to whoever was looked at last -- which is the bug this shape invites, and
      * the reason it is one slot rather than a list.
      */
    val invitee: Var[Option[PublicPlayer]] = Var(None)

    /** Nicknames for player ids this session has been told, so a row holding an id can say who it means.
      *
      * A challenge's invitations name their players by id and nothing else — `Invitation` is permission, not a player —
      * and the challenger looking at their own challenge needs the name. Filled from the searches that found them,
      * which is where every invitation on that screen is made from, so the player just invited is in here by the time
      * the row is redrawn.
      *
      * Best-effort by design: a miss is an id shown plainly, not a request. An invitation made in another session, or
      * in this one before a reload, is a name nobody here has heard — and one fetch per row to learn it would be a
      * request per invitation on a screen that already has what it needs for everything else.
      */
    val nicknames: Var[Map[PlayerId, String]] = Var(Map.empty)

    /** Remembers who a search or a page has just named. */
    def remember(players: Seq[PublicPlayer]): Unit =
        nicknames.update(_ ++ players.map(player => player.playerId -> player.nickname))

    /** What is in the player search box, kept in the store rather than in the screen so that leaving the search for a
      * player's page and coming back does not clear it -- the usual reason to come back is to try the next result.
      */
    val playerSearch: Var[String] = Var("")

    /** What the last search answered, or `None` when nothing has been searched for in this session.
      *
      * `None` rather than an empty result, because "no search yet" and "no player by that name" are the two things the
      * screen has to say differently, and an empty list cannot tell them apart. The `more` flag inside it is the
      * server's word for "there were others" -- the list's own length cannot say so, since a full page may be the last
      * one.
      */
    val playerResults: Var[Option[PlayerSearchResult]] = Var(None)

    /** The public matches of the player whose page is open: still being played, and finished.
      *
      * Emptied and re-fetched for each player, which is why they carry no `Fetch` flag like the caller's own lists: a
      * `Fetch` says "this has been answered once this session", and it would report the previous player's lists as
      * loaded. `publicMatchesLoading` is what says a fetch is in flight instead.
      */
    val publicActive: Var[Seq[MatchSummary]] = Var(Seq.empty)
    val publicCompleted: Var[Seq[MatchSummary]] = Var(Seq.empty)

    /** Whether a fetch of somebody's public matches is in flight, for the sections to say "Loading…" on.
      *
      * A flag rather than reading emptiness as "still coming", which is what this replaced: a player with no public
      * matches and a fetch that failed both leave the lists empty, and neither is still loading. Cleared however the
      * requests settle, so a failure leaves the sections saying there is nothing here, beside the banner that says what
      * went wrong -- rather than saying "Loading…" for the rest of the session.
      */
    val publicMatchesLoading: Var[Boolean] = Var(false)

    /* Which fetch of a player's public matches is the one the page is waiting for.
     *
     * Incremented per fetch, and an answer commits only if its own number is still the current one.
     * Two races need that, and the sign-in counter `reload` already applies catches neither, because
     * both happen within one session:
     *
     *   - Two players. Opening A and then B before A has answered would write A's matches into the
     *     slots B's page is drawing from -- one player's matches under another's nickname, which is
     *     worse than stale because nothing about it looks wrong.
     *   - One player, twice. An initial load and a refresh, or two impatient refreshes, can land in
     *     either order; without this the older answer wins whenever it arrives second.
     *
     * A single counter rather than one per player, because one player's page is on screen at a time:
     * what is wanted is not "the answer for this player" but "the answer to the question the page is
     * currently asking", and anything else is by definition not it. */
    private var publicMatchesFetch: Long = 0L

    /** Which game's row is open on a player's page, if any. One at a time, like `editingGame`: the rows are a list to
      * scan, and two open sets of matches make it a page to scroll.
      */
    val expandedPublicGame: Var[Option[GameId]] = Var(None)

    /** Searches for players whose nickname starts with what is in the box, and holds the answer.
      *
      * Through `reload` so the answer is dropped if the session that asked has ended, and so a failure raises the
      * banner: the caller is a button waiting to stop showing itself as busy, which is all it can usefully do with one.
      *
      * A blank box searches for nothing rather than for everybody -- the server refuses it, and asking would trade a
      * request for a banner saying so.
      */
    def searchPlayers(): Future[Unit] = {
        val prefix = playerSearch.now().trim
        if (prefix.isEmpty) {
            playerResults.set(None)
            Future.unit
        } else
            reload(ApiClient.searchPlayers(prefix)) { result =>
                playerResults.set(Some(result))
                remember(result.players)
            }
    }

    /** Both of a player's public lists. Used when their page is opened and by that page's refresh button.
      *
      * Two requests, one fetch: they are the two halves of one page, they are numbered together, and the page stops
      * saying it is loading when both have settled. Each commits only if this is still the fetch the page is waiting
      * for -- see `publicMatchesFetch` for the two races that guards.
      */
    def reloadPublicMatches(playerId: PlayerId): Future[Unit] = {
        publicMatchesFetch += 1
        val fetch = publicMatchesFetch
        publicMatchesLoading.set(true)

        val running = reload(ApiClient.publicMatches(playerId))(list => ifCurrent(fetch)(publicActive.set(list)))
        val over =
            reload(ApiClient.publicCompletedMatches(playerId))(list => ifCurrent(fetch)(publicCompleted.set(list)))

        // However they settled: `reload` reports a failure and succeeds, so this runs on either
        // outcome, which is what stops a failed fetch from leaving the page loading for ever.
        running.zip(over).map(_ => ifCurrent(fetch)(publicMatchesLoading.set(false)))
    }

    /* Commits a public-matches answer only if the page is still waiting for the fetch it came from. A
     * later fetch has already emptied the lists and set the flag for itself, so an older answer has
     * nothing to add and a place where it would do harm. */
    private def ifCurrent(fetch: Long)(commit: => Unit): Unit = if (fetch == publicMatchesFetch) commit

    /** What the caller wants to be told about, once something has asked.
      *
      * `None` is "not fetched", not "nothing set": the settings form is inside the account panel, which most sessions
      * never open, so this is loaded when that panel first opens rather than with the home screen's other five
      * requests. A player who has set nothing still fetches a `NotificationSettings` — of `unset` answers and no games.
      */
    val notificationSettings: Var[Option[NotificationSettings]] = Var(None)

    /** Fetches the caller's notification settings unless they are already here. Re-opening the panel shows what is held
      * rather than asking again; a save updates it in place, so the two cannot disagree.
      *
      * Through `load`, like every other fetch here — and the one where a stale answer would not merely flash. Every
      * other list is re-fetched at the next sign-in; this one is skipped when something is held, so an answer written
      * after a sign-out would be what the next player's panel shows them, and what they could save back as their own.
      */
    def loadNotifications(): Unit =
        if (notificationSettings.now().isEmpty) load(ApiClient.notifications())(s => notificationSettings.set(Some(s)))

    /** Fetches them again whatever is held, and says when it has.
      *
      * For a save whose effects reach rows this panel is holding copies of: `updateNotifications` with `applyToGames`
      * rewrites the player's per-game rows, and only for the questions that save changed. The screen could restate that
      * rule to work out the result, and did, wrongly — so it asks instead. One request, on a deliberate action, in
      * exchange for a copy that cannot disagree with the server.
      *
      * Through `reload`, so the answer is dropped if the session that asked has ended, and so a failure raises the
      * banner rather than being handed back to a caller with nothing useful to do about it.
      */
    def reloadNotifications(): Future[Unit] =
        reload(ApiClient.notifications())(s => notificationSettings.set(Some(s)))

    /** How each finished match turned out, keyed by its match id: the rows of the result table shown under a completed
      * match. Loaded whole with the lists, not per row.
      */
    val resultsByMatch: Var[Map[MatchId, Seq[Json.ParticipantResultView]]] = Var(Map.empty)

    /** Which screen the left-hand menu has selected.
      *
      * A field rather than a URL: there is still no router, and the browser's address bar is spoken for by the Cognito
      * redirect. What changed is that the games are no longer a list on the home page that expands in place — a game is
      * a screen of its own, so something has to say which one is being looked at.
      */
    enum Page {
        case Home
        case OneGame(gameId: GameId)
        case NewGame

        /** The search box and its results. */
        case FindPlayers

        /** Somebody else's page. Carries the player rather than their id, because the page is headed by their nickname
          * and the search result that opened it already knew it -- an id alone would mean a request to be told a name
          * the previous screen had in its hand.
          */
        case OnePlayer(player: PublicPlayer)
    }

    val page: Var[Page] = Var(Page.Home)

    /** Goes to a screen, loading what it needs on the way.
      *
      * A game's challenges and characters are fetched on arrival rather than held for every game at once: there is one
      * request per game opened, and a game nobody looks at costs nothing. Re-selecting the game already shown reloads
      * it, which is the only refresh this screen has.
      */
    def show(next: Page): Unit = {
        /* Leaving a screen closes the challenge form and forgets who it was being addressed to.
         *
         * Both are one slot for the whole session -- there is one form open at a time and one player
         * being asked -- and neither means anything on the screen after this one. Left standing, the
         * form reopened on the next game looked at, already addressed to somebody chosen for a
         * different game: it does say whose name it carries, but nobody asked it to carry one here.
         *
         * Only on an actual change of screen. Re-selecting the game already shown is this screen's
         * only refresh, and closing a half-typed challenge would be a strange thing for a refresh to
         * do. `showGameToInvite` below is the one caller that wants them set, which is why it sets
         * them after this rather than before. */
        if (next != page.now()) {
            showChallengeForm.set(false)
            invitee.set(None)
        }

        page.set(next)
        next match {
            case Page.OneGame(gameId) =>
                refreshChallenges(gameId)
                refreshCharacters(gameId)
                ensureGame(gameId)
            /* Emptied before the request rather than left holding the last player's matches: the
             * page is about to be headed with a different nickname, and rows from the player before
             * them under it would be read as theirs. `None` is what the sections show as loading. */
            case Page.OnePlayer(player) =>
                expandedPublicGame.set(None)
                publicActive.set(Seq.empty)
                publicCompleted.set(Seq.empty)
                reloadPublicMatches(player.playerId)
            case _ => ()
        }
    }

    /** Goes to a game's screen with the challenge form open and addressed to one player.
      *
      * The invite button on somebody's page: composing a challenge needs the form, and who it is for is the one thing
      * that screen cannot ask. Setting the two after the navigation rather than before it, because `show` clears them —
      * which is what keeps every *other* way of arriving at a game from inheriting them.
      */
    def showGameToInvite(gameId: GameId, asked: PublicPlayer): Unit = {
        show(Page.OneGame(gameId))
        invitee.set(Some(asked))
        showChallengeForm.set(true)
    }

    /** The game whose admin edit form is open, if any. One slot rather than a set: editing two games at once is not a
      * thing anyone does, and one open form is one place to look for it.
      */
    val editingGame: Var[Option[GameId]] = Var(None)

    /** Whether the game screen's challenge form is open. Closed by default — the wire-frame asks for a "Create
      * Challenge" button, and a form standing open is not a button.
      */
    val showChallengeForm: Var[Boolean] = Var(false)

    /** How many finished matches the home screen shows at once. The whole history belongs to the game it was played in;
      * the home screen shows it a page of this size at a time, most recent first.
      */
    val recentlyCompleted: Int = 10

    /** The last thing that went wrong, shown as a banner. A single slot rather than a list: the user acts on the most
      * recent failure, and a queue of stale ones is noise.
      */
    val error: Var[Option[String]] = Var(None)

    def report(error: Throwable): Unit = {
        dom.console.error(error.toString)
        Store.error.set(Some(messageOf(error)))
    }

    private def messageOf(error: Throwable): String = error match {
        case ApiError(401, _) => "Your session has expired. Sign in again."
        case ApiError(403, m) => s"Not allowed: $m"
        case ApiError(_, m)   => m
        case other            => Option(other.getMessage).getOrElse(other.toString)
    }

    /** Runs an action and reports a failure rather than losing it. Every button goes through here: a `Future` whose
      * failure nobody observes disappears silently, which in a UI looks exactly like a button that does nothing.
      */
    def run[A](action: Future[A])(onSuccess: A => Unit): Unit =
        action.onComplete(settle(_)(onSuccess))

    /** The same, holding `busy` for as long as the request is in flight, so the button that started it can show that it
      * is waiting. The flag is cleared however the request ends — a failure re-enables the button rather than leaving
      * it spinning on an answer that already came.
      */
    def run[A](action: Future[A], busy: Var[Boolean])(onSuccess: A => Unit): Unit = {
        busy.set(true)
        action.onComplete { outcome =>
            busy.set(false)
            settle(outcome)(onSuccess)
        }
    }

    /** The same, with something to do about a failure beyond showing it.
      *
      * For the actions whose failure is itself news about state this UI is holding: a 409 does not only mean the click
      * did not take, it means what was on screen when it was clicked had already stopped being true. `onFailure` runs
      * after the banner has been set, so a handler that reloads the affected list corrects it in the same beat as the
      * message explaining why — and a handler that sets `error` itself replaces that message deliberately, which is the
      * point of running last.
      */
    def run[A](action: Future[A], busy: Var[Boolean], onFailure: Throwable => Unit)(onSuccess: A => Unit): Unit = {
        busy.set(true)
        action.onComplete { outcome =>
            busy.set(false)
            settle(outcome)(onSuccess)
            outcome.failed.foreach(onFailure)
        }
    }

    /** Fetches something this store will hold, and drops the answer if the session that asked for it has ended.
      *
      * The difference between this and `run` is what the answer is for. `run` is for a button: somebody clicked it,
      * they are waiting, and whatever comes back is about the click. This is for the lists and the settings the store
      * keeps — an answer that arrives after a sign-out is about a session that is over, and writing it here would put
      * one player's data in front of the next.
      *
      * Nothing is reported for a dead session either, which is why the check wraps `settle` rather than sitting inside
      * it: a 401 for a request the previous session made is not news, and `ApiClient` has already ended that session
      * over it.
      */
    private def load[A](action: Future[A], fetches: Fetch*)(commit: A => Unit): Unit = {
        val signIn = currentSignIn
        action.onComplete { outcome =>
            if (stillSignedInAs(signIn)) {
                settle(outcome)(commit)
                fetched.update(_ ++ fetches)
            }
        }
    }

    private def settle[A](outcome: Try[A])(onSuccess: A => Unit): Unit = outcome match {
        case Success(value) => error.set(None); onSuccess(value)
        case Failure(error) => report(error)
    }

    /** Loads everything the signed-in user's home screen needs.
      *
      * A 403 from `/me` is not an error: it is how the API says this Cognito identity has no player yet, which is the
      * case self-registration exists for.
      */
    def loadAll(justSignedIn: Boolean = false): Unit = {
        player.set(PlayerState.Loading)
        val signIn = currentSignIn

        ApiClient.me().onComplete {
            // Signed out while this was in flight. Every branch below writes `player`, and the one
            // that succeeds goes on to fetch five more lists — all of it for a session that is over.
            case _ if !stillSignedInAs(signIn) => ()

            case Success(p) =>
                error.set(None)
                player.set(PlayerState.Registered(p))
                if (justSignedIn) syncEmail(p)
                refreshMatches()
                refreshGames()

            // The one failure that is not a failure: 403 is how the API says this Cognito identity has
            // no player yet, which is what self-registration exists for.
            case Failure(ApiError(403, _)) =>
                error.set(None)
                player.set(PlayerState.Unregistered)

            // Anything else — 5xx, a network error, a response that would not parse — says nothing
            // about whether the account exists. Offering to create one here would invite a registered
            // player to register a second time, so this reports the failure and offers a retry instead.
            case Failure(other) =>
                report(other)
                player.set(PlayerState.Unavailable(messageOf(other)))
        }
    }

    /** Brings matchmaker's copy of the address in step with the token, after a change the player has just confirmed.
      *
      * The other moment the claim can be trusted, and for the same reason sign-in is: `Account.confirmEmail` has
      * redeemed the refresh token, so `fromToken` is the claim of a token Cognito issued after the change rather than
      * one issued before it. Anything else — the address typed into the form, the claim of the token the session
      * started with — is a value nobody can check, which is why this takes the address rather than reading it.
      *
      * Without this the change would still arrive, at the next sign-in. What it costs to wait is notifications going to
      * the previous mailbox until then, and a suppression that the new address should have escaped staying in force
      * because the stored address is still the one that bounced.
      */
    private[ui] def adoptEmail(fromToken: String): Future[Unit] =
        player.now() match {
            case PlayerState.Registered(stored) => syncEmail(stored, fromToken)
            // Nobody to update: an unregistered or unavailable session has no stored address to disagree
            // with, and registration sends whatever the claim says at the time.
            case _ => Future.unit
        }

    /** Brings matchmaker's copy of the address in step with the token, at sign-in.
      *
      * Sign-in is the one moment the claim can be trusted, which is why this is not done on every load. Cognito fixes
      * the claims when it issues a token, so a session that changed its address an hour ago still carries the old one —
      * and a reload that "corrected" the stored address from that claim would undo the change the player had just made.
      * A token just issued by a sign-in has no such gap: whatever it says is what Cognito currently holds.
      *
      * Nothing is lost by waiting. The address is the username on that pool, so a player who changes it signs in with
      * the new one next time, and that sign-in is this. Until then matchmaker has the older address, which costs a
      * notification going to a mailbox the player still owns.
      *
      * Three further things it does not do:
      *
      *   - No claim, no write. `None` means this token carries no address — local development authenticates with a
      *     header, and there is no Cognito identity behind it — which says nothing about the stored one. Clearing a
      *     good address because this client cannot see one would be the worst outcome available.
      *   - Compared case-insensitively, because one address in two cases is one mailbox, and rewriting the row to
      *     restyle it would be a write per sign-in that changes nothing anyone can receive.
      *   - Failure is silent. Nobody asked for this, so an error banner on the home screen would report a problem the
      *     player did not cause and cannot act on; the next sign-in tries again, and until one succeeds the only cost
      *     is notifications going to the older address.
      */
    private def syncEmail(stored: Player): Unit =
        Auth.email.foreach(claim => syncEmail(stored, claim))

    /* The write itself, shared by the two moments a claim is worth believing.
     *
     * Hands back a future that says when it has settled, succeeded or not, for the caller that has
     * something to do afterwards -- `Account` re-reads the notification settings, and doing that before
     * this landed would re-read the address this is replacing. Sign-in ignores it. */
    private def syncEmail(stored: Player, fromToken: String): Future[Unit] = {
        val signIn = currentSignIn
        val claimed = fromToken.trim

        if (claimed.isEmpty || stored.email.exists(_.equalsIgnoreCase(claimed))) Future.unit
        else
            ApiClient.updateEmail(claimed).transform {
                case Success(updated) =>
                    // Only if this is still the session that asked. A sign-out while the call was in
                    // flight has already put something else on screen, and the answer to a request
                    // about the previous session must not overwrite it.
                    if (stillSignedInAs(signIn)) player.set(PlayerState.Registered(updated))
                    Success(())
                case Failure(_) => Success(())
            }
    }

    def refreshMatches(): Unit = {
        load(ApiClient.dueMatches(), Fetch.Due)(due.set)
        load(ApiClient.activeMatches(), Fetch.Active)(active.set)
        load(ApiClient.completedMatches(), Fetch.Completed)(completed.set)
        load(ApiClient.acceptances(), Fetch.Acceptances)(acceptances.set)
        load(ApiClient.invitations(), Fetch.Invitations)(invitations.set)
        load(ApiClient.results(), Fetch.Results)(rows => resultsByMatch.set(rows.groupBy(_.matchId)))
    }

    /** The same as `run`, but handing back a `Future` that says when the request has settled.
      *
      * Success and failure are dealt with exactly as `run` deals with them — the list is set or the error is reported —
      * and the result is always a success, because the only caller is a section waiting to stop showing that it is
      * reloading. A failure there is not a second thing to handle; it is a banner that has already been raised.
      */
    private def reload[A](action: Future[A], fetches: Fetch*)(onSuccess: A => Unit): Future[Unit] = {
        val signIn = currentSignIn

        action.transform { outcome =>
            // Dropped rather than committed when the session that asked has ended, as in `load`. The
            // `Future` still completes: the section that is waiting to stop showing itself as
            // reloading has been unmounted by the sign-out, but it must not be left hanging if it has
            // not.
            try
                if (stillSignedInAs(signIn)) {
                    settle(outcome)(onSuccess)
                    fetched.update(_ ++ fetches)
                }
            catch { case t: Throwable => report(t) }
            Success(())
        }
    }

    /** One list at a time, for the refresh button each section carries.
      *
      * `refreshMatches` reloads all of them because an action in one list usually changes another. These exist for the
      * other case: the user asking a single section whether it is still true, which should not cost four requests or
      * blank out the rest of the page.
      */
    def reloadDue(): Future[Unit] = reload(ApiClient.dueMatches(), Fetch.Due)(due.set)

    def reloadActive(): Future[Unit] = reload(ApiClient.activeMatches(), Fetch.Active)(active.set)

    def reloadAcceptances(): Future[Unit] = reload(ApiClient.acceptances(), Fetch.Acceptances)(acceptances.set)

    def reloadInvitations(): Future[Unit] = reload(ApiClient.invitations(), Fetch.Invitations)(invitations.set)

    /** The finished matches and their results together: the completed lists show the result table under each row, so
      * reloading one without the other would leave a match beside somebody else's outcome.
      */
    def reloadCompleted(): Future[Unit] = {
        val matches = reload(ApiClient.completedMatches(), Fetch.Completed)(completed.set)
        val rows = reload(ApiClient.results(), Fetch.Results)(r => resultsByMatch.set(r.groupBy(_.matchId)))
        matches.zip(rows).map(_ => ())
    }

    def reloadChallenges(gameId: GameId): Future[Unit] =
        reload(ApiClient.challenges(gameId))(list => challengesByGame.update(_.updated(gameId, list)))

    def refreshGames(): Unit = load(ApiClient.games(activeOnly = true), Fetch.Games)(games.set)

    /** Makes sure the game a screen is about can be drawn, deactivated or not.
      *
      * Nothing to do in the ordinary case: an active game is already held, and this costs no request. Otherwise the
      * full list is asked for — the one route there is, since a game has no endpoint of its own — and what comes back
      * is kept in `unlistedGames` rather than in `games`, so the menu and the pickers go on listing the active games
      * only.
      *
      * Only the game asked for is kept, for that reason: the answer holds every game there is, and folding all of them
      * in would quietly turn every "which game?" list on the screen into a list of games nobody may still play.
      */
    def ensureGame(gameId: GameId): Unit =
        if (!games.now().exists(_.gameId == gameId) && !unlistedGames.now().contains(gameId)) {
            loadingUnlistedGame.set(true)
            // Cleared however it ends, like every other flag of its kind here: a failure leaves the
            // screen saying the game is not available, beside the banner saying what went wrong,
            // rather than saying it is still loading for the rest of the session.
            val answered = ApiClient.games(activeOnly = false).transform { outcome =>
                loadingUnlistedGame.set(false)
                outcome
            }
            load(answered)(all =>
                all.find(_.gameId == gameId).foreach(game => unlistedGames.update(_.updated(gameId, game)))
            )
        }

    /** The game a screen is about: active, or reachable-but-deactivated, or not yet known.
      *
      * The two slots asked as one question, so a screen does not have to know that there are two.
      */
    def game(gameId: GameId): Signal[Option[Game]] =
        games.signal.combineWith(unlistedGames.signal).map { (active, unlisted) =>
            active.find(_.gameId == gameId).orElse(unlisted.get(gameId))
        }

    def refreshChallenges(gameId: GameId): Unit =
        load(ApiClient.challenges(gameId))(list => challengesByGame.update(_.updated(gameId, list)))

    def refreshCharacters(gameId: GameId): Unit =
        load(ApiClient.characters(gameId))(list => charactersByGame.update(_.updated(gameId, list)))

}
