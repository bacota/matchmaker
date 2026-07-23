package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, PlayerRepo, TextCodec}

/** Creates and updates characters. Requests must be authorized by the game itself, by
  * supplying the game's `externalId` (a shared secret stored on the game row), in
  * addition to `callerExternalId`, which identifies the player making the request: for
  * `create` it must match `externalId` (the player the character is being created for),
  * and for `update` it must match the externalId of the character's current owner, i.e.
  * before the update is applied.
  */
class CharacterService[T](config: DbConfig)(using codec: TextCodec[T]) {

  def create(
      gameId: GameId,
      name: String,
      description: String,
      state: T,
      externalId: String,
      callerExternalId: String,
      gameExternalId: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        _ <- IO.raiseUnless(callerExternalId == externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not create a character for '$externalId'")
        )
        game <- gameRepo.read(gameId).flatMap {
          case Some(g: CharacterGame) => IO.pure(g)
          case Some(_)                => IO.raiseError(ValidationError(s"game ${gameId.value} is not a character game"))
          case None                   => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }
        _ <- IO.raiseUnless(gameExternalId == game.externalId)(UnauthorizedError(s"invalid game externalId"))
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        character <- characterRepo.create(
          Character(CharacterId(0), gameId, name, description, state, Some(player.playerId))
        )
      } yield character
    }

  def update(
      characterId: CharacterId,
      name: String,
      description: String,
      state: T,
      externalId: String,
      callerExternalId: String,
      gameExternalId: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        joined <- characterRepo.readWithOwnerAndGame(characterId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
        }
        (existing, currentOwner, game) = joined
        _ <- IO.raiseUnless(callerExternalId == currentOwner.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not update character ${characterId.value}")
        )
        _ <- IO.raiseUnless(gameExternalId == game.externalId)(UnauthorizedError(s"invalid game externalId"))
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        updated = existing.copy(name = name, description = description, state = state, playerId = Some(player.playerId))
        _ <- characterRepo.update(updated)
      } yield updated
    }
}
