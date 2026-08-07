package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** What the UI knows, and how it reloads it.
  *
  * One `Var` per list the API serves, each reloaded by re-fetching rather than by patching the
  * copy held here. The lists are short and an action often changes more than the list it was
  * taken from — accepting a challenge can create a match, which changes three of them at once —
  * so re-fetching is both simpler and less likely to show something that is no longer true.
  */
object Store {

  /** `None` while unknown, `Some(None)` once it is known there is no player yet. That third state
    * is what tells registration from a still-loading page; collapsing it to `None` would flash
    * the registration form at every signed-in user on every load.
    */
  val player: Var[Option[Option[Player]]] = Var(None)

  /** Whether there is a usable token, mirrored into a `Var` because `sessionStorage` is not
    * observable: without this the UI would not notice a session ending until something re-rendered
    * for another reason.
    */
  val signedIn: Var[Boolean] = Var(Auth.isSignedIn)

  /** The token has gone — expired, revoked, or signed out elsewhere. Everything derived from it
    * is dropped, so no stale list is left on screen behind the sign-in prompt.
    */
  def sessionExpired(): Unit = {
    Auth.clearSession()
    signedIn.set(false)
    player.set(None)
    due.set(Seq.empty)
    active.set(Seq.empty)
    completed.set(Seq.empty)
    games.set(Seq.empty)
    challengesByGame.set(Map.empty)
    charactersByGame.set(Map.empty)
    acceptances.set(Seq.empty)
    expandedGames.set(Set.empty)
  }

  val due: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val active: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val completed: Var[Seq[MatchSummary]] = Var(Seq.empty)
  val games: Var[Seq[Game]] = Var(Seq.empty)

  /** Open challenges per game, filled in only for games the user has expanded — there is one
    * request per expansion, and games nobody opens cost nothing.
    */
  val challengesByGame: Var[Map[GameId, Seq[OpenChallenge]]] = Var(Map.empty)

  /** The caller's characters per game, loaded alongside the challenges when a game is expanded.
    * Both offering and accepting a challenge need one.
    */
  val charactersByGame: Var[Map[GameId, Seq[Character[String]]]] = Var(Map.empty)

  /** What the caller has accepted and that has not yet become a match — what `ui.txt` calls the
    * pending acceptances, and the list "back out" acts on.
    */
  val acceptances: Var[Seq[Acceptance]] = Var(Seq.empty)

  val expandedGames: Var[Set[GameId]] = Var(Set.empty)
  val showActive: Var[Boolean] = Var(false)
  val showCompleted: Var[Boolean] = Var(false)

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
    action.onComplete {
      case Success(value) => error.set(None); onSuccess(value)
      case Failure(error) => report(error)
    }

  /** Loads everything the signed-in user's home screen needs.
    *
    * A 403 from `/me` is not an error: it is how the API says this Cognito identity has no player
    * yet, which is the case self-registration exists for.
    */
  def loadAll(): Unit =
    ApiClient.me().onComplete {
      case Success(p) =>
        error.set(None)
        player.set(Some(Some(p)))
        refreshMatches()
        refreshGames()

      case Failure(ApiError(403, _)) =>
        error.set(None)
        player.set(Some(None))

      case Failure(other) =>
        report(other)
        player.set(Some(None))
    }

  def refreshMatches(): Unit = {
    run(ApiClient.dueMatches())(due.set)
    run(ApiClient.activeMatches())(active.set)
    run(ApiClient.completedMatches())(completed.set)
    run(ApiClient.acceptances())(acceptances.set)
  }

  def refreshGames(): Unit = run(ApiClient.games(activeOnly = true))(games.set)

  def refreshChallenges(gameId: GameId): Unit =
    run(ApiClient.challenges(gameId))(list => challengesByGame.update(_.updated(gameId, list)))

  def refreshCharacters(gameId: GameId): Unit =
    run(ApiClient.characters(gameId))(list => charactersByGame.update(_.updated(gameId, list)))

  def toggleGame(gameId: GameId): Unit = {
    val nowExpanded = !expandedGames.now().contains(gameId)
    expandedGames.update(current => if (nowExpanded) current + gameId else current - gameId)
    if (nowExpanded) {
      refreshChallenges(gameId)
      refreshCharacters(gameId)
    }
  }
}
