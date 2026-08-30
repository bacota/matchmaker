package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.Try
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit}
import upickle.default.{ReadWriter, read, write}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.api.Json.given
import com.vivi.matchmaker.model._

/** A failed request, carrying the status so callers can distinguish the cases that mean
  * something: 403 on `/me` means "signed in but not registered", 401 means the token is no good.
  */
case class ApiError(status: Int, message: String) extends RuntimeException(s"$status: $message")

/** The HTTP API, as the browser sees it.
  *
  * Every method sends the Cognito ID token as a bearer token; API Gateway's JWT authorizer
  * verifies it and the function reads the caller's identity from the token's claims. Nothing here
  * sends a player id to say who is calling, because nothing here could be trusted to.
  *
  * Request and response bodies use `Json` from the shared sources — literally the same codecs the
  * server encodes with, so the two cannot drift.
  */
object ApiClient {

  def me(): Future[Player] = get[Player]("/me")

  def register(nickname: String): Future[Player] =
    send[Player](HttpMethod.POST, "/register", Some(write(Json.RegisterRequest(nickname))))

  def dueMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches/due")

  def activeMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches")

  def completedMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches/completed")

  /** Everything the caller has said yes to and that has not yet become a match. Takes no player
    * id: the server scopes it to whoever the token says is calling.
    */
  def acceptances(): Future[Seq[PendingAcceptance]] = get[Seq[PendingAcceptance]]("/me/acceptances")

  def characters(gameId: GameId): Future[Seq[Character[String]]] =
    get[Seq[Character[String]]](s"/games/${gameId.value}/characters")

  def games(activeOnly: Boolean): Future[Seq[Game]] =
    get[Seq[Game]](if (activeOnly) "/games?activeOnly=true" else "/games")

  /** Creates a game, or updates one when `gameId` is already assigned. The same route does both,
    * which is why this is `POST /games` rather than a `PUT` on an id that does not exist yet. The
    * server refuses this to anyone who is not an admin, so the button is admin-only too — but the
    * check that matters is the server's.
    */
  def createGame(game: Game): Future[Game] =
    send[Game](HttpMethod.POST, "/games", Some(write(game)))

  def challenges(gameId: GameId): Future[Seq[OpenChallengeSummary]] =
    get[Seq[OpenChallengeSummary]](s"/games/${gameId.value}/challenges")

  def createChallenge(challenge: OpenChallenge): Future[OpenChallenge] =
    send[OpenChallenge](HttpMethod.POST, "/challenges", Some(write(challenge)))

  def deleteChallenge(gameId: GameId, challengeId: ChallengeId): Future[Unit] =
    sendUnit(HttpMethod.DELETE, s"/challenges/${gameId.value}/${challengeId.value}", None)

  def accept(
      gameId: GameId,
      challengeId: ChallengeId,
      characterId: Option[CharacterId],
      gameRoleId: GameRoleId
  ): Future[Acceptance] =
    send[Acceptance](
      HttpMethod.POST,
      s"/challenges/${gameId.value}/${challengeId.value}/acceptances",
      Some(write(Json.AcceptRequest(characterId, gameRoleId)))
    )

  /** Turns the caller's own challenge into a match: the server asks the game engine to create the
    * game and answers with the match, including the url the player plays it at.
    */
  def startChallenge(gameId: GameId, challengeId: ChallengeId): Future[Match] =
    send[Match](HttpMethod.POST, s"/challenges/${gameId.value}/${challengeId.value}/start", None)

  /** How every finished match turned out, in one call — see `MatchService.results`. */
  def results(): Future[Seq[Json.ParticipantResultView]] =
    get[Seq[Json.ParticipantResultView]]("/me/results")

  def matchDetail(gameId: GameId, matchId: MatchId): Future[Match] =
    get[Match](s"/games/${gameId.value}/matches/${matchId.value}")

  /** Asks the server to re-check the match with the game engine, for when a callback has gone
    * missing and matchmaker's idea of whose turn it is has fallen behind.
    */
  def refreshMatch(gameId: GameId, matchId: MatchId): Future[Match] =
    send[Match](HttpMethod.POST, s"/games/${gameId.value}/matches/${matchId.value}/refresh", None)

