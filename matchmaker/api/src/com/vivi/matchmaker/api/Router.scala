package com.vivi.matchmaker.api

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import cats.syntax.all._
import upickle.default.{ReadWriter, read, write}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.service._
import com.vivi.matchmaker.util.JsonValues
import ApiGateway.{Request, Response}
import Json.given
import TournamentJson.given

/** Maps requests onto service calls.
  *
  * Every route needs the caller's identity, so it is resolved once here rather than in each branch. How it is
  * established is the `Authenticator`'s business, not the router's: the same routes serve a gateway-verified Cognito
  * token and a trusted local header.
  */
object Router {

    /** `services` is by name so that a rejected request never builds them. `Handler` passes a lazy val that opens the
      * database pool on first touch, and an unauthenticated request is answered before any route is chosen — a 401 has
      * no business constructing a pool, and on a cold container it would pay the whole initialization to do it.
      */
    def dispatch(services: => Services[String], request: Request, authenticator: Authenticator): IO[Response] = {
        val where = s"${request.method} ${request.path}"
        // An authenticator that has to look a key up can fail as any database read can, and that
        // failure is answered and logged like a route's rather than escaping unhandled.
        authenticator
            .callerOf(request)
            .flatMap {
                case Left(rejection) => IO.pure(rejection)
                case Right(caller)   =>
                    // `route` is called inside the IO rather than before it: it forces the by-name
                    // `services`, and a pool that fails to open would otherwise throw while this IO is
                    // being built — outside the `handleError` below, and so unlogged.
                    IO(route(services = services, request = request, caller = caller)).flatten
            }
            .handleError(Errors.toResponse(_, where))
    }

