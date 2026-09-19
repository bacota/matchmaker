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

/** A failed request, carrying the status so callers can distinguish the cases that mean something: 403 on `/me` means
  * "signed in but not registered", 401 means the token is no good.
  */
case class ApiError(status: Int, message: String) extends RuntimeException(s"$status: $message")

/** The HTTP API, as the browser sees it.
  *
  * Every method sends the Cognito ID token as a bearer token; API Gateway's JWT authorizer verifies it and the function
  * reads the caller's identity from the token's claims. Nothing here sends a player id to say who is calling, because
  * nothing here could be trusted to.
  *
  * Request and response bodies use `Json` from the shared sources — literally the same codecs the server encodes with,
  * so the two cannot drift.
  */
object ApiClient {

    def me(): Future[Player] = get[Player]("/me")

    /** Creates the caller's player. The address is sent along with it from the ID token's `email` claim, so a new
      * player can be written to from the moment they exist rather than only after they have changed their address once.
      */
    def register(nickname: String, email: Option[String]): Future[Player] =
        send[Player](HttpMethod.POST, "/register", Some(write(Json.RegisterRequest(nickname, email))))

    /** Renames the caller. The server takes the player from the token, so there is no id to send and no way to rename
      * anyone else.
      */
    def updateNickname(nickname: String): Future[Player] =
        send[Player](HttpMethod.PUT, "/me", Some(write(Json.NicknameRequest(nickname))))

    /** Records the address the token says the player signs in with, so that matchmaker's copy — what it sends
      * notifications to — matches Cognito's.
      *
      * One caller, `Store.syncEmail`, and only at sign-in. The API cannot verify an address, so the only thing worth
      * sending it is the `email` claim of a token Cognito has just issued; an address the player typed, or the claim of
      * an hour-old token, are both things no one can check.
      */
    def updateEmail(email: String): Future[Player] =
        send[Player](HttpMethod.PUT, "/me/email", Some(write(Json.EmailRequest(email))))

    def dueMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches/due")

    def activeMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches")

    def completedMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches/completed")

    /** Players whose nickname begins with `prefix`, case insensitively, and whether there were more than the answer
      * shows.
      *
      * The prefix goes in the query string encoded, because a nickname may contain anything somebody can type -- a `&`
      * or a `#` unencoded would truncate the search or be read as part of the url.
      */
    def searchPlayers(prefix: String): Future[PlayerSearchResult] =
        get[PlayerSearchResult](s"/players?prefix=${js.URIUtils.encodeURIComponent(prefix)}")

    /** Another player's matches: the ones they marked public, still being played and finished.
      *
      * Two calls rather than one for the same reason the caller's own lists are two: they are two lists on the screen,
      * ordered differently -- what is being played now, and what finished most recently first.
      */
    def publicMatches(playerId: PlayerId): Future[Seq[MatchSummary]] =
        get[Seq[MatchSummary]](s"/players/${playerId.value}/matches")

    def publicCompletedMatches(playerId: PlayerId): Future[Seq[MatchSummary]] =
        get[Seq[MatchSummary]](s"/players/${playerId.value}/matches/completed")

    /** Everything the caller has said yes to and that has not yet become a match. Takes no player id: the server scopes
      * it to whoever the token says is calling.
      */
    def acceptances(): Future[Seq[PendingAcceptance]] = get[Seq[PendingAcceptance]]("/me/acceptances")

    /** What the caller wants to be told about: their answers in general, and the games they have answered differently
      * for. Takes no player id for the same reason `acceptances` does not.
      */
    def notifications(): Future[NotificationSettings] = get[NotificationSettings]("/me/notifications")

    /** Asks for the caller's own address to be tried again, after mail to it bounced.
      *
      * No arguments: the address is whichever one the server holds for them, which is the one their mail is being sent
      * to. Refused by the server for a complaint — a spam report is not undone by a button — so a caller that offers
      * the button when [[com.vivi.matchmaker.model.EmailSuppression.Notice.canRetry]] is false gets a 400 rather than a
      * surprise.
      */
    def retryNotifications(): Future[Unit] = sendUnit(HttpMethod.POST, "/me/notifications/retry", None)

    /** Saves the caller's defaults. `applyToGames` copies them into every game they have answered separately;
      * `applyToMatches` carries the result into the matches they are still playing. Both are the offers the form makes
      * beside the save button, and both default to off — saving a level changes that level and nothing else.
      */
    def updateNotifications(
        preferences: NotificationPreferences,
        applyToGames: Boolean = false,
        applyToMatches: Boolean = false
    ): Future[Unit] =
        sendUnit(
          HttpMethod.PUT,
          "/me/notifications",
          Some(write(Json.PreferencesRequest(preferences, applyToGames, applyToMatches)))
        )