  /** Calls a match off. Only its creator may, which the server checks — the button that leads
    * here is shown only to them, but that is a courtesy, not the rule.
    */
  def cancelMatch(gameId: GameId, matchId: MatchId): Future[Match] =
    send[Match](HttpMethod.POST, s"/games/${gameId.value}/matches/${matchId.value}/cancel", None)

  /** Backs out of a challenge already accepted. The player id is in the path because the route
    * also serves a challenger removing someone else's acceptance; the server still checks that
    * the caller is entitled to either.
    */
  def withdraw(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): Future[Unit] =
    sendUnit(HttpMethod.DELETE, s"/challenges/${gameId.value}/${challengeId.value}/acceptances/${playerId.value}", None)

  def createCharacter(
      gameId: GameId,
      name: String,
      description: String,
      playerExternalId: String
  ): Future[Character[String]] =
    send[Character[String]](
      HttpMethod.POST,
      s"/games/${gameId.value}/characters",
      // `externalId` on this route names the player the character is being created for, and the
      // server refuses any value but the caller's own. It is the caller's `sub`, which is exactly
      // what the token already says — the field is redundant here and simply echoed back.
      Some(write(Json.CharacterRequest(name, description, playerExternalId)))
    )

  private def get[A: ReadWriter](path: String): Future[A] = send[A](HttpMethod.GET, path, None)

  private def send[A: ReadWriter](method: HttpMethod, path: String, body: Option[String]): Future[A] =
    request(method, path, body).flatMap { case (status, text) =>
      Future.fromTry(decode[A](status, text, path))
    }

  private def sendUnit(method: HttpMethod, path: String, body: Option[String]): Future[Unit] =
    request(method, path, body).map(_ => ())

  private def decode[A: ReadWriter](status: Int, text: String, path: String): Try[A] =
    Try(read[A](text)).recover { case error =>
      // A body that will not parse is not a transport failure; saying which call produced it is
      // the difference between a one-line fix and a hunt.
      throw ApiError(status, s"could not read the response to $path: ${error.getMessage}")
    }

  /** Performs the request and turns a non-2xx into a failed `Future`.
    *
    * `fetch` only fails its promise when the request never happened; a 500 is a perfectly
    * successful fetch. Without this every caller would have to check `response.ok` itself.
    */
  private def request(method: HttpMethod, path: String, body: Option[String]): Future[(Int, String)] =
    // Two ways of saying who is calling, and only one of them is evidence. `LocalServer` reads
    // the header; the gateway reads the token and verifies it before the function is reached.
    // Asking for the token before every call is what lets an expired one be refreshed here,
    // rather than becoming a 401 the player has to sign in again to clear.
    if (Config.current.headerAuth) send(method, path, body, None)
    else Auth.freshIdToken().flatMap(token => send(method, path, body, token))

  private def send(
      method: HttpMethod,
      path: String,
      body: Option[String],
      idToken: Option[String]
  ): Future[(Int, String)] = {
    val init = new RequestInit {}
    init.method = method

    val headers = js.Dictionary("accept" -> "application/json")

    if (Config.current.headerAuth) headers("x-external-id") = Config.current.localExternalId
    else idToken.foreach(token => headers("authorization") = s"Bearer $token")
    body.foreach { payload =>
      headers("content-type") = "application/json"
      init.body = payload
    }
    init.headers = headers

    dom
      .fetch(s"${Config.current.apiEndpoint}$path", init)
      .toFuture
      .flatMap(response => response.text().toFuture.map(text => (response.status, text)))
      .flatMap {
        case (status, text) if status >= 200 && status < 300 => Future.successful((status, text))
        case (status, text) =>
          // The token has expired or been revoked. Dropping it here means the next render shows
          // the sign-in button instead of repeating a request that cannot succeed.
          // In header mode there is no session to expire, and clearing one would only wipe the
          // screen; a 401 there means the server is not in header mode, which the message says.
          if (status == 401 && !Config.current.headerAuth) Store.sessionExpired()
          Future.failed(ApiError(status, messageOf(text)))
      }
  }

  /** The API answers errors as `{"error": "..."}`. Anything else — a gateway's own 401, say — is
    * shown as it arrived rather than replaced with something vaguer.
    */
  private def messageOf(body: String): String =
    Try(ujson.read(body)("error").str).getOrElse(if (body.isEmpty) "no response body" else body)
}
