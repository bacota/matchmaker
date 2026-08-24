package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
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
                owner = joined.owner
                characterGame = joined.game
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
          // As in accept: the role must be one of this game's, checked here so a wrong one is a
          // 400 rather than a foreign-key violation surfacing as a 500. There is no "no role"
          // case left to skip — every acceptance names one, and creating a challenge writes the
          // challenger's acceptance.
          _ <- IO.raiseUnless(game.roles.exists(_.gameRoleId == challenge.gameRoleId))(
            ValidationError(s"game ${game.gameId.value} has no role ${challenge.gameRoleId.value}")
          )
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
              CharacterAcceptance(cc.challengeId, cc.challenger, cc.gameId, cc.characterId, cc.gameRoleId)
            case pc: PlainOpenChallenge =>
              PlainAcceptance(pc.challengeId, pc.challenger, pc.gameId, pc.gameRoleId)
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
    * attempts. The same lock covers the role check: `gameRoleId` must be one of the game's roles
    * and must not already be taken by another acceptance of this challenge, and two players
    * asking for the same free role at once must not both be told yes.
    */
  def accept(
      gameId: GameId,
      challengeId: ChallengeId,
      characterId: Option[CharacterId],
      gameRoleId: GameRoleId,
      callerExternalId: String
  ): IO[Acceptance] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
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
          // A challenge whose start is in flight is spoken for: its roster has already been
          // turned into participants and handed to the engine, so an acceptance added now would
          // never reach the match and would be deleted with the challenge when the start
          // finishes. Refused rather than silently lost.
          _ <- challengeInfo.startedMatchId.traverse_ { existing =>
            IO.raiseError(
              ConflictError(s"challenge ${challengeId.value} is being started as match ${existing.value} and can no longer be accepted")
            )
          }
          gameType = challengeInfo.gameType
          maxPlayers = challengeInfo.numberOfPlayers
          // A role has to be one of this game's, which the schema's composite foreign key also
          // enforces — checked here so that a wrong role is a 400 naming the game rather than a
          // constraint violation surfacing as a 500.
          _ <- requireGame(gameRepo, gameId).flatMap { game =>
            IO.raiseUnless(game.roles.exists(_.gameRoleId == gameRoleId))(
              ValidationError(s"game ${gameId.value} has no role ${gameRoleId.value}")
            )
          }
          // And it has to still be free. Under the challenge's lock, so two players asking for the
          // same role at the same moment cannot both pass this. The unique index on
          // (game_id, challenge_id, game_role_id) backs it up; this check is what makes the
          // refusal a 409 naming the role rather than a constraint violation.
          taken <- acceptanceRepo.rolesForChallenge(gameId, challengeId)
          _ <- IO.raiseWhen(taken.contains(gameRoleId))(
            ConflictError(s"role ${gameRoleId.value} is already taken in challenge ${challengeId.value}")
          )
          acceptance <- (gameType, characterId) match {
            case (GameType.Character, Some(cid)) =>
              for {
                // Locked for the same reason as in create: the acceptance about to be written
                // names this owner and references this character.
                joined <- characterRepo.readWithOwnerAndGameForUpdate(cid).flatMap {
                  case Some(t) => IO.pure(t)
                  case None    => IO.raiseError(NotFoundError(s"no character with id ${cid.value}"))
                }
                owner = joined.owner
                game = joined.game
                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not accept challenge ${challengeId.value} for character ${cid.value}")
                )
                _ <- IO.raiseUnless(game.gameId == gameId)(
                  ValidationError(s"character ${cid.value} is not from the same game as challenge ${challengeId.value}")
                )
              } yield CharacterAcceptance(challengeId, owner.playerId, gameId, cid, gameRoleId): Acceptance
            case (GameType.Plain, None) =>
              // Locked: the acceptance written below references this player.
              playerRepo.readByExternalIdForShare(callerExternalId).flatMap {
                case Some(player) => IO.pure(PlainAcceptance(challengeId, player.playerId, gameId, gameRoleId): Acceptance)
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
  def listByGame(gameId: GameId, callerExternalId: String): IO[List[OpenChallengeSummary]] =
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
          // Locked before anything else, for the same reason accept locks: the delete below must
          // not race a start of the same challenge. Without the lock a delete could land between
          // a start's first and last transactions and pull the challenge out from under it,
          // leaving the match already handed to the engine with no challenge to retire.
          locked <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
            case Some(l) => IO.pure(l)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
          }
          _ <- locked.startedMatchId.traverse_ { existing =>
            IO.raiseError(
              ConflictError(s"challenge ${challengeId.value} is being started as match ${existing.value} and can no longer be deleted")
            )
          }
          challenge <- challengeRepo.read(gameId, challengeId).flatMap {
            case Some(c) => IO.pure(c)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
          }
          _ <- challenge match {
            case cc: CharacterOpenChallenge =>
              // Locked: the owner read here is the only thing authorizing the delete below.
              characterRepo.readWithOwnerAndGameForUpdate(cc.characterId).flatMap {
                case Some(joined) =>
                  IO.raiseUnless(callerExternalId == joined.owner.externalId)(
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