    /* A case added here is only half of a new endpoint.
     *
     * The deployed API Gateway enumerates its routes and has no `$default`, so a path with no
     * route key is a 404 at the gateway and this function is never invoked — nothing reaches
     * CloudWatch, which makes it look like the caller is not calling at all. The route key belongs
     * in `local.routes` in terraform/modules/api/main.tf, or in `local.engine_routes` for a game
     * engine callback, which carries the API key instead of the JWT authorizer.
     *
     * To tell the two apart from outside: curl the path with no credentials. An enumerated route
     * answers 401, an unregistered one answers 404.
     */
    private def route(services: Services[String], request: Request, caller: String): IO[Response] =
        (request.method.toUpperCase, request.segments) match {

            case ("POST", "register" :: Nil) =>
                body[Json.RegisterRequest](request).flatMap(r =>
                    created(services.registration.register(nickname = r.nickname, externalId = caller, email = r.email))
                )

            case ("GET", "me" :: Nil) =>
                ok(services.players.me(caller))

            // The password belongs to the Cognito identity and is changed against Cognito by the
            // browser, never through this API.
            case ("PUT", "me" :: Nil) =>
                body[Json.NicknameRequest](request).flatMap(r =>
                    ok(services.players.updateNickname(caller, r.nickname))
                )

            // So does the email address — but unlike the password, matchmaker keeps a copy of it to send
            // notifications to, and this is how the browser reports what Cognito already holds: at
            // sign-in, when the `email` claim of the token just issued disagrees with the stored copy.
            // Not when the player changes their address, because at that moment no token agrees with the
            // new one yet. Separate from `PUT /me` because it is a different event: one is the player
            // renaming themselves here, the other is this API being told what happened elsewhere.
            case ("PUT", "me" :: "email" :: Nil) =>
                body[Json.EmailRequest](request).flatMap(r => ok(services.players.updateEmail(caller, r.email)))

            // What the caller wants to be told about, and the two levels of it that are theirs alone:
            // everywhere, and in one game. The per-match level is on the match's own route below,
            // because that is where it is set from and what it is about.
            case ("GET", "me" :: "notifications" :: Nil) =>
                ok(services.notifications.mine(caller))

            /* Try my address again: clears the suppression a bounce left behind, so the next
             * notification is sent rather than held back.
             *
             * POST rather than DELETE on a suppression resource, because the player is not deleting
             * a record -- the row survives, released, so that a second failure reads as a second
             * failure. What they are asking for is an attempt.
             *
             * Refused for a complaint, in the service rather than only in the browser: a one-click
             * undo of a spam report is exactly what the report exists to prevent, and a button is
             * not authority to grant one. Answers 204 either way it succeeds -- whether there was a
             * suppression to clear or not -- because the screen re-fetches its settings afterwards
             * and that answer is the one worth having.
             */
            case ("POST", "me" :: "notifications" :: "retry" :: Nil) =>
                noContent(services.suppression.retryMine(caller).void)

            case ("PUT", "me" :: "notifications" :: Nil) =>
                body[Json.PreferencesRequest](request).flatMap(r =>
                    noContent(
                      services.notifications.updateMine(
                        callerExternalId = caller,
                        preferences = r.preferences,
                        applyToGames = r.isAppliedToGames,
                        applyToMatches = r.isAppliedToMatches
                      )
                    )
                )

            // Same body as the route above, and `isAppliedToGames` refused rather than ignored: there is
            // no level between one game and another for it to mean anything about, so a client that
            // sends it here has asked for something this route cannot do -- and the likely way to end
            // up doing that is to reuse the body of its sibling above, which is exactly the mistake a
            // silent 204 would hide. Refused before the service is reached, so nothing is written.
            case ("PUT", "me" :: "notifications" :: "games" :: gameId :: Nil) =>
                withGameId(gameId) { id =>
                    body[Json.PreferencesRequest](request).flatMap { r =>
                        if (r.isAppliedToGames)
                            IO.pure(Errors.badRequest(ErrorText.request.applyToGamesHere))
                        else
                            noContent(
                              services.notifications.updateForGame(
                                callerExternalId = caller,
                                gameId = id,
                                preferences = r.preferences,
                                applyToMatches = r.isAppliedToMatches
                              )
                            )
                    }
                }

            case ("GET", "me" :: "matches" :: Nil) =>
                ok(services.matches.active(caller))

            case ("GET", "me" :: "matches" :: "due" :: Nil) =>
                ok(services.matches.due(caller))

            /* Completed lists are a window of time at a time -- `CompletedQuery`, read from
             * `?frame=day|week|month|year&page=<n>&asOf=<instant>&gameId=<id>`, every one optional. */
            case ("GET", "me" :: "matches" :: "completed" :: Nil) =>
                withCompletedQuery(request)(query => ok(services.matches.completed(caller, query)))

            /* Finding somebody, and then looking at them. Three routes and no body between them:
             * the prefix is a query parameter because this is a GET that reads, and the player's
             * page is two lists rather than one because the caller's own matches are two lists --
             * the same split, so the same rows mean the same thing on either screen.
             *
             * What comes back is `PublicPlayer` and public matches only. There is no route here
             * that answers with a `Player`: an address and a Cognito id are not things a stranger
             * asks for, so no stranger's route returns the shape that carries them. */
            case ("GET", "players" :: Nil) =>
                ok(services.players.search(caller, request.query.getOrElse("prefix", "")))

            case ("GET", "players" :: playerId :: "matches" :: Nil) =>
                withPlayerId(playerId)(id => ok(services.matches.publicActive(caller, id)))

            case ("GET", "players" :: playerId :: "matches" :: "completed" :: Nil) =>
                withPlayerId(playerId)(id =>
                    withCompletedQuery(request)(query =>
                        ok(services.matches.publicCompleted(callerExternalId = caller, playerId = id, query = query))
                    )
                )

            case ("GET", "games" :: Nil) =>
                ok(services.games.list(caller, activeOnly = request.query.get("activeOnly").contains("true")))

            case ("POST", "games" :: Nil) =>
                // The engine's API key travels in the same object as the game, and is read apart from
                // it so that the game itself never holds one.
                (body[Game](request), body[Json.GameApiKeyField](request)).tupled.flatMap((game, key) =>
                    ok(services.games.createOrUpdate(externalUserId = caller, game = game, apiKey = key.apiKey))
                )

            /* A game's admins: anybody registered may see who they are, and who made them; an
             * admin, or an admin of this game, may make a player one; an admin may take it away, and so
             * may the game's admin who made them one. A PUT rather than a POST, because making an admin
             * of somebody who already is one changes nothing. */
            case ("GET", "games" :: gameId :: "admins" :: Nil) =>
                withGameId(gameId)(id => ok(services.gameAdmins.list(id, caller)))

            case ("PUT", "games" :: gameId :: "admins" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        noContent(services.gameAdmins.grant(gameId = gid, playerId = pid, callerExternalId = caller))
                    )
                )

