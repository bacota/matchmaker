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
  * One `Var` per list the API serves, each reloaded by re-fetching rather than by patching the
  * copy held here. The lists are short and an action often changes more than the list it was
  * taken from — accepting a challenge can create a match, which changes three of them at once —
  * so re-fetching is both simpler and less likely to show something that is no longer true.
  */
object Store {

  /** What is known about the caller's player.
    *
    * Four states rather than an `Option`, because the differences matter to what is shown:
    * `Loading` must not flash the registration form at an existing player, and `Unavailable` must
    * not either — a 500 or a dropped connection is not evidence that the account does not exist,
    * and telling a registered player to register would be actively misleading.
    */
  enum PlayerState {
    case Loading
    case Unregistered
    case Registered(player: Player)
    case Unavailable(message: String)
  }

  val player: Var[PlayerState] = Var(PlayerState.Loading)

  /** The player, when there is one. Anything else — loading, unregistered, unreachable — is
    * `None`, so views that only need the player itself do not have to restate the distinction.
    */
  def currentPlayer: Signal[Option[Player]] = player.signal.map {
    case PlayerState.Registered(p) => Some(p)
    case _                         => None
  }

  /** Whether there is a usable token, mirrored into a `Var` because `sessionStorage` is not
    * observable: without this the UI would not notice a session ending until something re-rendered
    * for another reason.
    */
  val signedIn: Var[Boolean] = Var(Auth.isSignedIn)

  /** The player asked to leave: revocation of the refresh token is triggered (best-effort), then
    * the screen is returned to the sign-in form the same way an expired session returns it.
    */
  def signOut(): Unit = {
    Auth.signOut()
    sessionExpired()
  }

  /** The token has gone — expired, revoked, or signed out elsewhere. Everything derived from it
    * is dropped, so no stale list is left on screen behind the sign-in prompt.
    */
  def sessionExpired(): Unit = {
    Auth.clearSession()
    signedIn.set(false)
    player.set(PlayerState.Loading)
    due.set(Seq.empty)
    active.set(Seq.empty)
    completed.set(Seq.empty)
    games.set(Seq.empty)
    challengesByGame.set(Map.empty)
    charactersByGame.set(Map.empty)
    acceptances.set(Seq.empty)
    page.set(Page.Home)
    showChallengeForm.set(false)
    editingGame.set(None)
    // Closed and emptied with the rest: it holds a half-typed address and a password field, and
    // neither belongs to whoever signs in next.
    Account.close()
  }

  val due: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val active: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val completed: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val games: Var[Seq[Game]] = Var(Seq.empty)

  /** Open challenges per game, filled in only for games the user has expanded — there is one
    * request per expansion, and games nobody opens cost nothing.
    */
  val challengesByGame: Var[Map[GameId, Seq[OpenChallengeSummary]]] = Var(Map.empty)

  /** The caller's characters per game, loaded alongside the challenges when a game is expanded.
    * Both offering and accepting a challenge need one.
    */
  val charactersByGame: Var[Map[GameId, Seq[Character[String]]]] = Var(Map.empty)

  /** What the caller has accepted and that has not yet become a match — what `ui.txt` calls the
    * pending acceptances, and the list "back out" acts on.
    */
  val acceptances: Var[Seq[PendingAcceptance]] = Var(Seq.empty)

  /** How each finished match turned out, keyed by its match id: the rows of the result table
    * shown under a completed match. Loaded whole with the lists, not per row.
    */
  val resultsByMatch: Var[Map[MatchId, Seq[Json.ParticipantResultView]]] = Var(Map.empty)

  /** Which screen the left-hand menu has selected.
    *
    * A field rather than a URL: there is still no router, and the browser's address bar is
    * spoken for by the Cognito redirect. What changed is that the games are no longer a list on
    * the home page that expands in place — a game is a screen of its own, so something has to
    * say which one is being looked at.
    */
  enum Page {
    case Home
    case OneGame(gameId: GameId)
    case NewGame
  }

  val page: Var[Page] = Var(Page.Home)

  /** Goes to a screen, loading what it needs on the way.
    *
    * A game's challenges and characters are fetched on arrival rather than held for every game
    * at once: there is one request per game opened, and a game nobody looks at costs nothing.
    * Re-selecting the game already shown reloads it, which is the only refresh this screen has.
    */
  def show(next: Page): Unit = {
    page.set(next)
    next match {
      case Page.OneGame(gameId) =>
        refreshChallenges(gameId)
        refreshCharacters(gameId)
      case _ => ()
    }
  }

  /** The game whose admin edit form is open, if any. One slot rather than a set: editing two
    * games at once is not a thing anyone does, and one open form is one place to look for it.
    */
  val editingGame: Var[Option[GameId]] = Var(None)

