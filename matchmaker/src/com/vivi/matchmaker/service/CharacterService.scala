package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, PlayerRepo, TextCodec}

/** Creates and updates characters. `create` and `update` are authorized by
  * `callerExternalId`, which identifies the player making the request: for `create` it must
  * match `externalId` (the player the character is being created for), and for `update` it
  * must match the externalId of the character's current owner, i.e. before the update is
  * applied. `updateState` is instead authorized on behalf of the game itself: its
  * `callerExternalId` must match the externalId of the game the character belongs to.
  */
class CharacterService[T](config: DbConfig)(using codec: TextCodec[T]) {

  def create(
      gameId: GameId,
      name: String,
      description: String,
      externalId: String,
      callerExternalId: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        _ <- IO.raiseUnless(callerExternalId == externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not create a character for '$externalId'")
        )
        _ <- gameRepo.read(gameId).flatMap {
          case Some(g) => IO.pure(g)
          case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        character <- characterRepo.create(
          Character(CharacterId(0), gameId, name, description, codec.decode(""), Some(player.playerId))
        )
      } yield character
    }

  def update(
      characterId: CharacterId,
      name: String,
      description: String,
      externalId: String,
      callerExternalId: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        joined <- characterRepo.readWithOwnerAndGame(characterId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
        }
        (existing, currentOwner, _) = joined
        _ <- IO.raiseUnless(callerExternalId == currentOwner.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not update character ${characterId.value}")
        )
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        updated = existing.copy(name = name, description = description, playerId = Some(player.playerId))
        _ <- characterRepo.update(updated)
      } yield updated
    }

  def updateState(
      characterId: CharacterId,
      state: T,
      callerExternalId: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val characterRepo = new CharacterRepo[T](session)
      for {
        joined <- characterRepo.readWithGame(characterId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
        }
        (existing, game) = joined
        _ <- IO.raiseUnless(callerExternalId == game.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not update the state of character ${characterId.value}")
        )
        updated = existing.copy(state = state)
        _ <- characterRepo.update(updated)
      } yield updated
    }
}
