package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, GameRepo, OpenChallengeRepo, PlayerRepo, TextCodec}

/** Creates and deletes open challenges. For a `'C'`-type game (a [[CharacterOpenChallenge]]),
  * both operations are authorized by `callerExternalId` matching the externalId of the player
  * who owns the challenge's character, same as before. For a `'P'`-type game (a
  * [[PlainOpenChallenge]]) there is no character to authorize through, so `callerExternalId`
  * must match the challenger player directly.
  */
class OpenChallengeService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

  private def requireGame(gameRepo: GameRepo[T], gameId: GameId): IO[Game] =
    gameRepo.read(gameId).flatMap {
      case Some(g) => IO.pure(g)
      case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
    }

  /** The player a challenge is being created or deleted for, locked for the rest of the
    * transaction so the authorization decided from it cannot be invalidated before the write.
    */
  private def requirePlayer(playerRepo: PlayerRepo, playerId: PlayerId): IO[Player] =
    playerRepo.readForShare(playerId).flatMap {
      case Some(p) => IO.pure(p)
      case None    => IO.raiseError(NotFoundError(s"no player with id ${playerId.value}"))
    }

  def create(challenge: OpenChallenge, callerExternalId: String): IO[OpenChallenge] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      // Creating a challenge is itself an acceptance of it: the challenger is the first
      // participant. Both rows go in together so a challenge can never exist with its creator
      // missing from its own acceptances.
      session.transaction.use { _ =>
        for {
          game <- requireGame(gameRepo, challenge.gameId)
          _ <- challenge match {
            case cc: CharacterOpenChallenge =>
              for {
                _ <- IO.raiseUnless(game.gameType == GameType.Character)(
                  ValidationError(s"game ${game.gameId.value} does not require a character, but a CharacterOpenChallenge was given")
                )
                // Locked: ownership is what authorizes this challenge, and the challenge row
                // inserted below references the character. An unlocked read would let the
                // character be reassigned or removed between the check and the insert.
                joined <- characterRepo.readWithOwnerAndGameForUpdate(cc.characterId).flatMap {
                  case Some(t) => IO.pure(t)
                  case None    => IO.raiseError(NotFoundError(s"no character with id ${cc.characterId.value}"))
                }
                (_, owner, characterGame) = joined
                _ <- IO.raiseUnless(challenge.gameId == characterGame.gameId)(
                  ValidationError(
                    s"challenge game_id ${challenge.gameId.value} does not match character's game_id ${characterGame.gameId.value}"
                  )
                )
                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not create a challenge for character ${cc.characterId.value}")
                )
                // The caller owning the character is not enough on its own: challenger names the
                // player the challenge (and now its implicit acceptance) is recorded under, so it
                // has to be the character's owner too, not some other player the caller picked.
                _ <- IO.raiseUnless(challenge.challenger == owner.playerId)(
                  UnauthorizedError(
                    s"player ${challenge.challenger.value} does not own character ${cc.characterId.value}"
                  )
                )
              } yield ()
            case _: PlainOpenChallenge =>
              for {
                _ <- IO.raiseUnless(game.gameType == GameType.Plain)(
                  ValidationError(s"game ${game.gameId.value} requires a character, but a PlainOpenChallenge was given")
                )
                challengerPlayer <- requirePlayer(playerRepo, challenge.challenger)
                _ <- IO.raiseUnless(callerExternalId == challengerPlayer.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not create a challenge for player ${challenge.challenger.value}")
                )
              } yield ()
          }
          _ <- IO.raiseUnless(
            challenge.numberOfPlayers >= game.minPlayers && challenge.numberOfPlayers <= game.maxPlayers
          )(
            ValidationError(
              s"numberOfPlayers ${challenge.numberOfPlayers} is not in range [${game.minPlayers}, ${game.maxPlayers}]"
            )
          )
          created <- challengeRepo.create(challenge)
          _ <- acceptanceRepo.create(created match {
            case cc: CharacterOpenChallenge =>
              CharacterAcceptance(cc.challengeId, cc.challenger, cc.gameId, cc.characterId)
            case pc: PlainOpenChallenge =>
              PlainAcceptance(pc.challengeId, pc.challenger, pc.gameId)
          })
        } yield created
      }
    }

  /** Accepts `challengeId` in game `gameId`, authorized by `callerExternalId`. For a `'C'`-type
    * game's challenge, `characterId` must be `Some`, naming the character accepting on the
    * caller's behalf, and is authorized the same way `create` authorizes a
    * [[CharacterOpenChallenge]]. For a `'P'`-type game's challenge, `characterId` must be `None`,
    * and the caller accepts as themselves. The challenge row is locked (`FOR UPDATE`) before
    * counting existing acceptances, so that the capacity check (acceptances, including this one,
    * must not exceed the challenge's numberOfPlayers) is race-free against concurrent acceptance
    * attempts.
    */
  def accept(gameId: GameId, challengeId: ChallengeId, characterId: Option[CharacterId], callerExternalId: String): IO[Acceptance] =
    sessionPool.use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      session.transaction.use { _ =>
        for {
          challengeInfo <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
          }
          (gameType, maxPlayers) = challengeInfo
          acceptance <- (gameType, characterId) match {
            case (GameType.Character, Some(cid)) =>
              for {
                // Locked for the same reason as in create: the acceptance about to be written
                // names this owner and references this character.
                joined <- characterRepo.readWithOwnerAndGameForUpdate(cid).flatMap {
                  case Some(t) => IO.pure(t)
                  case None    => IO.raiseError(NotFoundError(s"no character with id ${cid.value}"))
                }
                (_, owner, game) = joined
                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not accept challenge ${challengeId.value} for character ${cid.value}")
                )
                _ <- IO.raiseUnless(game.gameId == gameId)(
                  ValidationError(s"character ${cid.value} is not from the same game as challenge ${challengeId.value}")
                )
              } yield CharacterAcceptance(challengeId, owner.playerId, gameId, cid): Acceptance
            case (GameType.Plain, None) =>
              // Locked: the acceptance written below references this player.
              playerRepo.readByExternalIdForShare(callerExternalId).flatMap {
                case Some(player) => IO.pure(PlainAcceptance(challengeId, player.playerId, gameId): Acceptance)
                case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
              }
            case (GameType.Character, None) =>
              IO.raiseError(ValidationError(s"challenge ${challengeId.value} requires a characterId to accept"))
            case (GameType.Plain, Some(_)) =>
              IO.raiseError(ValidationError(s"challenge ${challengeId.value} does not accept a characterId"))
          }
          existing <- acceptanceRepo.read(gameId, challengeId, acceptance.playerId)
          _ <- IO.raiseWhen(existing.isDefined)(
            ConflictError(s"player ${acceptance.playerId.value} has already accepted challenge ${challengeId.value}")
          )
          count <- acceptanceRepo.countForChallenge(gameId, challengeId)
          _ <- IO.raiseUnless(count + 1 <= maxPlayers.toLong)(
            ValidationError(s"challenge ${challengeId.value} already has $count acceptance(s) (capacity $maxPlayers)")
          )
          created <- acceptanceRepo.create(acceptance)
        } yield created
      }
    }

  /** The open challenges for a game, which any registered player may browse in order to accept
    * one.
    */
  def listByGame(gameId: GameId, callerExternalId: String): IO[List[OpenChallenge]] =
    sessionPool.use { session =>
      for {
        _ <- new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
          case Some(player) => IO.pure(player)
          case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }
        challenges <- new OpenChallengeRepo(session).listByGame(gameId)
      } yield challenges
    }

  def delete(gameId: GameId, challengeId: ChallengeId, callerExternalId: String): IO[Unit] =
    sessionPool.use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      session.transaction.use { _ =>
        for {
          challenge <- challengeRepo.read(gameId, challengeId).flatMap {
            case Some(c) => IO.pure(c)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
          }
          _ <- challenge match {
            case cc: CharacterOpenChallenge =>
              // Locked: the owner read here is the only thing authorizing the delete below.
              characterRepo.readWithOwnerAndGameForUpdate(cc.characterId).flatMap {
                case Some((_, owner, _)) =>
                  IO.raiseUnless(callerExternalId == owner.externalId)(
                    UnauthorizedError(s"caller '$callerExternalId' may not delete challenge ${challengeId.value}")
                  )
                case None => IO.raiseError(NotFoundError(s"no character with id ${cc.characterId.value}"))
              }
            case pc: PlainOpenChallenge =>
              requirePlayer(playerRepo, pc.challenger).flatMap { challenger =>
                IO.raiseUnless(callerExternalId == challenger.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not delete challenge ${challengeId.value}")
                )
              }
          }
          _ <- acceptanceRepo.deleteAllForChallenge(gameId, challengeId)
          _ <- challengeRepo.delete(gameId, challengeId)
        } yield ()
      }
    }
}
