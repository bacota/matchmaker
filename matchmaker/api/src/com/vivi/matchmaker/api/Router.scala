package com.vivi.matchmaker.api

import cats.effect.IO
import upickle.default.{ReadWriter, read, write}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.service._
import ApiGateway.{Request, Response}
import Json.given

/** Maps requests onto service calls.
  *
  * Every route needs the caller's identity, so it is resolved once here rather than in each
  * branch. How it is established is the `Authenticator`'s business, not the router's: the same
  * routes serve a gateway-verified Cognito token and a trusted local header.
  */
object Router {

  def dispatch(services: Services[String], request: Request, authenticator: Authenticator): IO[Response] =
    authenticator.callerOf(request) match {
      case Left(rejection) => IO.pure(rejection)
      case Right(caller)   => route(services, request, caller).handleError(Errors.toResponse)
    }

  private def route(services: Services[String], request: Request, caller: String): IO[Response] =
    (request.method.toUpperCase, request.segments) match {

      case ("POST", "register" :: Nil) =>
        body[Json.RegisterRequest](request).flatMap(r => created(services.registration.register(r.nickname, caller)))

      case ("GET", "me" :: Nil) =>
        ok(services.players.me(caller))

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
          body[Json.UpdateStateRequest](request).flatMap(r => ok(services.characters.updateState(id, r.state, caller)))
        }

      case ("POST", "challenges" :: Nil) =>
        body[OpenChallenge](request).flatMap(c => created(services.challenges.create(c, caller)))

      case ("DELETE", "challenges" :: challengeId :: Nil) =>
        withChallengeId(challengeId)(id => noContent(services.challenges.delete(id, caller)))

      case ("POST", "challenges" :: challengeId :: "acceptances" :: Nil) =>
        withChallengeId(challengeId) { id =>
          body[Json.AcceptRequest](request).flatMap(r => created(services.challenges.accept(id, r.characterId, caller)))
        }

      case ("DELETE", "challenges" :: challengeId :: "acceptances" :: playerId :: Nil) =>
        withChallengeId(challengeId) { challenge =>
          withPlayerId(playerId)(player => noContent(services.acceptances.delete(challenge, player, caller)))
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
    IO(read[A](request.body)).handleErrorWith(e => IO.raiseError(ValidationError(s"malformed request body: ${e.getMessage}")))

  private def withGameId(raw: String)(f: GameId => IO[Response]): IO[Response] =
    raw.toIntOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a game id")))(id => f(GameId(id)))

  private def withCharacterId(raw: String)(f: CharacterId => IO[Response]): IO[Response] =
    raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a character id")))(id => f(CharacterId(id)))

  private def withChallengeId(raw: String)(f: ChallengeId => IO[Response]): IO[Response] =
    raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a challenge id")))(id => f(ChallengeId(id)))

  private def withPlayerId(raw: String)(f: PlayerId => IO[Response]): IO[Response] =
    raw.toLongOption.fold(IO.pure(Errors.badRequest(s"'$raw' is not a player id")))(id => f(PlayerId(id)))
}