    /** Saves the caller's answers for one game. `applyToMatches` carries them into the matches of that game they are
      * still playing, which is the only cascade this level has: there is no level between one game and another.
      */
    def updateGameNotifications(
        gameId: GameId,
        preferences: NotificationPreferences,
        applyToMatches: Boolean = false
    ): Future[Unit] =
        sendUnit(
          HttpMethod.PUT,
          s"/me/notifications/games/${gameId.value}",
          Some(write(Json.PreferencesRequest(preferences, applyToMatches = applyToMatches)))
        )

    /** The caller's answers for one match. Every kind answered, because a seat's own columns are what decides and they
      * cannot be unsaid — so the form this seeds offers no "Use Default". 404 where the caller has no seat in it, which
      * is also the answer to asking about somebody else's match.
      */
    def matchNotifications(gameId: GameId, matchId: MatchId): Future[SeatNotifications] =
        get[SeatNotifications](s"/games/${gameId.value}/matches/${matchId.value}/notifications")

    def updateMatchNotifications(
        gameId: GameId,
        matchId: MatchId,
        preferences: SeatNotifications
    ): Future[Unit] =
        sendUnit(
          HttpMethod.PUT,
          s"/games/${gameId.value}/matches/${matchId.value}/notifications",
          Some(write(preferences))
        )

    def characters(gameId: GameId): Future[Seq[Character[String]]] =
        get[Seq[Character[String]]](s"/games/${gameId.value}/characters")

    def games(activeOnly: Boolean): Future[Seq[Game]] =
        get[Seq[Game]](if (activeOnly) "/games?activeOnly=true" else "/games")

    /** Creates a game, or updates one when `gameId` is already assigned. The same route does both, which is why this is
      * `POST /games` rather than a `PUT` on an id that does not exist yet. The server refuses this to anyone who is not
      * an admin, so the button is admin-only too — but the check that matters is the server's.
      */
    def createGame(game: Game): Future[Game] =
        send[Game](HttpMethod.POST, "/games", Some(write(game)))

    def challenges(gameId: GameId): Future[Seq[ChallengeSummary]] =
        get[Seq[ChallengeSummary]](s"/games/${gameId.value}/challenges")

    def createChallenge(challenge: Challenge): Future[Challenge] =
        send[Challenge](HttpMethod.POST, "/challenges", Some(write(challenge)))

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

    /** Turns the caller's own challenge into a match: the server asks the game engine to create the game and answers
      * with the match, including the url the player plays it at.
      */
    def startChallenge(gameId: GameId, challengeId: ChallengeId): Future[Match] =
        send[Match](HttpMethod.POST, s"/challenges/${gameId.value}/${challengeId.value}/start", None)

    /** How every finished match turned out, in one call — see `MatchService.results`. */
    def results(): Future[Seq[Json.ParticipantResultView]] =
        get[Seq[Json.ParticipantResultView]]("/me/results")

    def matchDetail(gameId: GameId, matchId: MatchId): Future[Match] =
        get[Match](s"/games/${gameId.value}/matches/${matchId.value}")

    /** Asks the server to re-check the match with the game engine, for when a callback has gone missing and
      * matchmaker's idea of whose turn it is has fallen behind.
      */
    def refreshMatch(gameId: GameId, matchId: MatchId): Future[Match] =
        send[Match](HttpMethod.POST, s"/games/${gameId.value}/matches/${matchId.value}/refresh", None)

    /** Calls a match off. Only its creator may, which the server checks — the button that leads here is shown only to
      * them, but that is a courtesy, not the rule.
      */
    def cancelMatch(gameId: GameId, matchId: MatchId): Future[Match] =
        send[Match](HttpMethod.POST, s"/games/${gameId.value}/matches/${matchId.value}/cancel", None)

    /** Backs out of a challenge already accepted. The player id is in the path because the route also serves a
      * challenger removing someone else's acceptance; the server still checks that the caller is entitled to either.
      */
    def withdraw(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): Future[Unit] =
        sendUnit(
          HttpMethod.DELETE,
          s"/challenges/${gameId.value}/${challengeId.value}/acceptances/${playerId.value}",
          None
        )

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
      * `fetch` only fails its promise when the request never happened; a 500 is a perfectly successful fetch. Without
      * this every caller would have to check `response.ok` itself.
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
                case (status, text)                                  =>
                    // The token has expired or been revoked. Dropping it here means the next render shows
                    // the sign-in button instead of repeating a request that cannot succeed.
                    // In header mode there is no session to expire, and clearing one would only wipe the
                    // screen; a 401 there means the server is not in header mode, which the message says.
                    if (status == 401 && !Config.current.headerAuth) Store.sessionExpired()
                    Future.failed(ApiError(status, messageOf(text)))
            }
    }

    /** The API answers errors as `{"error": "..."}`. Anything else — a gateway's own 401, say — is shown as it arrived
      * rather than replaced with something vaguer.
      */
    private def messageOf(body: String): String =
        Try(ujson.read(body)("error").str).getOrElse(if (body.isEmpty) "no response body" else body)
}