  /** Whether the game screen's challenge form is open. Closed by default — the wire-frame asks
    * for a "Create Challenge" button, and a form standing open is not a button.
    */
  val showChallengeForm: Var[Boolean] = Var(false)

  /** How many finished matches the home screen shows. The whole history belongs to the game it
    * was played in; the home screen is a glance at what happened lately.
    */
  val recentlyCompleted: Int = 10

  /** The last thing that went wrong, shown as a banner. A single slot rather than a list: the
    * user acts on the most recent failure, and a queue of stale ones is noise.
    */
  val error: Var[Option[String]] = Var(None)

  def report(error: Throwable): Unit = {
    dom.console.error(error.toString)
    Store.error.set(Some(messageOf(error)))
  }

  private def messageOf(error: Throwable): String = error match {
    case ApiError(401, _)      => "Your session has expired. Sign in again."
    case ApiError(403, m)      => s"Not allowed: $m"
    case ApiError(_, m)        => m
    case other                 => Option(other.getMessage).getOrElse(other.toString)
  }

  /** Runs an action and reports a failure rather than losing it. Every button goes through here:
    * a `Future` whose failure nobody observes disappears silently, which in a UI looks exactly
    * like a button that does nothing.
    */
  def run[A](action: Future[A])(onSuccess: A => Unit): Unit =
    action.onComplete(settle(_)(onSuccess))

  /** The same, holding `busy` for as long as the request is in flight, so the button that started
    * it can show that it is waiting. The flag is cleared however the request ends — a failure
    * re-enables the button rather than leaving it spinning on an answer that already came.
    */
  def run[A](action: Future[A], busy: Var[Boolean])(onSuccess: A => Unit): Unit = {
    busy.set(true)
    action.onComplete { outcome =>
      busy.set(false)
      settle(outcome)(onSuccess)
    }
  }

  private def settle[A](outcome: Try[A])(onSuccess: A => Unit): Unit = outcome match {
    case Success(value) => error.set(None); onSuccess(value)
    case Failure(error) => report(error)
  }

  /** Loads everything the signed-in user's home screen needs.
    *
    * A 403 from `/me` is not an error: it is how the API says this Cognito identity has no player
    * yet, which is the case self-registration exists for.
    */
  def loadAll(): Unit = {
    player.set(PlayerState.Loading)

    ApiClient.me().onComplete {
      case Success(p) =>
        error.set(None)
        player.set(PlayerState.Registered(p))
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

  def refreshMatches(): Unit = {
    run(ApiClient.dueMatches())(due.set)
    run(ApiClient.activeMatches())(active.set)
    run(ApiClient.completedMatches())(completed.set)
    run(ApiClient.acceptances())(acceptances.set)
    run(ApiClient.results())(rows => resultsByMatch.set(rows.groupBy(_.matchId)))
  }

  /** The same as `run`, but handing back a `Future` that says when the request has settled.
    *
    * Success and failure are dealt with exactly as `run` deals with them — the list is set or the
    * error is reported — and the result is always a success, because the only caller is a section
    * waiting to stop showing that it is reloading. A failure there is not a second thing to
    * handle; it is a banner that has already been raised.
    */
  private def reload[A](action: Future[A])(onSuccess: A => Unit): Future[Unit] =
    action.transform { outcome => settle(outcome)(onSuccess); Success(()) }

  /** One list at a time, for the refresh button each section carries.
    *
    * `refreshMatches` reloads all of them because an action in one list usually changes another.
    * These exist for the other case: the user asking a single section whether it is still true,
    * which should not cost four requests or blank out the rest of the page.
    */
  def reloadDue(): Future[Unit] = reload(ApiClient.dueMatches())(due.set)

  def reloadActive(): Future[Unit] = reload(ApiClient.activeMatches())(active.set)

  def reloadAcceptances(): Future[Unit] = reload(ApiClient.acceptances())(acceptances.set)

  /** The finished matches and their results together: the completed lists show the result table
    * under each row, so reloading one without the other would leave a match beside somebody
    * else's outcome.
    */
  def reloadCompleted(): Future[Unit] = {
    val matches = reload(ApiClient.completedMatches())(completed.set)
    val rows = reload(ApiClient.results())(r => resultsByMatch.set(r.groupBy(_.matchId)))
    matches.zip(rows).map(_ => ())
  }

  def reloadChallenges(gameId: GameId): Future[Unit] =
    reload(ApiClient.challenges(gameId))(list => challengesByGame.update(_.updated(gameId, list)))

  def refreshGames(): Unit = run(ApiClient.games(activeOnly = true))(games.set)

  def refreshChallenges(gameId: GameId): Unit =
    run(ApiClient.challenges(gameId))(list => challengesByGame.update(_.updated(gameId, list)))

  def refreshCharacters(gameId: GameId): Unit =
    run(ApiClient.characters(gameId))(list => charactersByGame.update(_.updated(gameId, list)))

}
