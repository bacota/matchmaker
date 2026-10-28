package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, Repos, TextCodec}

/** Records and updates characters, on behalf of the game engines they belong to.
  *
  * A character is made and edited in its game engine, not here: the engine is what knows what a character of its game
  * is. It builds one with the player and tells matchmaker it exists, so that it can enter challenges and tournaments
  * and be seated in matches, and tells it again whenever the player changes it — its name and description, or who owns
  * it. So every write here (`create`, `edit`, `transfer`, `updateState`) and the engine's listing (`listForOwner`) is
  * authorized on behalf of the game: `callerExternalId` must be the externalId of the game the character belongs to.
  * Players only read here.
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
            val repos = Repos(session)
            for {
                player <- repos.players.readByExternalId(callerExternalId).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                }
                characters <- repos.characters[T].listForPlayerAndGame(player.playerId, gameId)
            } yield characters
        }

    /** Another player's characters in one game, by name only.
      *
      * What a player's page shows of them, and deliberately no more: [[listForGame]]'s reason for not enumerating
      * another player's characters is their state, which [[CharacterName]] leaves out. The caller must be registered,
      * as for every other read.
      */
    def namesFor(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[List[CharacterName]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                _ <- repos.players.readByExternalId(callerExternalId).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                }
                names <- repos.characters[T].listNamesForPlayerAndGame(playerId, gameId)
            } yield names
        }

    /** One character as its page shows it: name, game, description, present owner and record, never its state. Any
      * registered player may ask, as for [[namesFor]]. Not found where `characterId` is not a character of `gameId`.
      */
    def profile(gameId: GameId, characterId: CharacterId, callerExternalId: String): IO[CharacterProfile] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                _ <- repos.players.readByExternalId(callerExternalId).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                }
                found <- repos.characters[T].readProfile(gameId, characterId)
                profile <- found match {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(NotFoundError(ErrorText.character.notFound))
                }
                // Worked out from the results each time it is asked for, not kept: see `ratedRecord`.
                record <- repos.characters[T].ratedRecord(gameId, characterId)
            } yield profile.copy(record = record)
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
            val repos = Repos(session)
            // The checks below decide whether the insert is allowed; running them in the same
            // transaction as the insert keeps that decision from going stale before it lands.
            session.transaction.use { _ =>
                for {
                    _ <- IO.raiseWhen(name.trim.isEmpty)(ValidationError(ErrorText.character.blankName))
                    // Locked, not just read: the insert below references the game, and without the lock it
                    // could be deleted, or given to another engine, between the check and the insert.
                    gameId <- callersGame(repos.games[T].lockForShareByExternalId(callerExternalId))
                    // A plain read, of the row just locked: FOR SHARE already keeps an admin's edit of the
                    // game's type from landing until this transaction ends, so what is checked here is what
                    // holds at the insert.
                    game <- repos.games[T].read(gameId).flatMap {
                        case Some(g) => IO.pure(g)
                        case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
                    }
                    _ <- IO.raiseUnless(game.gameType == GameType.Character)(
                      ValidationError(ErrorText.character.notACharacterGame(game.displayName))
                    )
                    owner <- repos.players.readByExternalIdForShare(ownerExternalId).flatMap {
                        case Some(p) => IO.pure(p)
                        case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
                    }
                    character <- repos
                        .characters[T]
                        .create(
                          Character(
                            characterId = CharacterId(0),
                            gameId = gameId,
                            name = name.trim,
                            description = description.trim,
                            state = state,
                            playerId = Some(owner.playerId)
                          )
                        )
                } yield character
            }
        }

    /** One player's characters in the calling engine's game, for the engine to show them — it keeps none itself.
      *
      * A read, so the game is found without a lock. An owner matchmaker has no player for is a 404, as in [[create]]:
      * it is somebody who has never signed in to matchmaker, and has no characters anywhere.
      */
    def listForOwner(ownerExternalId: String, callerExternalId: String): IO[List[Character[T]]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                gameId <- callersGame(repos.games[T].readIdsByExternalId(callerExternalId))
                owner <- repos.players.readByExternalId(ownerExternalId).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
                }
                characters <- repos.characters[T].listForPlayerAndGame(owner.playerId, gameId)
            } yield characters
        }

    /** A character's name and description, changed in its game engine at the request of `ownerExternalId`, who must own
      * it now.
      *
      * The engine signs the player in and says who they are; matchmaker is what knows who owns the character, so the
      * ownership is checked here, against the row locked for the write. A character that is not the owner's is answered
      * the same as one that does not exist, so that an engine cannot use this to find out what other players own.
      */
    def edit(
        characterId: CharacterId,
        name: String,
        description: String,
        ownerExternalId: String,
        callerExternalId: String
    ): IO[Character[T]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                for {
                    _ <- IO.raiseWhen(name.trim.isEmpty)(ValidationError(ErrorText.character.blankName))
                    existing <- ownedForUpdate(
                      characterRepo = repos.characters[T],
                      characterId = characterId,
                      ownerExternalId = ownerExternalId,
                      callerExternalId = callerExternalId
                    )
                    edited = existing.copy(name = name.trim, description = description.trim)
                    _ <- repos.characters[T].update(edited)
                } yield edited
            }
        }

    /** A character handed by its owner to the player called `toNickname`, done in its game engine.
      *
      * Checked as [[edit]] is. The new owner is named by nickname because that is what one player knows of another, and
      * it is unique. An invitation to the character follows it: whoever owns it when they answer is who may.
      */
    def transfer(
        characterId: CharacterId,
        toNickname: String,
        ownerExternalId: String,
        callerExternalId: String
    ): IO[Character[T]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                for {
                    existing <- ownedForUpdate(
                      characterRepo = repos.characters[T],
                      characterId = characterId,
                      ownerExternalId = ownerExternalId,
                      callerExternalId = callerExternalId
                    )
                    // For share: the character is about to reference this player, who must still exist when it does.
                    recipient <- repos.players.readByNicknameForShare(toNickname.trim).flatMap {
                        case Some(p) => IO.pure(p)
                        case None    => IO.raiseError(ValidationError(ErrorText.player.noneCalled(toNickname.trim)))
                    }
                    _ <- IO.raiseWhen(recipient.externalId == ownerExternalId)(
                      ValidationError(ErrorText.character.alreadyYours(existing.name))
                    )
                    handed = existing.copy(playerId = Some(recipient.playerId))
                    _ <- repos.characters[T].update(handed)
                } yield handed
            }
        }

    /* The character, locked for the write its caller is about to make, once it is known to be in the
     * calling engine's game and owned by the player the engine says asked. */
    private def ownedForUpdate(
        characterRepo: CharacterRepo[T],
        characterId: CharacterId,
        ownerExternalId: String,
        callerExternalId: String
    ): IO[Character[T]] =
        for {
            // For update: the game and owner checked below are those of the row the caller overwrites.
            joined <- characterRepo.readWithOwnerAndGameForUpdate(characterId).flatMap {
                case Some(t) => IO.pure(t)
                case None    => IO.raiseError(NotFoundError(ErrorText.character.notFound))
            }
            _ <- IO.raiseUnless(callerExternalId == joined.game.externalId)(
              UnauthorizedError(ErrorText.character.notThisGames)
            )
            _ <- IO.raiseUnless(ownerExternalId == joined.owner.externalId)(
              NotFoundError(ErrorText.character.notFound)
            )
        } yield joined.character

    /* The one game an engine's identity names, for the calls made on a game's behalf without naming it. */
    private def callersGame(found: IO[List[GameId]]): IO[GameId] =
        found.flatMap {
            case List(id) => IO.pure(id)
            case Nil      => IO.raiseError(UnauthorizedError(ErrorText.character.onlyItsGame))
            case _        => IO.raiseError(ConflictError(ErrorText.engine.sharedIdentity))
        }

    def updateState(
        characterId: CharacterId,
        state: T,
        callerExternalId: String
    ): IO[Character[T]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            // Same as update: the game checked here is the game the write is applied under.
            session.transaction.use { _ =>
                for {
                    // Same as update: read, authorize and write the same row without releasing it.
                    joined <- repos.characters[T].readWithGameForUpdate(characterId).flatMap {
                        case Some(t) => IO.pure(t)
                        case None    => IO.raiseError(NotFoundError(ErrorText.character.notFound))
                    }
                    existing = joined.character
                    game = joined.game
                    _ <- IO.raiseUnless(callerExternalId == game.externalId)(
                      UnauthorizedError(ErrorText.character.notThisGames)
                    )
                    updated = existing.copy(state = state)
                    _ <- repos.characters[T].update(updated)
                } yield updated
            }
        }
}
