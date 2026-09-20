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
        // Nobody owns a banner for a session that is over.
        bannerOwner = Banner.Nobody
        Auth.clearSession()
        signedIn.set(false)
        player.set(PlayerState.Loading)
        due.set(Seq.empty)
        active.set(Seq.empty)
        completed.set(Seq.empty)
        games.set(Seq.empty)
        unlistedGames.set(Map.empty)
        lookingForGames.set(Set.empty)
        challengesByGame.set(Map.empty)
        charactersByGame.set(Map.empty)
        acceptances.set(Seq.empty)
        invitations.set(Seq.empty)
        // And what was last said about one. The stamp above would keep it from being read anyway;
        // this is so that nothing is held about a player who has gone, which is what the rest of
        // this method is for.
        invitationStatus.set(None)
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
        // rather than reporting this player's empty lists as theirs. The stamps go with them: the
        // sign-in counter already drops every answer still in flight, and a stamp left behind would
        // be compared against the next session's requests.
        outcomes.set(Map.empty)
        latestAsk.clear()
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
      * The keyed ones carry what they are about. A `Map` slot per game already tells a screen whether that game's list
      * has arrived — a missing key rather than an empty list — so those screens do not ask `loading` about them. What
      * the name is needed for is the ordering: two answers about the *same* game overwrite one another, so each has to
      * be tellable from the other. See `latestAsk`.
      */
    enum Fetch {
        case Due, Active, Completed, Results, Games, Acceptances, Invitations
        case Challenges(gameId: GameId)
        case Characters(gameId: GameId)
        case PlayerSearch
        case NotificationSettings
    }

    /* How each list's *latest* answer went: absent until one has come, `true` for an answer that
     * brought a list, `false` for a request that failed.
     *
     * The latest rather than the best. Two accumulating sets stood here -- "has answered" and "has
     * answered with a list" -- and a fetch that succeeded and later failed stayed in both, because
     * nothing was ever taken out of either. So a list held stale contents while reporting itself
     * known and not failed, which is the one combination that offers no way out: the section drew
     * the old rows, decided what the player could do from them, and showed no retry, since by its
     * own account nothing had gone wrong. One slot per list, overwritten by each answer, cannot say
     * that -- a failure replaces the success it followed rather than joining it.
     *
     * Why the distinction is needed at all: a failed fetch is not still in flight, so a section over
     * one must stop saying "Loading..." -- that would be the worse lie -- and what it has instead is
     * an empty list. Anything deciding what a player may do by reading such a list decides from a
     * blank, which is what the invitations section did: it hides the invitations already accepted by
     * comparing two lists, and with no acceptances to compare against it hid nothing and offered
     * buttons that could only be refused.
     *
     * One map rather than a flag per list, because every list has the distinction and only some have
     * had to notice it. */
    private val outcomes: Var[Map[Fetch, Boolean]] = Var(Map.empty)

    /* Which request is the newest one asked for each list, so that an older one's answer can be told
     * from the current one's.
     *
     * Reloads overlap: answering an invitation re-reads the invitations, the acceptances and both
     * match lists at once, and a second answer a moment later asks for the same lists again while
     * the first set is still in flight. Nothing orders the responses, so the older request can land
     * last -- and committing it would restore a row the player has just answered, or an acceptance
     * list from before they accepted, and leave it there until something else happened to reload.
     *
     * A stamp per list rather than one counter for the store: two lists reloaded together are two
     * questions, and the answer to one is not made stale by a newer question about the other.
     *
     * A plain `var` and a plain `Map` because nothing renders either: this decides whether an answer
     * is committed, and what is rendered is what the commit writes. */
    private var requests: Int = 0
    private val latestAsk = scala.collection.mutable.Map.empty[Fetch, Int]

    /** Stamps a request as the newest for every list it will answer for, and says what its stamp is. */
    private def ask(fetches: Seq[Fetch]): Int = {
        requests += 1
        fetches.foreach(what => latestAsk.update(what, requests))
        requests
    }

    /** Whether `stamp`'s answer is still the current answer for every list it speaks for.
      *
      * Every fetch that writes into this store names one, which is what makes this worth asking. A request about a
      * single game or a single search is not made safe by being keyed: the key keeps it from overwriting an answer
      * about something *else*, and says nothing about the previous answer to the same question. A game's challenges are
      * re-read by a refresh button and by every callback that changes them — creating, inviting, revoking, removing an
      * acceptance — so two answers about one game overlap readily, and the older landing last puts back the invitation
      * that was just withdrawn. Hence [[Fetch.Challenges]] and its neighbours carrying their key.
      *
      * The one exception is `reloadPublicMatches`, which has a counter of its own because the same guard has to decide
      * when its page stops saying it is loading — see `publicMatchesFetch`. It remains vacuously true here.
      */
    private def newest(stamp: Int, fetches: Seq[Fetch]): Boolean =
        fetches.forall(what => latestAsk.get(what).contains(stamp))

    /** Whether a list is still on its way, which is to say nothing has answered for it yet this session. */
    def loading(what: Fetch): Signal[Boolean] = outcomes.signal.map(!_.contains(what))

    /** Whether what is held for a list is this session's own answer rather than the empty default. */
    def known(what: Fetch): Signal[Boolean] = outcomes.signal.map(_.get(what).contains(true))

    /** Whether the last thing to happen to a list was its request failing, so what is held for it is nothing.
      *
      * The third state a section needs, and the one most often inferred wrongly from emptiness. A screen that can tell
      * it from "nothing yet" can offer the thing that helps, which is to ask again.
      */
    def failed(what: Fetch): Signal[Boolean] = outcomes.signal.map(_.get(what).contains(false))

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

    /** Which games are being looked for right now.
      *
      * Its own state rather than a `Fetch`: a `Fetch` says a list has been answered once this session, and this is
      * asked per game and only for the games that are missing. Without it a screen would read "not yet answered" as
      * "not there" for as long as the request took, and say so.
      *
      * A set rather than one flag, because two of these can be in flight — one screen navigated away from before it
      * answered, and the next one asking for a different game. One flag was cleared by whichever answered first, which
      * told the screen still waiting that its game was not there. Each lookup takes its own game out, so a stale answer
      * releases only what it was asked about.
      *
      * It is also what keeps a second lookup for the same game from being made: re-selecting a game is this screen's
      * refresh, and `show` calls `ensureGame` each time.
      */
    private val lookingForGames: Var[Set[GameId]] = Var(Set.empty)

    /** Whether the game this screen is about is still being looked for. */
    def lookingForGame(gameId: GameId): Signal[Boolean] = lookingForGames.signal.map(_.contains(gameId))

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

    /* What the last answer to an invitation was, and which session answered it.
     *
     * Here rather than in the view for the reason every other per-player value is: this one names a
     * game the *previous* player was invited to, and a view-local slot outlives the session that
     * filled it -- `sessionExpired` cannot reach into the screen to clear it. Which made it one
     * player's data sitting in the next player's page, announced or not.
     *
     * Stamped with the sign-in it belongs to rather than merely cleared, because clearing alone
     * leaves the other half: `Store.run` drops nothing, so an accept that answers after a sign-out
     * still reports itself, and it would report itself into the session that had just started. The
     * stamp makes such a write unreadable instead of racing it. */
    private val invitationStatus: Var[Option[(Int, String)]] = Var(None)

    /** Says what has just been done with an invitation, for the status line in that section to announce. */
    def sayAboutInvitations(said: String): Unit = invitationStatus.set(Some((currentSignIn, said)))

    /** What to announce, which is nothing at all unless this session is the one that said it.
      *
      * Empty rather than absent, because a live region has to be in the page before the text arrives in it: the element
      * stands there always and it is the words that come and go.
      */
    val invitationsSaid: Signal[String] = invitationStatus.signal.map {
        case Some((signIn, said)) if stillSignedInAs(signIn) => said
        case _                                               => ""
    }

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
            reload(ApiClient.searchPlayers(prefix), Fetch.PlayerSearch) { result =>
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
        if (notificationSettings.now().isEmpty)
            load(ApiClient.notifications(), Fetch.NotificationSettings)(s => notificationSettings.set(Some(s)))

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
        reload(ApiClient.notifications(), Fetch.NotificationSettings)(s => notificationSettings.set(Some(s)))

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
        action.onComplete(settle(_, Banner.Action)(onSuccess))

    /** Who raised the banner now showing, which is what decides who may clear it. */
    private enum Banner {

        /** Nothing is showing, so there is nothing to be careful of. */
        case Nobody

        /** Something the player did — a button, or a refusal this UI made itself. */
        case Action

        /** A list that failed to arrive, named by the lists that request spoke for. */
        case Background(lists: Set[Fetch])
    }

    /* Which of those raised the banner now showing.
     *
     * An action's success clears any banner: the click worked, so whatever went wrong before it is
     * history. A *fetch's* success clears only a banner about the same lists, and that last part is
     * the whole of why this names them rather than being a flag.
     *
     * A flag said no more than "a fetch raised it", and two fetches run together: answering an
     * invitation re-reads the invitations and the acceptances at once. When one of them failed and
     * the other then succeeded, the success saw only "a fetch raised it" -- true, and about the
     * sibling that had just failed -- and erased a banner a second old, leaving a section empty with
     * nothing on screen to say why or to offer another go. Asking whether the success is about the
     * *same* lists answers no in that case and yes to a retry of the failed list itself, which is the
     * one case where clearing it is right.
     *
     * A fetch's success also leaves an action's banner alone, because a list arriving says nothing
     * about an action that failed -- and the reloads a 404/409 handler starts are expected to succeed,
     * since they succeed precisely because the refusal was real. Letting one of those clear the
     * message would leave a screen that had visibly changed with no account of why.
     *
     * Which is also why this is not a flag saying "hold the banner I just set": that has to be
     * released by something, and whatever releases it is a race -- a second click clears the hold, its
     * own failure raises a newer banner, and the first click's reload lands and clears that. Asking
     * who owns the banner has no such window, because the answer travels with the banner rather than
     * with the reloads.
     *
     * A plain `var` because nothing renders it; what is rendered is `error`. */
    private var bannerOwner: Banner = Banner.Nobody

    /** Whether an outcome belonging to `owner` may clear the banner that is up.
      *
      * An untagged fetch — one that speaks for no `Fetch` at all, like `reloadChallenges` — carries the empty set,
      * which is a subset of every other, so it may clear another untagged fetch's banner and nothing else's.
      */
    private def mayClear(owner: Banner): Boolean = (owner, bannerOwner) match {
        case (_, Banner.Nobody)                                    => true
        case (Banner.Action, _)                                    => true
        case (Banner.Background(mine), Banner.Background(showing)) => showing.subsetOf(mine)
        case _                                                     => false
    }

    /** Raises the banner for something the player did that this UI decided against, rather than something the server
      * refused: a match with no url to open, a form that does not add up.
      *
      * Through here rather than by setting `error`, so that it is a banner with an owner — one a list reload cannot
      * clear by succeeding, exactly like a refusal from the server.
      */
    def reportProblem(message: String): Unit = {
        error.set(Some(message))
        bannerOwner = Banner.Action
    }

    /** The same, holding `busy` for as long as the request is in flight, so the button that started it can show that it
      * is waiting. The flag is cleared however the request ends — a failure re-enables the button rather than leaving
      * it spinning on an answer that already came.
      */
    def run[A](action: Future[A], busy: Var[Boolean])(onSuccess: A => Unit): Unit = {
        busy.set(true)
        action.onComplete { outcome =>
            busy.set(false)
            settle(outcome, Banner.Action)(onSuccess)
        }
    }

    /** The same, with something to do about a failure beyond showing it.
      *
      * For the actions whose failure is itself news about state this UI is holding: a 409 does not only mean the click
      * did not take, it means what was on screen when it was clicked had already stopped being true. `onFailure` runs
      * after the banner has been set, so a handler that reloads the affected list corrects it in the same beat as the
      * message explaining why — and a handler that sets `error` itself replaces that message deliberately, which is the
      * point of running last.
      *
      * A handler that reloads needs nothing else to keep that message: a reload is not an action, and a fetch's success
      * leaves an action's banner alone. See `Banner`.
      */
    def run[A](action: Future[A], busy: Var[Boolean], onFailure: Throwable => Unit)(onSuccess: A => Unit): Unit = {
        busy.set(true)
        action.onComplete { outcome =>
            busy.set(false)
            settle(outcome, Banner.Action)(onSuccess)
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
        val stamp = ask(fetches)
        action.onComplete { outcome =>
            // Superseded as well as signed out: an answer to a question since asked again is not this
            // list's current answer, and committing it would undo the newer one. See `newest`.
            if (stillSignedInAs(signIn) && newest(stamp, fetches)) {
                settle(outcome, Banner.Background(fetches.toSet))(commit)
                // However it ended: what the section shows next is decided by which of the two it was.
                // See `outcomes`.
                outcomes.update(_ ++ fetches.map(_ -> outcome.isSuccess))
            }
        }
    }

    /* `owner` says what this outcome belongs to, which decides both whose banner may be cleared on a
     * success and who owns the one a failure raises. See `Banner`. The value is committed either way
     * -- what the distinction protects is the explanation, not the list. */
    private def settle[A](outcome: Try[A], owner: Banner)(onSuccess: A => Unit): Unit = outcome match {
        case Success(value) =>
            if (mayClear(owner)) {
                error.set(None)
                bannerOwner = Banner.Nobody
            }
            onSuccess(value)
        case Failure(problem) =>
            report(problem)
            bannerOwner = owner
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
        val stamp = ask(fetches)

        action.transform { outcome =>
            // Dropped rather than committed when the session that asked has ended, or when this list
            // has since been asked for again — as in `load`, and for the same two reasons. The
            // `Future` still completes either way: the section that is waiting to stop showing itself
            // as reloading has been unmounted by the sign-out, but it must not be left hanging if it
            // has not, and a superseded reload is still a reload that has finished.
            try
                if (stillSignedInAs(signIn) && newest(stamp, fetches)) {
                    settle(outcome, Banner.Background(fetches.toSet))(onSuccess)
                    outcomes.update(_ ++ fetches.map(_ -> outcome.isSuccess))
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

    /** The invitations and the acceptances together, which is what the invitations section is drawn from.
      *
      * Its own reload rather than `reloadInvitations` alone: which invitations are still to answer is a question about
      * both lists, and the section is the only place on the page from which a failed acceptances fetch can be retried —
      * the sections drawn from the acceptances are absent when that list is empty, refresh button and all.
      */
    def reloadInvitationsWithAcceptances(): Future[Unit] =
        reloadInvitations().zip(reloadAcceptances()).map(_ => ())

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
        reload(ApiClient.challenges(gameId), Fetch.Challenges(gameId))(list =>
            challengesByGame.update(_.updated(gameId, list))
        )

    def refreshGames(): Unit = load(ApiClient.games(activeOnly = true), Fetch.Games)(games.set)

    /** What to re-read after an admin saves a game.
      *
      * The active list, for the menu and every picker drawn from it — and the copy held in `unlistedGames`, which
      * `refreshGames` cannot reach and `ensureGame` will not fetch again, since holding it is what tells that method
      * there is nothing to look for. A game saved while deactivated would otherwise leave its own screen showing the
      * name, roles and settings it had before the save, and its edit form reopening on that stale snapshot to submit it
      * again.
      *
      * Both copies are written from the save's own answer, which is the authoritative account of what the game now is —
      * the server has just said so. Only then is the list re-read, and the re-read is for what this cannot know:
      * whatever else has changed in it, and the order the server puts it in.
      *
      * Written here rather than left to that re-read, which was the bug: the screen behind this form and the form
      * itself are drawn from `games`, so until the request landed they both showed the name, description and roles the
      * game had *before* the save — and if it failed they showed them for the rest of the session, over a save that had
      * actually succeeded, with the form ready to submit the stale copy again.
      *
      * Which copy it belongs in is what `active` decides, so a save that flips it moves the game between them: into
      * `games` and out of `unlistedGames` when it is active, and the other way when it is not. `refreshGames` reaches
      * neither case on its own — it fetches the active games, so it cannot say what became of one that is no longer
      * among them.
      */
    def gameSaved(saved: Game): Unit = {
        games.update { held =>
            if (!saved.active) held.filterNot(_.gameId == saved.gameId)
            else if (held.exists(_.gameId == saved.gameId))
                held.map(game => if (game.gameId == saved.gameId) saved else game)
            // Appended rather than placed: a game just created is not in the list at all, and where the
            // server would sort it is exactly what the re-read below is for.
            else held :+ saved
        }
        unlistedGames.update(held => if (saved.active) held - saved.gameId else held.updated(saved.gameId, saved))
        refreshGames()
    }

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
        if (
          !games.now().exists(_.gameId == gameId) && !unlistedGames.now().contains(gameId) &&
          !lookingForGames.now().contains(gameId)
        ) {
            val signIn = currentSignIn
            lookingForGames.update(_ + gameId)
            /* Released however it ends, like every other flag of its kind here: a failure leaves the
             * screen saying the game is not available, beside the banner saying what went wrong, rather
             * than saying it is still loading for the rest of the session.
             *
             * Guarded by the sign-in counter, which `load` below cannot do for this: `load` guards what
             * it commits, and this runs on the way there. An answer to a request the previous session
             * made would otherwise release a lookup this one is still waiting on -- the counter is
             * incremented before anything is cleared, so a request in flight across a sign-out is
             * exactly the case it catches. */
            val answered = ApiClient.games(activeOnly = false).transform {
                case failure @ Failure(_) =>
                    if (stillSignedInAs(signIn)) lookingForGames.update(_ - gameId)
                    failure
                case success => success
            }
            load(answered) { all =>
                all.find(_.gameId == gameId).foreach(game => unlistedGames.update(_.updated(gameId, game)))
                lookingForGames.update(_ - gameId)
            }
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
        load(ApiClient.challenges(gameId), Fetch.Challenges(gameId))(list =>
            challengesByGame.update(_.updated(gameId, list))
        )

    def refreshCharacters(gameId: GameId): Unit =
        load(ApiClient.characters(gameId), Fetch.Characters(gameId))(list =>
            charactersByGame.update(_.updated(gameId, list))
        )

}