            case ("DELETE", "games" :: gameId :: "admins" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        noContent(services.gameAdmins.revoke(gameId = gid, playerId = pid, callerExternalId = caller))
                    )
                )

            /* Players' Elo ratings in a game: anybody registered may see its leaderboard, a page
             * at a time from `page` 0, and any one player's standing on it; an admin, or an admin of this game, may set one. A PUT, because
             * what is sent is the rating, not a change to it. `?role=` asks about one role's ratings
             * instead of the overall ones. */
            case ("GET", "games" :: gameId :: "ratings" :: Nil) =>
                withGameId(gameId)(id =>
                    withRoleQuery(request)(role =>
                        // `?prefix=` finds the game's rated players by the start of a nickname instead.
                        (request.query.get("prefix"), request.query.get("page")) match {
                            case (Some(prefix), _) =>
                                ok(
                                  services.ratings.findInRankings(
                                    gameId = id,
                                    prefix = prefix,
                                    callerExternalId = caller,
                                    role = role
                                  )
                                )
                            case (None, None) =>
                                ok(
                                  services.ratings
                                      .leaderboard(gameId = id, page = 0, callerExternalId = caller, role = role)
                                )
                            case (None, Some(raw)) =>
                                raw.toIntOption.filter(_ >= 0) match {
                                    case Some(page) =>
                                        ok(
                                          services.ratings.leaderboard(
                                            gameId = id,
                                            page = page,
                                            callerExternalId = caller,
                                            role = role
                                          )
                                        )
                                    case None =>
                                        IO.pure(Errors.badRequest(ErrorText.request.invalid(raw = raw, what = "page")))
                                }
                        }
                    )
                )

            case ("GET", "games" :: gameId :: "ratings" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        withRoleQuery(request)(role =>
                            ok(
                              services.ratings
                                  .standing(gameId = gid, playerId = pid, callerExternalId = caller, role = role)
                            )
                        )
                    )
                )

            case ("PUT", "games" :: gameId :: "ratings" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        body[Json.RatingRequest](request).flatMap(r =>
                            ok(
                              services.ratings
                                  .set(gameId = gid, playerId = pid, rating = r.rating, callerExternalId = caller)
                            )
                        )
                    )
                )

            case ("GET", "games" :: gameId :: "characters" :: Nil) =>
                withGameId(gameId)(id => ok(services.characters.listForGame(id, caller)))

            // Another player's characters in one game, by name and without their state: what the
            // challenger needs in order to invite one of them.
            case ("GET", "games" :: gameId :: "players" :: playerId :: "characters" :: Nil) =>
                withGameId(gameId) { gid =>
                    withPlayerId(playerId)(player =>
                        ok(services.characters.namesFor(gameId = gid, playerId = player, callerExternalId = caller))
                    )
                }

            // A character's page: the character as anybody may see it, and its public matches -- the
            // same two lists as a player's page, and the same rule for which matches are on them.
            case ("GET", "games" :: gameId :: "characters" :: characterId :: Nil) =>
                withGameId(gameId)(gid =>
                    withCharacterId(characterId)(id =>
                        ok(services.characters.profile(gameId = gid, characterId = id, callerExternalId = caller))
                    )
                )

            case ("GET", "games" :: gameId :: "characters" :: characterId :: "matches" :: Nil) =>
                withGameId(gameId) { gid =>
                    withCharacterId(characterId)(id =>
                        ok(services.matches.characterActive(callerExternalId = caller, gameId = gid, characterId = id))
                    )
                }

            case ("GET", "games" :: gameId :: "characters" :: characterId :: "matches" :: "completed" :: Nil) =>
                withGameId(gameId) { gid =>
                    withCharacterId(characterId)(id =>
                        withCompletedQuery(request)(query =>
                            ok(
                              services.matches.characterCompleted(
                                callerExternalId = caller,
                                gameId = gid,
                                characterId = id,
                                query = query
                              )
                            )
                        )
                    )
                }

            // A character a game engine has made, reported so it can be challenged with and seated.
            // The engine's, not a player's: characters are made in their engine, and the game they are
            // in is the one the caller's identity names. Deployed, this is one of
            // `local.engine_routes`, not `local.routes`, as is the state route below.
            case ("POST", "characters" :: Nil) =>
                body[Json.RegisterCharacterRequest](request).flatMap { r =>
                    created(
                      services.characters.create(
                        name = r.name,
                        description = r.description,
                        ownerExternalId = r.ownerExternalId,
                        state = r.state,
                        callerExternalId = caller
                      )
                    )
                }

            // One player's characters in the calling engine's game, for the engine to show them: it
            // keeps none of its own. The engine's, like the two below.
            case ("GET", "characters" :: Nil) =>
                request.query.get("owner").filter(_.nonEmpty) match {
                    case None        => IO.pure(Errors.badRequest(ErrorText.request.noOwner))
                    case Some(owner) => ok(services.characters.listForOwner(owner, caller))
                }

            // A character edited in its game engine on behalf of the player who owns it: its name and
            // description, and below, who owns it. Every edit of a character is its engine's; players
            // only read characters here. Deployed, both are among `local.engine_routes`.
            case ("PUT", "characters" :: characterId :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.EditCharacterRequest](request).flatMap(r =>
                        ok(
                          services.characters.edit(
                            characterId = id,
                            name = r.name,
                            description = r.description,
                            ownerExternalId = r.ownerExternalId,
                            callerExternalId = caller
                          )
                        )
                    )
                }

            case ("PUT", "characters" :: characterId :: "owner" :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.TransferCharacterRequest](request).flatMap(r =>
                        ok(
                          services.characters.transfer(
                            characterId = id,
                            toNickname = r.toNickname,
                            ownerExternalId = r.ownerExternalId,
                            callerExternalId = caller
                          )
                        )
                    )
                }

            // Authorized on behalf of the game rather than a player: the caller is the game engine,
            // identified by its API key (X-External-Id locally), not a player. See CharacterService's
            // class comment. Deployed, this is one of `local.engine_routes`, not `local.routes`.
            case ("PUT", "characters" :: characterId :: "state" :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.UpdateStateRequest](request).flatMap(r =>
                        ok(
                          services.characters.updateState(characterId = id, state = r.state, callerExternalId = caller)
                        )
                    )
                }

            case ("GET", "games" :: gameId :: "matches" :: matchId :: Nil) =>
                withMatch(gameId, matchId)((gid, mid) =>
                    ok(services.engine.read(gameId = gid, matchId = mid, callerExternalId = caller))
                )

            // How the caller's finished matches turned out, for one window of their completed list,
            // named as `/me/matches/completed` names it. One call for the window: it is a join over
            // five tables, and per-match would be a request per row.
            case ("GET", "me" :: "results" :: Nil) =>
                withCompletedQuery(request)(query =>
                    ok(
                      services.matches
                          .results(caller, query)
                          .map(_.map { r =>
                              Json.ParticipantResultView(
                                gameId = r.gameId,
                                matchId = r.matchId,
                                participantId = r.participantId,
                                nickname = r.nickname,
                                roleName = r.roleName,
                                rank = r.rank,
                                scores = r.scores.view.mapValues(JsonValues.fromScala).toMap,
                                isWinner = r.isWinner,
                                isForfeit = r.isForfeit,
                                timeTaken = r.timeTaken,
                                eloStart = r.eloStart,
                                eloDelta = r.eloDelta
                              )
                          })
                    )
                )

            // Re-checks a running match with the game engine, for a participant who suspects the
            // state matchmaker holds has fallen behind. Player-authorized, like any other match route.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "refresh" :: Nil) =>
                withMatch(gameId, matchId)((gid, mid) =>
                    ok(services.engine.refresh(gameId = gid, matchId = mid, callerExternalId = caller))
                )

            // Calls a match off. Only the owner of its tournament may -- a challenge's challenger.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "cancel" :: Nil) =>
                withMatch(gameId, matchId)((gid, mid) =>
                    ok(services.matches.cancel(gameId = gid, matchId = mid, callerExternalId = caller))
                )

            // A game's matches, for its admins to manage them from -- whether each is friendly, above all.
            // `?playerId=` narrows it to one player's, as an admin sees them on that player's page.
            case ("GET", "games" :: gameId :: "matches" :: Nil) =>
                withGameId(gameId)(gid =>
                    request.query.get("playerId") match {
                        case None => ok(services.matches.listForGame(gameId = gid, callerExternalId = caller))
                        case Some(raw) =>
                            raw.toLongOption match {
                                case Some(id) =>
                                    ok(
                                      services.matches.listForGame(
                                        gameId = gid,
                                        callerExternalId = caller,
                                        player = Some(PlayerId(id))
                                      )
                                    )
                                case None =>
                                    IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "player")))
                            }
                    }
                )

            // Whether the match is friendly, which a game's admin says.
            case ("PUT", "games" :: gameId :: "matches" :: matchId :: "friendly" :: Nil) =>
                withMatch(gameId, matchId) { (gid, mid) =>
                    body[Json.FriendlyRequest](request).flatMap(r =>
                        ok(
                          services.matches.setFriendly(
                            gameId = gid,
                            matchId = mid,
                            friendly = r.isFriendly,
                            callerExternalId = caller
                          )
                        )
                    )
                }

            // Muting one match, which is the most specific thing a player can say about notifications
            // and the only one that is about a single thing they are playing. Player-authorized like
            // every other match route; the service refuses a caller with no seat in it.
            case ("GET", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withMatch(gameId, matchId)((gid, mid) =>
                    ok(services.notifications.forMatch(callerExternalId = caller, gameId = gid, matchId = mid))
                )

            case ("PUT", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withMatch(gameId, matchId) { (gid, mid) =>
                    // `SeatNotifications`, not preferences: a seat answers every kind it can be asked
                    // about, so there is nothing here a caller may leave unsaid and nothing below it to
                    // fall through to. Four kinds -- the others, about a challenge are not a
                    // seat's to answer.
                    body[SeatNotifications](request).flatMap(p =>
                        noContent(
                          services.notifications
                              .updateForMatch(callerExternalId = caller, gameId = gid, matchId = mid, preferences = p)
                        )
                    )
                }

            // The game engine's two callbacks. Authorized on behalf of the game rather than a player:
            // X-External-Id carries the game's shared secret, as on the character-state route above.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "moves" :: Nil) =>
                withMatch(gameId, matchId) { (gid, mid) =>
                    body[Json.MoveNotification](request).flatMap { r =>
                        noContent(
                          services.engine.recordMove(
                            gameId = gid,
                            matchId = mid,
                            moved = r.participantId,
                            next = r.next,
                            takenAt = r.takenAt,
                            startedAt = r.startedAt,
                            callerExternalId = caller,
                            state = r.state.map(st =>
                                MoveState(st.sequence, st.pending.map(p => SeatClock(p.participantId, p.since)))
                            )
                          )
                        )
                    }
                }

            case ("POST", "games" :: gameId :: "matches" :: matchId :: "results" :: Nil) =>
                withMatch(gameId, matchId) { (gid, mid) =>
                    body[Json.MatchResults](request).flatMap { r =>
                        val results = r.results.map(entry =>
                            ReportedResult(
                              participantId = entry.participantId,
                              rank = entry.rank,
                              scores = entry.scores.view.mapValues(JsonValues.toScala).toMap,
                              isWinner = entry.isWinner,
                              isForfeit = entry.isForfeit
                            )
                        )
                        val turns = r.turns.map(
                          _.map(t =>
                              com.vivi.matchmaker.engine.EngineTurn(
                                participantId = t.participantId.value,
                                takenAt = t.takenAt,
                                startedAt = t.startedAt
                              )
                          )
                        )
                        noContent(
                          services.engine.recordResults(
                            gameId = gid,
                            matchId = mid,
                            results = results,
                            callerExternalId = caller,
                            turns = turns,
                            summary = r.summary
                          )
                        )
                    }
                }

            // ---- tournaments ------------------------------------------------------------------------

            // The caller's own: owned, entered or invited to, in every game.
            case ("GET", "me" :: "tournaments" :: Nil) =>
                ok(services.tournaments.mine(caller))

            // The tournaments the caller owns that ended within the last `frame` (a day if not given).
            case ("GET", "me" :: "tournaments" :: "ended" :: Nil) =>
                request.query.get("frame") match {
                    case None => ok(services.tournaments.endedOwned(caller, CompletedFrame.Day))
                    case Some(raw) =>
                        CompletedFrame
                            .fromCode(raw)
                            .fold(IO.pure(Errors.badRequest(ErrorText.request.invalid(raw = raw, what = "frame"))))(
                              frame => ok(services.tournaments.endedOwned(caller, frame))
                            )
                }

            // Where the caller stands in every tournament they have entered.
            case ("GET", "me" :: "tournament-standings" :: Nil) =>
                ok(services.tournaments.standings(caller))

            case ("GET", "games" :: gameId :: "tournaments" :: Nil) =>
                withGameId(gameId)(gid => ok(services.tournaments.listForGame(gid, caller)))

            // A new tournament, open for entries, made from one of the game's models; its creator's entry, if asked.
            case ("POST", "games" :: gameId :: "tournaments" :: Nil) =>
                withGameId(gameId)(gid =>
                    body[TournamentRequest](request).flatMap(r =>
                        created(services.tournaments.create(gameId = gid, request = r, callerExternalId = caller))
                    )
                )

            case ("GET", "games" :: gameId :: "tournaments" :: tid :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    ok(services.tournaments.detail(gameId = gid, tournamentId = t, callerExternalId = caller))
                )

            // The models tournaments are made from: every game's, as the code defines them, synced into the database
            // by a site admin; each game offers those its admins choose.
            case ("GET", "tournament-models" :: Nil) =>
                ok(services.tournamentModels.listAll(caller))

            case ("POST", "tournament-models" :: "sync" :: Nil) =>
                ok(services.tournamentModels.sync(caller))

            case ("GET", "games" :: gameId :: "tournament-models" :: Nil) =>
                withGameId(gameId)(gid => ok(services.tournamentModels.list(gid, caller)))

            case ("PUT", "games" :: gameId :: "tournament-models" :: mid :: Nil) =>
                withTournamentModel(gameId, mid)((gid, m) =>
                    ok(services.tournamentModels.offer(gameId = gid, id = m, callerExternalId = caller))
                )

            case ("DELETE", "games" :: gameId :: "tournament-models" :: mid :: Nil) =>
                withTournamentModel(gameId, mid)((gid, m) =>
                    ok(services.tournamentModels.withdraw(gameId = gid, id = m, callerExternalId = caller))
                )

            case ("PUT", "games" :: gameId :: "tournaments" :: tid :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    body[TournamentRequest](request).flatMap(r =>
                        ok(
                          services.tournaments
                              .update(gameId = gid, tournamentId = t, request = r, callerExternalId = caller)
                        )
                    )
                )

            // The owner's steps through a tournament's life: start, call off.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "start" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    ok(services.tournaments.start(gameId = gid, tournamentId = t, callerExternalId = caller))
                )

            // Scheduling no more of its matches: one not yet started is deleted, and one under way finishes
            // with the round being played.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "stop" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    noContent(services.rounds.stop(gameId = gid, tournamentId = t, callerExternalId = caller))
                )

            // Inviting a player, and taking it back -- the owner's revoke or the player's decline.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "invitations" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    body[TournamentJson.TournamentInvite](request).flatMap(r =>
                        noContent(
                          services.tournaments
                              .invite(gameId = gid, tournamentId = t, playerId = r.playerId, callerExternalId = caller)
                        )
                    )
                )

            case ("DELETE", "games" :: gameId :: "tournaments" :: tid :: "invitations" :: playerId :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    withPlayerId(playerId)(p =>
                        noContent(
                          services.tournaments
                              .revoke(gameId = gid, tournamentId = t, playerId = p, callerExternalId = caller)
                        )
                    )
                )

            // A character game's invitations, which name characters: inviting one is the owner's, and removing one
            // is the owner's or the character's owner's, who is declining it.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "character-invitations" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    body[TournamentJson.TournamentCharacterInvite](request).flatMap(r =>
                        noContent(
                          services.tournaments.inviteCharacter(
                            gameId = gid,
                            tournamentId = t,
                            characterId = r.characterId,
                            callerExternalId = caller
                          )
                        )
                    )
                )

            case (
                  "DELETE",
                  "games" :: gameId :: "tournaments" :: tid :: "character-invitations" :: characterId :: Nil
                ) =>
                withTournament(gameId, tid)((gid, t) =>
                    withCharacterId(characterId)(c =>
                        noContent(
                          services.tournaments.revokeCharacter(
                            gameId = gid,
                            tournamentId = t,
                            characterId = c,
                            callerExternalId = caller
                          )
                        )
                    )
                )

            // Entering, with a character in a character game, and withdrawing an entry.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "entries" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    body[TournamentJson.EnterRequest](request).flatMap(r =>
                        created(
                          services.tournaments.enter(
                            gameId = gid,
                            tournamentId = t,
                            characterId = r.characterId,
                            callerExternalId = caller
                          )
                        )
                    )
                )

            case ("DELETE", "games" :: gameId :: "tournaments" :: tid :: "entries" :: entrantId :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    entrantId.toLongOption.fold(IO.pure(Errors.badRequest(ErrorText.request.notAnEntryId(entrantId))))(
                      e =>
                          noContent(
                            services.tournaments.withdraw(
                              gameId = gid,
                              tournamentId = t,
                              entrantId = EntrantId(e),
                              callerExternalId = caller
                            )
                          )
                    )
                )

            // Playing a tournament's rounds: the owner beginning the next, checking the one being played
            // for clocks run out, and stopping a stage that repeats until stopped; a seat choosing its
            // role; the owner deciding a match by hand; and the caller's seats waiting on a choice.
            // The owner may give this round a duration of its own; with no body, it lasts the tournament's.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "rounds" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    (if (Option(request.body).forall(_.trim.isEmpty)) IO.pure(TournamentJson.StartRoundRequest())
                     else body[TournamentJson.StartRoundRequest](request)).flatMap(r =>
                        ok(
                          services.rounds
                              .startRound(
                                gameId = gid,
                                tournamentId = t,
                                callerExternalId = caller,
                                duration = r.roundDurationSeconds.map(java.time.Duration.ofSeconds)
                              )
                              .map(TournamentJson.RoundStarted(_))
                        )
                    )
                )

            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "check" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    noContent(services.rounds.checkRound(gameId = gid, tournamentId = t, callerExternalId = caller))
                )

            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "stop-repeating" :: Nil) =>
                withTournament(gameId, tid)((gid, t) =>
                    noContent(services.rounds.stopRepeating(gameId = gid, tournamentId = t, callerExternalId = caller))
                )

            // A tournament match is a match, addressed by its id.
            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "matches" :: matchId :: "role" :: Nil) =>
                withTournamentMatch(rawGame = gameId, rawTournament = tid, rawMatch = matchId)((gid, t, mid) =>
                    body[TournamentJson.ChooseRoleRequest](request).flatMap(r =>
                        ok(
                          services.rounds.chooseRole(
                            gameId = gid,
                            tournamentId = t,
                            matchId = mid,
                            role = r.gameRoleId,
                            callerExternalId = caller
                          )
                        )
                    )
                )

            case ("POST", "games" :: gameId :: "tournaments" :: tid :: "matches" :: matchId :: "result" :: Nil) =>
                withTournamentMatch(rawGame = gameId, rawTournament = tid, rawMatch = matchId)((gid, t, mid) =>
                    body[TournamentJson.DecideRequest](request).flatMap(r =>
                        noContent(
                          services.rounds
                              .decide(
                                gameId = gid,
                                tournamentId = t,
                                matchId = mid,
                                ranks = r.ranks.map(x => x.entrantId -> x.rank).toMap,
                                callerExternalId = caller
                              )
                        )
                    )
                )

            case ("GET", "me" :: "tournament-choices" :: Nil) =>
                ok(services.rounds.choicesFor(caller))

            // A completed match's archive, the engine's to move and matchmaker's to keep track of
            // (archiving-matches-plan.md). Authorized as the game, like the callbacks above, but addressed
            // by match id alone: an engine whose live copy is gone no longer knows the game id.
            case ("POST", "matches" :: matchId :: "archive" :: Nil) =>
                withMatchId(matchId)(mid =>
                    body[Json.ArchiveUploadRequest](request).flatMap { r =>
                        ok(
                          services.archives
                              .requestUpload(
                                matchId = mid,
                                size = r.size,
                                sha256 = r.sha256,
                                formatVersion = r.formatVersion,
                                callerExternalId = caller
                              )
                              .map {
                                  case UploadAnswer.Upload(signed) =>
                                      Json.ArchiveUploadAnswer(upload =
                                          Some(
                                            Json.ArchiveUpload(
                                              url = signed.url,
                                              method = signed.method,
                                              headers = signed.headers,
                                              expiresAt = signed.expiresAt
                                            )
                                          )
                                      )
                                  case UploadAnswer.AlreadyArchived(at) =>
                                      Json.ArchiveUploadAnswer(archivedAt = Some(at))
                              }
                        )
                    }
                )

            case ("POST", "matches" :: matchId :: "archive" :: "confirm" :: Nil) =>
                withMatchId(matchId)(mid => ok(services.archives.confirm(mid, caller).map(Json.ArchiveConfirmation(_))))

            // A POST, not a GET: finding a friendly archive gone is recorded.
            case ("POST", "matches" :: matchId :: "archive" :: "read" :: Nil) =>
                withMatchId(matchId)(mid =>
                    ok(
                      services.archives
                          .download(mid, caller)
                          .map(signed => Json.ArchiveDownload(signed.url, signed.expiresAt))
                    )
                )

            case ("POST", "matches" :: matchId :: "archive" :: "expired" :: Nil) =>
                withMatchId(matchId)(mid => noContent(services.archives.reportExpired(mid, caller)))

            case _ => IO.pure(Errors.notFound)
        }

    private def ok[A: ReadWriter](result: IO[A]): IO[Response] = respond(200, result)

    private def created[A: ReadWriter](result: IO[A]): IO[Response] = respond(201, result)

    // Failures are not handled per-route: `dispatch` maps every one of them, so that a body that
    // fails to parse before the service is ever called is mapped the same way as a service error.
    private def respond[A: ReadWriter](status: Int, result: IO[A]): IO[Response] =
        result.map(value => Response(status, write(value)))

    private def noContent(result: IO[Unit]): IO[Response] =
        result.as(Response(204, ""))

    private def body[A: ReadWriter](request: Request): IO[A] =
        IO(read[A](request.body)).handleErrorWith(e =>
            IO.raiseError(ValidationError(ErrorText.request.malformedBody(e.getMessage)))
        )

    private def withGameId(raw: String)(f: GameId => IO[Response]): IO[Response] =
        raw.toIntOption.fold(IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "game"))))(id =>
            f(GameId(id))
        )

    private def withMatchId(raw: String)(f: MatchId => IO[Response]): IO[Response] =
        MatchId.parse(raw).fold(IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "match"))))(f)

    private def withMatch(rawGame: String, rawMatch: String)(f: (GameId, MatchId) => IO[Response]): IO[Response] =
        withGameId(rawGame)(gid => withMatchId(rawMatch)(mid => f(gid, mid)))

    private def withTournamentMatch(rawGame: String, rawTournament: String, rawMatch: String)(
        f: (GameId, TournamentId, MatchId) => IO[Response]
    ): IO[Response] =
        withTournament(rawGame, rawTournament)((gid, t) => withMatchId(rawMatch)(mid => f(gid, t, mid)))

    private def withCharacterId(raw: String)(f: CharacterId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "character"))))(
          id => f(CharacterId(id))
        )

    /* The window a completed list is asked for: every parameter optional, each one that is given
     * well formed, and the window they name one that can be computed and stored -- or the request is
     * refused rather than answered with a window nobody asked for. */
    private def withCompletedQuery(request: Request)(f: CompletedQuery => IO[Response]): IO[Response] = {
        def param[A](name: String)(parse: String => Option[A]): Either[String, Option[A]] =
            request.query.get(name) match {
                case None      => Right(None)
                case Some(raw) => parse(raw).map(Some(_)).toRight(ErrorText.request.invalid(raw = raw, what = name))
            }
        val query = for {
            frame <- param("frame")(CompletedFrame.fromCode)
            page <- param("page")(_.toIntOption.filter(n => n >= 0 && n <= MaxCompletedPage))
            asOf <- param("asOf")(raw => scala.util.Try(java.time.Instant.parse(raw)).toOption)
            game <- param("gameId")(_.toIntOption.map(GameId.apply))
            window = CompletedQuery(
              frame = frame.getOrElse(CompletedFrame.Day),
              page = page.getOrElse(0),
              asOf = asOf,
              gameId = game
            )
            // Each part well formed is not enough: `asOf` far in the past or the future, or a page
            // far enough back, names an instant that cannot be computed or stored, and that is the
            // request's mistake to be told about rather than a 500.
            inRange <- Either.cond(
              window.isInRange(java.time.Instant.now()),
              window,
              ErrorText.request.windowOutOfRange
            )
        } yield inRange
        query.fold(message => IO.pure(Errors.badRequest(message)), f)
    }

    private val MaxCompletedPage = 10000

    /* `?role=`, when there is one: which role's ratings are asked about, rather than the overall ones. */
    private def withRoleQuery(request: Request)(f: Option[GameRoleId] => IO[Response]): IO[Response] =
        request.query.get("role") match {
            case None => f(None)
            case Some(raw) =>
                raw.toIntOption.fold(IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "role"))))(
                  id => f(Some(GameRoleId(id)))
                )
        }

    private def withTournament(rawGame: String, rawTournament: String)(
        f: (GameId, TournamentId) => IO[Response]
    ): IO[Response] =
        withGameId(rawGame)(gid =>
            rawTournament.toLongOption.fold(
              IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = rawTournament, what = "tournament")))
            )(t => f(gid, TournamentId(t)))
        )

    private def withTournamentModel(rawGame: String, rawModel: String)(
        f: (GameId, TournamentModelId) => IO[Response]
    ): IO[Response] =
        withGameId(rawGame)(gid =>
            if (rawModel.isEmpty) IO.pure(Errors.badRequest(ErrorText.request.noModel))
            else f(gid, TournamentModelId(rawModel))
        )

    private def withPlayerId(raw: String)(f: PlayerId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(ErrorText.request.notAnId(raw = raw, what = "player"))))(id =>
            f(PlayerId(id))
        )
}
