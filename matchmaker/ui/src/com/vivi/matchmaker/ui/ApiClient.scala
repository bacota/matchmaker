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
import com.vivi.matchmaker.api.TournamentJson
import com.vivi.matchmaker.api.TournamentJson.given
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
        send[Player](
          method = HttpMethod.POST,
          path = "/register",
          body = Some(write(Json.RegisterRequest(nickname, email)))
        )

    /** Renames the caller. The server takes the player from the token, so there is no id to send and no way to rename
      * anyone else.
      */
    def updateNickname(nickname: String): Future[Player] =
        send[Player](method = HttpMethod.PUT, path = "/me", body = Some(write(Json.NicknameRequest(nickname))))

    /** Records the address the token says the player signs in with, so that matchmaker's copy — what it sends
      * notifications to — matches Cognito's.
      *
      * One caller, `Store.syncEmail`, and only at sign-in. The API cannot verify an address, so the only thing worth
      * sending it is the `email` claim of a token Cognito has just issued; an address the player typed, or the claim of
      * an hour-old token, are both things no one can check.
      */
    def updateEmail(email: String): Future[Player] =
        send[Player](method = HttpMethod.PUT, path = "/me/email", body = Some(write(Json.EmailRequest(email))))

    def dueMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches/due")

    def activeMatches(): Future[Seq[MatchSummary]] = get[Seq[MatchSummary]]("/me/matches")

    /** One window of the caller's finished matches — see `CompletedQuery`. */
    def completedMatches(query: CompletedQuery): Future[CompletedPage] =
        get[CompletedPage]("/me/matches/completed" + completedQueryString(query))

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

    /** One window of the public matches another player has finished. */
    def publicCompletedMatches(playerId: PlayerId, query: CompletedQuery): Future[CompletedPage] =
        get[CompletedPage](s"/players/${playerId.value}/matches/completed" + completedQueryString(query))

    /** A character as its page shows it: name, game, description and present owner. */
    def characterProfile(character: CharacterName): Future[CharacterProfile] =
        get[CharacterProfile](characterPath(character))

    /** A character's public matches still being played, whoever owned it for each. */
    def characterMatches(character: CharacterName): Future[Seq[MatchSummary]] =
        get[Seq[MatchSummary]](characterPath(character) + "/matches")

    /** One window of the public matches a character has finished. */
    def characterCompletedMatches(character: CharacterName, query: CompletedQuery): Future[CompletedPage] =
        get[CompletedPage](characterPath(character) + "/matches/completed" + completedQueryString(query))

    private def characterPath(character: CharacterName): String =
        s"/games/${character.gameId.value}/characters/${character.characterId.value}"

    /* The window as the server reads it, each part only when it says something. `asOf` encoded: an
     * instant carries a `:` and may carry a `+`. */
    private def completedQueryString(query: CompletedQuery): String = {
        val parts = Seq(
          Some(s"frame=${query.frame.code}"),
          Some(s"page=${query.page}"),
          query.asOf.map(at => s"asOf=${js.URIUtils.encodeURIComponent(at.toString)}"),
          query.gameId.map(id => s"gameId=${id.value}")
        ).flatten
        parts.mkString("?", "&", "")
    }

    /** What the caller wants to be told about: their answers in general, and the games they have answered differently
      * for. Takes no player id for the same reason `myTournaments` does not.
      */
    def notifications(): Future[NotificationSettings] = get[NotificationSettings]("/me/notifications")

    /** Asks for the caller's own address to be tried again, after mail to it bounced.
      *
      * No arguments: the address is whichever one the server holds for them, which is the one their mail is being sent
      * to. Refused by the server for a complaint — a spam report is not undone by a button — so a caller that offers
      * the button when [[com.vivi.matchmaker.model.EmailSuppression.Notice.canRetry]] is false gets a 400 rather than a
      * surprise.
      */
    def retryNotifications(): Future[Unit] =
        sendUnit(method = HttpMethod.POST, path = "/me/notifications/retry", body = None)

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
          method = HttpMethod.PUT,
          path = "/me/notifications",
          body = Some(
            write(
              Json.PreferencesRequest(
                preferences = preferences,
                isAppliedToGames = applyToGames,
                isAppliedToMatches = applyToMatches
              )
            )
          )
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
          method = HttpMethod.PUT,
          path = s"/me/notifications/games/${gameId.value}",
          body = Some(write(Json.PreferencesRequest(preferences = preferences, isAppliedToMatches = applyToMatches)))
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
          method = HttpMethod.PUT,
          path = s"/games/${gameId.value}/matches/${matchId.value}/notifications",
          body = Some(write(preferences))
        )

    def characters(gameId: GameId): Future[Seq[Character[String]]] =
        get[Seq[Character[String]]](s"/games/${gameId.value}/characters")

    /** Another player's characters in one game, by name only — what the invite form offers a choice from. */
    def characterNames(gameId: GameId, playerId: PlayerId): Future[Seq[CharacterName]] =
        get[Seq[CharacterName]](s"/games/${gameId.value}/players/${playerId.value}/characters")

    /** A game's admins, each with who made them one: what tells the game's screen whether to offer what only a game's
      * admin may, such as choosing its competition formats.
      */
    def gameAdmins(gameId: GameId): Future[Seq[GameAdmin]] =
        get[Seq[GameAdmin]](s"/games/${gameId.value}/admins")

    /** A game's matches, running ones first, for its admins to manage them from; refused for anybody else. */
    def gameMatches(gameId: GameId, player: Option[PlayerId] = None): Future[Seq[GameMatch]] =
        get[Seq[GameMatch]](s"/games/${gameId.value}/matches${player.fold("")(p => s"?playerId=${p.value}")}")

    /** Says whether a match is friendly — a game's admin's to say. */
    def setFriendly(gameId: GameId, matchId: MatchId, friendly: Boolean): Future[Match] =
        send[Match](
          method = HttpMethod.PUT,
          path = s"/games/${gameId.value}/matches/${matchId.value}/friendly",
          body = Some(write(Json.FriendlyRequest(friendly)))
        )

    /* `role=` for one role's ratings, after whatever else the query asks; nothing for the overall ones. */
    private def roleQuery(role: Option[GameRoleId], first: Boolean): String =
        role.fold("")(r => s"${if (first) "?" else "&"}role=${r.value}")

    /** A page of a game's leaderboard, or of one role's, from page 0, best first: anybody may see it. */
    def leaderboard(gameId: GameId, page: Int, role: Option[GameRoleId] = None): Future[Leaderboard] =
        get[Leaderboard](s"/games/${gameId.value}/ratings?page=$page${roleQuery(role, first = false)}")

    /** A game's rated players, overall or in a role, whose nickname begins with `prefix`, with their places: at most
      * the player search's limit, and `hasMore` when there were others.
      */
    def findInRankings(gameId: GameId, prefix: String, role: Option[GameRoleId] = None): Future[Leaderboard] =
        get[Leaderboard](
          s"/games/${gameId.value}/ratings?prefix=${js.URIUtils.encodeURIComponent(prefix)}${roleQuery(role, first = false)}"
        )

    /** One player's standing in a game, overall or in a role: their place, the rating it was worked out from, and their
      * rating now. A 404 if they have no rating in it.
      */
    def standing(gameId: GameId, playerId: PlayerId, role: Option[GameRoleId] = None): Future[EloRating] =
        get[EloRating](s"/games/${gameId.value}/ratings/${playerId.value}${roleQuery(role, first = true)}")

    /** Sets a player's Elo rating in a game outright — a game's admin's to do. */
    def setRating(gameId: GameId, playerId: PlayerId, rating: Int): Future[EloRating] =
        send[EloRating](
          method = HttpMethod.PUT,
          path = s"/games/${gameId.value}/ratings/${playerId.value}",
          body = Some(write(Json.RatingRequest(rating)))
        )

    def games(activeOnly: Boolean): Future[Seq[Game]] =
        get[Seq[Game]](if (activeOnly) "/games?activeOnly=true" else "/games")

    /** Creates a game, or updates one when `gameId` is already assigned. The same route does both, which is why this is
      * `POST /games` rather than a `PUT` on an id that does not exist yet. The server refuses this to anyone who is not
      * an admin, so the button is admin-only too — but the check that matters is the server's.
      *
      * `apiKey` is the game engine's key, sent in the same object as the game's fields — the server reads it apart from
      * the game, stores it, and never sends it back. `None` leaves the stored key as it is.
      */
    def createGame(game: Game, apiKey: Option[String] = None): Future[Game] = {
        val body = upickle.default.writeJs(game).obj
        apiKey.foreach(key => body("apiKey") = ujson.Str(key))
        send[Game](method = HttpMethod.POST, path = "/games", body = Some(ujson.write(ujson.Obj(body))))
    }

    /** How the finished matches of one window of the caller's completed list turned out, in one call — see
      * `MatchService.results`.
      */
    def results(query: CompletedQuery): Future[Seq[Json.ParticipantResultView]] =
        get[Seq[Json.ParticipantResultView]]("/me/results" + completedQueryString(query))

    def matchDetail(gameId: GameId, matchId: MatchId): Future[Match] =
        get[Match](s"/games/${gameId.value}/matches/${matchId.value}")

    /** Asks the server to re-check the match with the game engine, for when a callback has gone missing and
      * matchmaker's idea of whose turn it is has fallen behind.
      */
    def refreshMatch(gameId: GameId, matchId: MatchId): Future[Match] =
        send[Match](
          method = HttpMethod.POST,
          path = s"/games/${gameId.value}/matches/${matchId.value}/refresh",
          body = None
        )

    /** Calls a match off. Only its tournament's owner may, which the server checks — the button that leads here is
      * shown only to them, but that is a courtesy, not the rule.
      */
    def cancelMatch(gameId: GameId, matchId: MatchId): Future[Match] =
        send[Match](
          method = HttpMethod.POST,
          path = s"/games/${gameId.value}/matches/${matchId.value}/cancel",
          body = None
        )

    // ---- tournaments ------------------------------------------------------------------------

    /** The tournaments the caller owns, has entered or is invited to. */
    def myTournaments(): Future[Seq[TournamentSummary]] = get[Seq[TournamentSummary]]("/me/tournaments")

    /** The seats waiting on the caller to choose a role. */
    def tournamentChoices(): Future[Seq[TournamentChoice]] = get[Seq[TournamentChoice]]("/me/tournament-choices")

    /** Where the caller stands in every tournament they have entered. */
    def tournamentStandings(): Future[Seq[TournamentStanding]] =
        get[Seq[TournamentStanding]]("/me/tournament-standings")

    def tournaments(gameId: GameId): Future[Seq[TournamentSummary]] =
        get[Seq[TournamentSummary]](s"/games/${gameId.value}/tournaments")

    def tournament(gameId: GameId, tournamentId: TournamentId): Future[TournamentDetail] =
        get[TournamentDetail](tournamentPath(gameId, tournamentId))

    def createTournament(gameId: GameId, request: TournamentRequest): Future[Tournament] =
        send[Tournament](
          method = HttpMethod.POST,
          path = s"/games/${gameId.value}/tournaments",
          body = Some(write(request))
        )

    def updateTournament(gameId: GameId, tournamentId: TournamentId, request: TournamentRequest): Future[Tournament] =
        send[Tournament](
          method = HttpMethod.PUT,
          path = tournamentPath(gameId, tournamentId),
          body = Some(write(request))
        )

    /** One of the owner's steps through a tournament's life: `open` or `start`. */
    def tournamentStep(gameId: GameId, tournamentId: TournamentId, step: String): Future[Tournament] =
        send[Tournament](method = HttpMethod.POST, path = s"${tournamentPath(gameId, tournamentId)}/$step", body = None)

    def inviteToTournament(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId): Future[Unit] =
        sendUnit(
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/invitations",
          body = Some(write(TournamentJson.TournamentInvite(playerId)))
        )

    def revokeTournamentInvitation(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId): Future[Unit] =
        sendUnit(
          method = HttpMethod.DELETE,
          path = s"${tournamentPath(gameId, tournamentId)}/invitations/${playerId.value}",
          body = None
        )

    /** Invites a character, in a character game: whoever owns it when they answer may enter with it. */
    def inviteCharacterToTournament(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: CharacterId
    ): Future[Unit] =
        sendUnit(
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/character-invitations",
          body = Some(write(TournamentJson.TournamentCharacterInvite(characterId)))
        )

    /** Takes a character's invitation back, or -- for the character's owner -- turns it down. */
    def revokeTournamentCharacterInvitation(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: CharacterId
    ): Future[Unit] =
        sendUnit(
          method = HttpMethod.DELETE,
          path = s"${tournamentPath(gameId, tournamentId)}/character-invitations/${characterId.value}",
          body = None
        )

    def enterTournament(gameId: GameId, tournamentId: TournamentId, characterId: Option[CharacterId]): Future[Entrant] =
        send[Entrant](
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/entries",
          body = Some(write(TournamentJson.EnterRequest(characterId)))
        )

    def withdrawFromTournament(gameId: GameId, tournamentId: TournamentId, entrantId: EntrantId): Future[Unit] =
        sendUnit(
          method = HttpMethod.DELETE,
          path = s"${tournamentPath(gameId, tournamentId)}/entries/${entrantId.value}",
          body = None
        )

    /** Begins the next round: for `duration` if given, and otherwise for the tournament's round duration. */
    def startRound(
        gameId: GameId,
        tournamentId: TournamentId,
        duration: Option[java.time.Duration] = None
    ): Future[TournamentJson.RoundStarted] =
        send[TournamentJson.RoundStarted](
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/rounds",
          body = Some(write(TournamentJson.StartRoundRequest(duration.map(_.getSeconds))))
        )

    def checkRound(gameId: GameId, tournamentId: TournamentId): Future[Unit] =
        sendUnit(method = HttpMethod.POST, path = s"${tournamentPath(gameId, tournamentId)}/check", body = None)

    /** The owner stopping a tournament: one not yet started is deleted -- which the screens call cancelling it -- and
      * one under way schedules no more matches, and finishes with the round being played.
      */
    def stopTournament(gameId: GameId, tournamentId: TournamentId): Future[Unit] =
        sendUnit(method = HttpMethod.POST, path = s"${tournamentPath(gameId, tournamentId)}/stop", body = None)

    def stopTournamentStage(gameId: GameId, tournamentId: TournamentId): Future[Unit] =
        sendUnit(
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/stop-repeating",
          body = None
        )

    def chooseRole(
        gameId: GameId,
        tournamentId: TournamentId,
        matchId: MatchId,
        role: GameRoleId
    ): Future[TournamentMatchView] =
        send[TournamentMatchView](
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/matches/${matchId.value}/role",
          body = Some(write(TournamentJson.ChooseRoleRequest(role)))
        )

    def decideMatch(
        gameId: GameId,
        tournamentId: TournamentId,
        matchId: MatchId,
        ranks: List[EntrantRank]
    ): Future[Unit] =
        sendUnit(
          method = HttpMethod.POST,
          path = s"${tournamentPath(gameId, tournamentId)}/matches/${matchId.value}/result",
          body = Some(write(TournamentJson.DecideRequest(ranks)))
        )

    /** The models a game's tournaments are made from. */
    def tournamentModels(gameId: GameId): Future[Seq[TournamentModel]] =
        get[Seq[TournamentModel]](s"/games/${gameId.value}/tournament-models")

    /** Every model a game could offer. */
    def allTournamentModels(): Future[Seq[TournamentModel]] =
        get[Seq[TournamentModel]]("/tournament-models")

    /** Offers a model in a game: the models the game now offers. */
    def offerTournamentModel(gameId: GameId, modelId: TournamentModelId): Future[Seq[TournamentModel]] =
        send[Seq[TournamentModel]](method = HttpMethod.PUT, path = modelPath(gameId, modelId), body = None)

    /** Stops offering a model in a game: the models the game now offers. */
    def withdrawTournamentModel(gameId: GameId, modelId: TournamentModelId): Future[Seq[TournamentModel]] =
        send[Seq[TournamentModel]](method = HttpMethod.DELETE, path = modelPath(gameId, modelId), body = None)

    /** A site admin's sync of the stored models with those the code defines: what it did. */
    def syncTournamentModels(): Future[TournamentModelSync] =
        send[TournamentModelSync](method = HttpMethod.POST, path = "/tournament-models/sync", body = None)

    private def modelPath(gameId: GameId, modelId: TournamentModelId): String =
        s"/games/${gameId.value}/tournament-models/${scalajs.js.URIUtils.encodeURIComponent(modelId.value)}"

    private def tournamentPath(gameId: GameId, tournamentId: TournamentId): String =
        s"/games/${gameId.value}/tournaments/${tournamentId.value}"

    private def get[A: ReadWriter](path: String): Future[A] = send[A](method = HttpMethod.GET, path = path, body = None)

    private def send[A: ReadWriter](method: HttpMethod, path: String, body: Option[String]): Future[A] =
        request(method = method, path = path, body = body).flatMap { case (status, text) =>
            Future.fromTry(decode[A](status = status, text = text, path = path))
        }

    private def sendUnit(method: HttpMethod, path: String, body: Option[String]): Future[Unit] =
        request(method = method, path = path, body = body).map(_ => ())

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
    private def request(method: HttpMethod, path: String, body: Option[String]): Future[(Int, String)] = {
        // Two ways of saying who is calling, and only one of them is evidence. `LocalServer` reads
        // the header; the gateway reads the token and verifies it before the function is reached.
        // Asking for the token before every call is what lets an expired one be refreshed here,
        // rather than becoming a 401 the player has to sign in again to clear.
        // The session is taken before the token is: a 401 ends only the session that sent the request.
        val signIn = Store.currentSignIn
        if (Config.current.isHeaderAuth)
            send(method = method, path = path, body = body, idToken = None, signIn = signIn)
        else
            Auth.freshIdToken()
                .flatMap(token => send(method = method, path = path, body = body, idToken = token, signIn = signIn))
    }

    private def send(
        method: HttpMethod,
        path: String,
        body: Option[String],
        idToken: Option[String],
        signIn: Int
    ): Future[(Int, String)] = {
        val init = new RequestInit {}
        init.method = method

        val headers = js.Dictionary("accept" -> "application/json")

        if (Config.current.isHeaderAuth) headers("x-external-id") = Config.current.localExternalId
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
                    // A 401 to a request from before a sign-out is about that session, not whoever is here now.
                    if (status == 401 && !Config.current.isHeaderAuth && Store.isStillSignedInAs(signIn))
                        Store.sessionExpired()
                    Future.failed(ApiError(status, messageOf(text)))
            }
    }

    /** The API answers errors as `{"error": "..."}`. Anything else — a gateway's own 401, say — is shown as it arrived
      * rather than replaced with something vaguer.
      */
    private def messageOf(body: String): String =
        Try(ujson.read(body)("error").str).getOrElse(if (body.isEmpty) "no response body" else body)
}
