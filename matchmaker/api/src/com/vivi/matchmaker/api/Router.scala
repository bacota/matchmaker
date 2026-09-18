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
    def dispatch(services: => Services[String], request: Request, authenticator: Authenticator): IO[Response] =
        authenticator.callerOf(request) match {
            case Left(rejection) => IO.pure(rejection)
            case Right(caller) =>
                val where = s"${request.method} ${request.path}"
                // `route` is called inside the IO rather than before it: it forces the by-name
                // `services`, and a pool that fails to open would otherwise throw while this IO is being
                // built — outside the `handleError` below, and so unlogged.
                IO(route(services, request, caller)).flatten.handleError(Errors.toResponse(_, where))
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

            case ("GET", "me" :: "matches" :: "completed" :: Nil) =>
                ok(services.matches.completed(caller))

            case ("GET", "games" :: Nil) =>
                ok(services.games.list(caller, activeOnly = request.query.get("activeOnly").contains("true")))

            case ("POST", "games" :: Nil) =>
                body[Game](request).flatMap(game => ok(services.games.createOrUpdate(caller, game)))

            case ("GET", "games" :: gameId :: "challenges" :: Nil) =>
                withGameId(gameId)(id => ok(services.challenges.listByGame(id, caller)))

            case ("GET", "games" :: gameId :: "characters" :: Nil) =>
                withGameId(gameId)(id => ok(services.characters.listForGame(id, caller)))

            case ("POST", "games" :: gameId :: "characters" :: Nil) =>
                withGameId(gameId) { id =>
                    body[Json.CharacterRequest](request).flatMap { r =>
                        created(services.characters.create(id, r.name, r.description, r.externalId, caller))
                    }
                }

            case ("PUT", "characters" :: characterId :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.CharacterRequest](request).flatMap { r =>
                        ok(services.characters.update(id, r.name, r.description, r.externalId, caller))
                    }
                }

            // Authorized on behalf of the game rather than a player: here X-External-Id carries the
            // game's shared secret, not a player's id. See CharacterService's class comment.
            case ("PUT", "characters" :: characterId :: "state" :: Nil) =>
                withCharacterId(characterId) { id =>
                    body[Json.UpdateStateRequest](request).flatMap(r =>
                        ok(services.characters.updateState(id, r.state, caller))
                    )
                }

            case ("POST", "challenges" :: Nil) =>
                body[OpenChallenge](request).flatMap(c => created(services.challenges.create(c, caller)))

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
                            r.timeTaken
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

            // Muting one match, which is the most specific thing a player can say about notifications
            // and the only one that is about a single thing they are playing. Player-authorized like
            // every other match route; the service refuses a caller with no seat in it.
            case ("GET", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withGameId(gameId)(gid => ok(services.notifications.forMatch(caller, gid, MatchId(matchId))))

            case ("PUT", "games" :: gameId :: "matches" :: matchId :: "notifications" :: Nil) =>
                withGameId(gameId) { gid =>
                    // `NotificationDefaults`, not preferences: a seat answers every kind, so there is
                    // nothing here a caller may leave unsaid and nothing below it to fall through to.
                    body[NotificationDefaults](request).flatMap(p =>
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
                            caller
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
                              entry.isWinner
                            )
                        )
                        noContent(services.engine.recordResults(gid, MatchId(matchId), results, caller))
                    }
                }

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

    private def withPlayerId(raw: String)(f: PlayerId => IO[Response]): IO[Response] =
        raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a player id")))(id => f(PlayerId(id)))
}
