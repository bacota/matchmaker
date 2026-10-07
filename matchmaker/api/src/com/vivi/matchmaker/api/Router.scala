package com.vivi.matchmaker.api

import cats.effect.IO
import cats.syntax.all._
import upickle.default.{ReadWriter, read, write}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.service._
import com.vivi.matchmaker.util.JsonValues
import ApiGateway.{Request, Response}
import Json.given

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
                    IO(route(services, request, caller)).flatten
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
                    created(services.registration.register(r.nickname, caller, r.email))
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
                      services.notifications.updateMine(caller, r.preferences, r.applyToGames, r.applyToMatches)
                    )
                )

            // Same body as the route above, and `applyToGames` refused rather than ignored: there is
            // no level between one game and another for it to mean anything about, so a client that
            // sends it here has asked for something this route cannot do -- and the likely way to end
            // up doing that is to reuse the body of its sibling above, which is exactly the mistake a
            // silent 204 would hide. Refused before the service is reached, so nothing is written.
            case ("PUT", "me" :: "notifications" :: "games" :: gameId :: Nil) =>
                withGameId(gameId) { id =>
                    body[Json.PreferencesRequest](request).flatMap { r =>
                        if (r.applyToGames)
                            IO.pure(
                              Errors.badRequest(
                                "applyToGames is not supported for one game's settings; it belongs to PUT /me/notifications"
                              )
                            )
                        else
                            noContent(services.notifications.updateForGame(caller, id, r.preferences, r.applyToMatches))
                    }
                }

            case ("GET", "me" :: "acceptances" :: Nil) =>
                ok(services.acceptances.mine(caller))

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
                    withCompletedQuery(request)(query => ok(services.matches.publicCompleted(caller, id, query)))
                )

            case ("GET", "games" :: Nil) =>
                ok(services.games.list(caller, activeOnly = request.query.get("activeOnly").contains("true")))

            case ("POST", "games" :: Nil) =>
                // The engine's API key travels in the same object as the game, and is read apart from
                // it so that the game itself never holds one.
                (body[Game](request), body[Json.GameApiKeyField](request)).tupled.flatMap((game, key) =>
                    ok(services.games.createOrUpdate(caller, game, key.apiKey))
                )

            /* A game's admins (V35): anybody registered may see who they are, and who made them; an
             * admin, or an admin of this game, may make a player one; an admin may take it away, and so
             * may the game's admin who made them one. A PUT rather than a POST, because making an admin
             * of somebody who already is one changes nothing. */
            case ("GET", "games" :: gameId :: "admins" :: Nil) =>
                withGameId(gameId)(id => ok(services.gameAdmins.list(id, caller)))

            case ("PUT", "games" :: gameId :: "admins" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid => noContent(services.gameAdmins.grant(gid, pid, caller)))
                )

            case ("DELETE", "games" :: gameId :: "admins" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid => noContent(services.gameAdmins.revoke(gid, pid, caller)))
                )

            /* Players' Elo ratings in a game (V42): anybody registered may see its leaderboard (V45), a page
             * at a time from `page` 0, and any one player's standing on it; an admin, or an admin of this game, may set one. A PUT, because
             * what is sent is the rating, not a change to it. `?role=` asks about one role's ratings (V49)
             * instead of the overall ones. */
            case ("GET", "games" :: gameId :: "ratings" :: Nil) =>
                withGameId(gameId)(id =>
                    withRoleQuery(request)(role =>
                        // `?prefix=` finds the game's rated players by the start of a nickname instead.
                        (request.query.get("prefix"), request.query.get("page")) match {
                            case (Some(prefix), _) => ok(services.ratings.findInRankings(id, prefix, caller, role))
                            case (None, None)      => ok(services.ratings.leaderboard(id, 0, caller, role))
                            case (None, Some(raw)) =>
                                raw.toIntOption.filter(_ >= 0) match {
                                    case Some(page) => ok(services.ratings.leaderboard(id, page, caller, role))
                                    case None       => IO.pure(Errors.badRequest(s"'$raw' is not a valid page"))
                                }
                        }
                    )
                )

            case ("GET", "games" :: gameId :: "ratings" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        withRoleQuery(request)(role => ok(services.ratings.standing(gid, pid, caller, role)))
                    )
                )

            case ("PUT", "games" :: gameId :: "ratings" :: playerId :: Nil) =>
                withGameId(gameId)(gid =>
                    withPlayerId(playerId)(pid =>
                        body[Json.RatingRequest](request).flatMap(r =>
                            ok(services.ratings.set(gid, pid, r.rating, caller))
                        )
                    )
                )

            case ("GET", "games" :: gameId :: "challenges" :: Nil) =>
                withGameId(gameId)(id => ok(services.challenges.listByGame(id, caller)))

            case ("GET", "games" :: gameId :: "characters" :: Nil) =>
                withGameId(gameId)(id => ok(services.characters.listForGame(id, caller)))

            // Another player's characters in one game, by name and without their state: what the
            // challenger needs in order to invite one of them (V25).
            case ("GET", "games" :: gameId :: "players" :: playerId :: "characters" :: Nil) =>
                withGameId(gameId) { gid =>
                    withPlayerId(playerId)(player => ok(services.characters.namesFor(gid, player, caller)))
                }

            // A character's page: the character as anybody may see it, and its public matches -- the
            // same two lists as a player's page, and the same rule for which matches are on them.
            case ("GET", "games" :: gameId :: "characters" :: characterId :: Nil) =>
                withGameId(gameId)(gid =>
                    withCharacterId(characterId)(id => ok(services.characters.profile(gid, id, caller)))
                )

            case ("GET", "games" :: gameId :: "characters" :: characterId :: "matches" :: Nil) =>
                withGameId(gameId) { gid =>
                    withCharacterId(characterId)(id => ok(services.matches.characterActive(caller, gid, id)))
                }

            case ("GET", "games" :: gameId :: "characters" :: characterId :: "matches" :: "completed" :: Nil) =>
                withGameId(gameId) { gid =>
                    withCharacterId(characterId)(id =>
                        withCompletedQuery(request)(query =>
                            ok(services.matches.characterCompleted(caller, gid, id, query))
                        )
                    )
                }

            // A character a game engine has made, reported so it can be challenged with and seated.
            // The engine's, not a player's: characters are made in their engine, and the game they are
            // in is the one the caller's identity names. Deployed, this is one of
            // `local.engine_routes`, not `local.routes`, as is the state route below.
            case ("POST", "characters" :: Nil) =>
                body[Json.RegisterCharacterRequest](request).flatMap { r =>
                    created(services.characters.create(r.name, r.description, r.ownerExternalId, r.state, caller))
                }

            // One player's characters in the calling engine's game, for the engine to show them: it
            // keeps none of its own. The engine's, like the two below.
            case ("GET", "characters" :: Nil) =>
                request.query.get("owner").filter(_.nonEmpty) match {
                    case None        => IO.pure(Errors.badRequest("say whose characters with ?owner="))
                    case Some(owner) => ok(services.characters.listForOwner(owner, caller))
                }

            // A character edited in its game engine on behalf of the player who owns it: its name and
            // description, and below, who owns it. Every edit of a character is its engine's; players
            // only read characters here. Deployed, both are among `local.engine_routes`.
            case ("PUT", "characters" :: characterId :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.EditCharacterRequest](request).flatMap(r =>
                        ok(services.characters.edit(id, r.name, r.description, r.ownerExternalId, caller))
                    )
                }

            case ("PUT", "characters" :: characterId :: "owner" :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.TransferCharacterRequest](request).flatMap(r =>
                        ok(services.characters.transfer(id, r.toNickname, r.ownerExternalId, caller))
                    )
                }

            // Authorized on behalf of the game rather than a player: the caller is the game engine,
            // identified by its API key (X-External-Id locally), not a player. See CharacterService's
            // class comment. Deployed, this is one of `local.engine_routes`, not `local.routes`.
            case ("PUT", "characters" :: characterId :: "state" :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.UpdateStateRequest](request).flatMap(r =>
                        ok(services.characters.updateState(id, r.state, caller))
                    )
                }

            /* Making a challenge, and asking particular players to it in the same call.
             *
             * One call rather than a create followed by a POST per invitation: a closed challenge
             * with nobody invited yet is a challenge nobody can accept, and the service validates
             * the whole set against each other — two invitations cannot hold one role — which it
             * can only do if it is given them together. The body wraps the challenge for the
             * reason `Json.CreateChallenge` says.
             */
            case ("POST", "challenges" :: Nil) =>
                body[Json.CreateChallenge](request).flatMap(r =>
                    created(services.challenges.create(r.challenge, caller, r.invitations, r.characterInvitations))
                )

            // What the caller has been asked to play, across every game: the home page's question,
            // and the one invitation route that is not about a challenge the caller already holds.
            case ("GET", "me" :: "invitations" :: Nil) =>
                ok(services.challenges.invitationsFor(caller))

            /* Turning one down. Under `/me` rather than beside the revoke below, because the caller
             * is the invitation: `reject` reads the caller's own player row and deletes that row, so
             * there is no player id for a path to carry and none a caller could name instead.
             *
             * That is the difference from the acceptance routes, where one path serves the player
             * backing out and the challenger removing them: there the row is named by a player id
             * either way, and the service decides which of the two the caller is. An invitation's
             * two sides are two questions -- "I decline" and "I withdraw my offer to them" -- and
             * they differ in what they notify as well as in who may ask.
             */
            case ("DELETE", "me" :: "invitations" :: gameId :: challengeId :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId)(id => noContent(services.challenges.reject(gid, id, caller)))
                }

            // A character game's invitation is to a character (V25), and one player may own several
            // characters invited to the same challenge, so the character is in the path.
            case ("DELETE", "me" :: "character-invitations" :: gameId :: challengeId :: characterId :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { id =>
                        withCharacterId(characterId)(cid =>
                            noContent(services.challenges.rejectCharacter(gid, id, cid, caller))
                        )
                    }
                }

            /* Inviting somebody to a challenge that already exists, and taking it back. Only the
             * challenger may either — the service checks that, since the challenge is what says who
             * they are.
             *
             * The body is an `Invite` rather than a request type of its own: who, and as what, is
             * the whole of what a caller decides here, and it is the same value `POST /challenges`
             * carries. The player is in the path on the revoke because the invitation is the
             * resource being removed, which is how the acceptance routes above read too.
             */
            case ("POST", "challenges" :: gameId :: challengeId :: "invitations" :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { id =>
                        body[Invite](request).flatMap(invite =>
                            created(services.challenges.invite(gid, id, invite, caller))
                        )
                    }
                }

            case ("DELETE", "challenges" :: gameId :: challengeId :: "invitations" :: playerId :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { challenge =>
                        withPlayerId(playerId)(player =>
                            noContent(services.challenges.revoke(gid, challenge, player, caller))
                        )
                    }
                }

            // The same pair for a character game (V25), where the invitation names a character.
            case ("POST", "challenges" :: gameId :: challengeId :: "character-invitations" :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { id =>
                        body[CharacterInvite](request).flatMap(invite =>
                            created(services.challenges.inviteCharacter(gid, id, invite, caller))
                        )
                    }
                }

            case (
                  "DELETE",
                  "challenges" :: gameId :: challengeId :: "character-invitations" :: characterId :: Nil
                ) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { challenge =>
                        withCharacterId(characterId)(cid =>
                            noContent(services.challenges.revokeCharacter(gid, challenge, cid, caller))
                        )
                    }
                }

            // Turns a challenge into a match: matchmaker creates the game in the engine and records
            // the urls it returns. Only the challenger may do it — the service checks that.
            case ("POST", "challenges" :: gameId :: challengeId :: "start" :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId)(id => created(services.engine.start(gid, id, caller)))
                }

            case ("DELETE", "challenges" :: gameId :: challengeId :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId)(id => noContent(services.challenges.delete(gid, id, caller)))
                }

            case ("POST", "challenges" :: gameId :: challengeId :: "acceptances" :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { id =>
                        body[Json.AcceptRequest](request).flatMap { r =>
                            created(services.challenges.accept(gid, id, r.characterId, r.gameRoleId, caller))
                        }
                    }
                }

            case ("DELETE", "challenges" :: gameId :: challengeId :: "acceptances" :: playerId :: Nil) =>
                withGameId(gameId) { gid =>
                    withChallengeId(challengeId) { challenge =>
                        withPlayerId(playerId)(player =>
                            noContent(services.acceptances.delete(gid, challenge, player, caller))
                        )
                    }
                }

            case ("GET", "games" :: gameId :: "matches" :: matchId :: Nil) =>
                withGameId(gameId)(gid => ok(services.engine.read(gid, MatchId(matchId), caller)))

            // How the caller's finished matches turned out. One call for the whole completed list:
            // it is a join over five tables, and per-match would be a request per row.
            case ("GET", "me" :: "results" :: Nil) =>
                ok(
                  services.matches
                      .results(caller)
                      .map(_.map { r =>
                          Json.ParticipantResultView(
                            r.gameId,
                            r.matchId,
                            r.participantId,
                            r.nickname,
                            r.roleName,
                            r.rank,
                            r.scores.view.mapValues(JsonValues.fromScala).toMap,
                            r.isWinner,
                            r.forfeit,
                            r.timeTaken,
                            r.eloStart,
                            r.eloDelta
                          )
                      })
                )

            // Re-checks a running match with the game engine, for a participant who suspects the
            // state matchmaker holds has fallen behind. Player-authorized, like any other match route.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "refresh" :: Nil) =>
                withGameId(gameId)(gid => ok(services.engine.refresh(gid, MatchId(matchId), caller)))

            // Calls a match off. Only its creator may — the challenger of the challenge it was started
            // from — which is why the challenge outlives the start.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "cancel" :: Nil) =>
                withGameId(gameId)(gid => ok(services.matches.cancel(gid, MatchId(matchId), caller)))

            // A game's matches, for its admins to manage them from -- whether each is friendly, above all.
            // `?playerId=` narrows it to one player's, as an admin sees them on that player's page.
            case ("GET", "games" :: gameId :: "matches" :: Nil) =>
                withGameId(gameId)(gid =>
                    request.query.get("playerId") match {
                        case None => ok(services.matches.listForGame(gid, caller))
                        case Some(raw) =>
                            raw.toLongOption match {
                                case Some(id) => ok(services.matches.listForGame(gid, caller, Some(PlayerId(id))))
                                case None     => IO.pure(Errors.badRequest(s"'$raw' is not a player id"))
                            }
                    }
                )

            // Whether the match is friendly (V36), which a game's admin says.
            case ("PUT", "games" :: gameId :: "matches" :: matchId :: "friendly" :: Nil) =>
                withGameId(gameId) { gid =>
                    body[Json.FriendlyRequest](request).flatMap(r =>
                        ok(services.matches.setFriendly(gid, MatchId(matchId), r.friendly, caller))
                    )
                }

            // Muting one match, which is the most specific thing a player can say about notifications
            // and the only one that is about a single thing they are playing. Player-authorized like
            // every other match route; the service refuses a caller with no seat in it.
            case ("GET", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withGameId(gameId)(gid => ok(services.notifications.forMatch(caller, gid, MatchId(matchId))))

            case ("PUT", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withGameId(gameId) { gid =>
                    // `SeatNotifications`, not preferences: a seat answers every kind it can be asked
                    // about, so there is nothing here a caller may leave unsaid and nothing below it to
                    // fall through to. Four kinds since V24 -- the seven about a challenge are not a
                    // seat's to answer.
                    body[SeatNotifications](request).flatMap(p =>
                        noContent(services.notifications.updateForMatch(caller, gid, MatchId(matchId), p))
                    )
                }

            // The game engine's two callbacks. Authorized on behalf of the game rather than a player:
            // X-External-Id carries the game's shared secret, as on the character-state route above.
            case ("POST", "games" :: gameId :: "matches" :: matchId :: "moves" :: Nil) =>
                withGameId(gameId) { gid =>
                    body[Json.MoveNotification](request).flatMap { r =>
                        noContent(
                          services.engine.recordMove(
                            gid,
                            MatchId(matchId),
                            r.participantId,
                            r.next,
                            r.takenAt,
                            r.startedAt,
                            caller,
                            r.state.map(st =>
                                MoveState(
                                  st.sequence,
                                  st.pending.map(p => SeatClock(p.participantId, p.since)),
                                  st.roles.map(seat => ReportedRole(seat.participantId, seat.role))
                                )
                            )
                          )
                        )
                    }
                }

            case ("POST", "games" :: gameId :: "matches" :: matchId :: "results" :: Nil) =>
                withGameId(gameId) { gid =>
                    body[Json.MatchResults](request).flatMap { r =>
                        val results = r.results.map(entry =>
                            ReportedResult(
                              entry.participantId,
                              entry.rank,
                              entry.scores.view.mapValues(JsonValues.toScala).toMap,
                              entry.isWinner,
                              entry.forfeit,
                              entry.role
                            )
                        )
                        val turns = r.turns.map(
                          _.map(t =>
                              com.vivi.matchmaker.engine.EngineTurn(t.participantId.value, t.takenAt, t.startedAt)
                          )
                        )
                        noContent(
                          services.engine.recordResults(gid, MatchId(matchId), results, caller, turns, r.summary)
                        )
                    }
                }

            // A completed match's archive, the engine's to move and matchmaker's to keep track of
            // (archiving-matches-plan.md). Authorized as the game, like the callbacks above, but addressed
            // by match id alone: an engine whose live copy is gone no longer knows the game id.
            case ("POST", "matches" :: matchId :: "archive" :: Nil) =>
                body[Json.ArchiveUploadRequest](request).flatMap { r =>
                    ok(
                      services.archives
                          .requestUpload(MatchId(matchId), r.size, r.sha256, r.formatVersion, caller)
                          .map {
                              case UploadAnswer.Upload(signed) =>
                                  Json.ArchiveUploadAnswer(upload =
                                      Some(
                                        Json.ArchiveUpload(signed.url, signed.method, signed.headers, signed.expiresAt)
                                      )
                                  )
                              case UploadAnswer.AlreadyArchived(at) => Json.ArchiveUploadAnswer(archivedAt = Some(at))
                          }
                    )
                }

            case ("POST", "matches" :: matchId :: "archive" :: "confirm" :: Nil) =>
                ok(services.archives.confirm(MatchId(matchId), caller).map(Json.ArchiveConfirmation(_)))

            // A POST, not a GET: finding a friendly archive gone is recorded.
            case ("POST", "matches" :: matchId :: "archive" :: "read" :: Nil) =>
                ok(
                  services.archives
                      .download(MatchId(matchId), caller)
                      .map(signed => Json.ArchiveDownload(signed.url, signed.expiresAt))
                )

            case ("POST", "matches" :: matchId :: "archive" :: "expired" :: Nil) =>
                noContent(services.archives.reportExpired(MatchId(matchId), caller))

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
            IO.raiseError(ValidationError(s"malformed request body: ${e.getMessage}"))
        )

    private def withGameId(raw: String)(f: GameId => IO[Response]): IO[Response] =
        raw.toIntOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a game id")))(id => f(GameId(id)))

    private def withCharacterId(raw: String)(f: CharacterId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a character id")))(id => f(CharacterId(id)))

    private def withChallengeId(raw: String)(f: ChallengeId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a challenge id")))(id => f(ChallengeId(id)))

    /* The window a completed list is asked for: every parameter optional, each one that is given
     * well formed, and the window they name one that can be computed and stored -- or the request is
     * refused rather than answered with a window nobody asked for. */
    private def withCompletedQuery(request: Request)(f: CompletedQuery => IO[Response]): IO[Response] = {
        def param[A](name: String)(parse: String => Option[A]): Either[String, Option[A]] =
            request.query.get(name) match {
                case None      => Right(None)
                case Some(raw) => parse(raw).map(Some(_)).toRight(s"'$raw' is not a valid $name")
            }
        val query = for {
            frame <- param("frame")(CompletedFrame.fromCode)
            page <- param("page")(_.toIntOption.filter(n => n >= 0 && n <= MaxCompletedPage))
            asOf <- param("asOf")(raw => scala.util.Try(java.time.Instant.parse(raw)).toOption)
            game <- param("gameId")(_.toIntOption.map(GameId.apply))
            window = CompletedQuery(frame.getOrElse(CompletedFrame.Day), page.getOrElse(0), asOf, game)
            // Each part well formed is not enough: `asOf` far in the past or the future, or a page
            // far enough back, names an instant that cannot be computed or stored, and that is the
            // request's mistake to be told about rather than a 500.
            inRange <- Either.cond(
              window.inRange(java.time.Instant.now()),
              window,
              "that window of completed matches is out of range"
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
                raw.toIntOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a role id")))(id =>
                    f(Some(GameRoleId(id)))
                )
        }

    private def withPlayerId(raw: String)(f: PlayerId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a player id")))(id => f(PlayerId(id)))
}
