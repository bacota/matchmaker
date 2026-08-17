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
class CharacterService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

  /** The caller's own characters in one game.
    *
    * Scoped to the caller rather than taking a player id, for the same reason `create` checks
    * one: a character carries a player's state in a game, and there is no route by which one
    * player should be able to enumerate another's.
    *
    * An unknown game is not an error here — a player simply has no characters in it — but an
    * unknown caller is, because that means the token is for someone with no player at all.
    */
  def listForGame(gameId: GameId, callerExternalId: String): IO[List[Character[T]]] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        player <- playerRepo.readByExternalId(callerExternalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$callerExternalId'"))
        }
        characters <- characterRepo.listForPlayerAndGame(player.playerId, gameId)
      } yield characters
    }

  def create(
      gameId: GameId,
      name: String,
      description: String,
      externalId: String,
      callerExternalId: String
  ): IO[Character[T]] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      // The checks below decide whether the insert is allowed; running them in the same
      // transaction as the insert keeps that decision from going stale before it lands.
      session.transaction.use { _ =>
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
    }

  def update(
      characterId: CharacterId,
      name: String,
      description: String,
      externalId: String,
      callerExternalId: String
  ): IO[Character[T]] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      // Read, authorize and write as one change: the owner checked here is the owner the
      // update is applied to.
      session.transaction.use { _ =>
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
    }

  def updateState(
      characterId: CharacterId,
      state: T,
      callerExternalId: String
  ): IO[Character[T]] =
    sessionPool.use { session =>
      val characterRepo = new CharacterRepo[T](session)
      // Same as update: the game checked here is the game the write is applied under.
      session.transaction.use { _ =>
        for {
          joined <- characterRepo.readWithGame(characterId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
          }
          (existing, game) = joined
          _ <- IO.raiseUnless(callerExternalId == game.externalId)(
            UnauthorizedError(s"invalid game externalId for character ${characterId.value}")
          )
          updated = existing.copy(state = state)
          _ <- characterRepo.update(updated)
        } yield updated
      }
    }
}
