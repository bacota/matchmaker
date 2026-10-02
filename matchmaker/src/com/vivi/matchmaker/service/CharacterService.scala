package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, PlayerRepo, TextCodec}

/** Records and updates characters.
  *
  * A character is made in its game engine, not here: the engine is what knows what a character of its game is, builds
  * one with the player, and then tells matchmaker it exists so that it can be offered in challenges and seated in
  * matches. So `create` and `updateState` are authorized on behalf of the game: their `callerExternalId` must match the
  * externalId of the game the character belongs to. `update` is still a player's, and its `callerExternalId` must match
  * the externalId of the character's current owner, i.e. before the update is applied.
  */
class CharacterService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

    /** The caller's own characters in one game.
      *
      * Scoped to the caller rather than taking a player id, for the same reason `create` checks one: a character
      * carries a player's state in a game, and there is no route by which one player should be able to enumerate
      * another's.
      *
      * An unknown game is not an error here — a player simply has no characters in it — but an unknown caller is,
      * because that means the token is for someone with no player at all.
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

    /** Another player's characters in one game, by name only (V25).
      *
      * What a challenger needs in order to invite one of them, and deliberately no more: [[listForGame]]'s reason for
      * not enumerating another player's characters is their state, which [[CharacterName]] leaves out. The caller must
      * be registered, as for every other read.
      */
    def namesFor(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[List[CharacterName]] =
        sessionPool.use { session =>
            for {
                _ <- new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$callerExternalId'"))
                }
                names <- new CharacterRepo[T](session).listNamesForPlayerAndGame(playerId, gameId)
            } yield names
        }

    /** A character a game engine has made, recorded for `ownerExternalId` with the state the engine gave it.
      *
      * The caller is the engine — its API key deployed, `X-External-Id` locally — and the game is the one whose
      * externalId that is. An engine is not told its matchmaker game id, which is only assigned once the game is
      * registered, after the engine is deployed; the identity it already presents names the game instead.
      *
      * A player cannot call this to any effect: their identity is never a game's externalId. Refused for a game that
      * does not take characters, since nothing could ever seat one there, and for an owner who has not registered with
      * matchmaker, since a character is somebody's.
      */
    def create(
        name: String,
        description: String,
        ownerExternalId: String,
        state: T,
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
                    _ <- IO.raiseWhen(name.trim.isEmpty)(ValidationError("a character needs a name"))
                    // Locked, not just read: the insert below references the game, and without the lock it
                    // could be deleted, or given to another engine, between the check and the insert.
                    gameId <- gameRepo.lockForShareByExternalId(callerExternalId).flatMap {
                        case List(id) => IO.pure(id)
                        case Nil      => IO.raiseError(UnauthorizedError("only a game may create its characters"))
                        case several =>
                            IO.raiseError(
                              ConflictError(
                                s"games ${several.map(_.value).mkString(", ")} share this engine's identity, " +
                                    "so which one the character is in cannot be told"
                              )
                            )
                    }
                    // A plain read, of the row just locked: FOR SHARE already keeps an admin's edit of the
                    // game's type from landing until this transaction ends, so what is checked here is what
                    // holds at the insert.
                    game <- gameRepo.read(gameId).flatMap {
                        case Some(g) => IO.pure(g)
                        case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
                    }
                    _ <- IO.raiseUnless(game.gameType == GameType.Character)(
                      ValidationError(s"${game.name} is not played with characters")
                    )
                    owner <- playerRepo.readByExternalIdForShare(ownerExternalId).flatMap {
                        case Some(p) => IO.pure(p)
                        case None    => IO.raiseError(NotFoundError(s"no player with externalId '$ownerExternalId'"))
                    }
                    character <- characterRepo.create(
                      Character(CharacterId(0), gameId, name.trim, description.trim, state, Some(owner.playerId))
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
                    // For update: the ownership checked below has to still hold when the write lands, and
                    // the row read here is the row overwritten at the end of this block.
                    joined <- characterRepo.readWithOwnerAndGameForUpdate(characterId).flatMap {
                        case Some(t) => IO.pure(t)
                        case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
                    }
                    existing = joined.character
                    currentOwner = joined.owner
                    _ <- IO.raiseUnless(callerExternalId == currentOwner.externalId)(
                      UnauthorizedError(s"caller '$callerExternalId' may not update character ${characterId.value}")
                    )
                    player <- playerRepo.readByExternalIdForShare(externalId).flatMap {
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
                    // Same as update: read, authorize and write the same row without releasing it.
                    joined <- characterRepo.readWithGameForUpdate(characterId).flatMap {
                        case Some(t) => IO.pure(t)
                        case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
                    }
                    existing = joined.character
                    game = joined.game
                    _ <- IO.raiseUnless(callerExternalId == game.externalId)(
                      UnauthorizedError(s"invalid game externalId for character ${characterId.value}")
                    )
                    updated = existing.copy(state = state)
                    _ <- characterRepo.update(updated)
                } yield updated
            }
        }
}
