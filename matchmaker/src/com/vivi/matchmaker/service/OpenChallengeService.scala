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

  private def requirePlayer(playerRepo: PlayerRepo, playerId: PlayerId): IO[Player] =
    playerRepo.read(playerId).flatMap {
      case Some(p) => IO.pure(p)
      case None    => IO.raiseError(NotFoundError(s"no player with id ${playerId.value}"))
    }

  def create(challenge: OpenChallenge, callerExternalId: String): IO[OpenChallenge] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      for {
        game <- requireGame(gameRepo, challenge.gameId)
        _ <- challenge match {
          case cc: CharacterOpenChallenge =>
            for {
              _ <- IO.raiseUnless(game.gameType == GameType.Character)(
                ValidationError(s"game ${game.gameId.value} does not require a character, but a CharacterOpenChallenge was given")
              )
              joined <- characterRepo.readWithOwnerAndGame(cc.characterId).flatMap {
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
      } yield created
    }

  /** Accepts `challengeId`, authorized by `callerExternalId`. For a `'C'`-type game's challenge,
    * `characterId` must be `Some`, naming the character accepting on the caller's behalf, and is
    * authorized the same way `create` authorizes a [[CharacterOpenChallenge]]. For a `'P'`-type
    * game's challenge, `characterId` must be `None`, and the caller accepts as themselves. The
    * challenge row is locked (`FOR UPDATE`) before counting existing acceptances, so that the
    * capacity check (acceptances, including this one, must not exceed the challenge's
    * numberOfPlayers) is race-free against concurrent acceptance attempts.
    */
  def accept(challengeId: ChallengeId, characterId: Option[CharacterId], callerExternalId: String): IO[Acceptance] =
    sessionPool.use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      session.transaction.use { _ =>
        for {
          challengeInfo <- challengeRepo.readForUpdate(challengeId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value}"))
          }
          (challengeGameId, gameType, maxPlayers) = challengeInfo
          acceptance <- (gameType, characterId) match {
            case (GameType.Character, Some(cid)) =>
              for {
                joined <- characterRepo.readWithOwnerAndGame(cid).flatMap {
                  case Some(t) => IO.pure(t)
                  case None    => IO.raiseError(NotFoundError(s"no character with id ${cid.value}"))
                }
                (_, owner, game) = joined
                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                  UnauthorizedError(s"caller '$callerExternalId' may not accept challenge ${challengeId.value} for character ${cid.value}")
                )
                _ <- IO.raiseUnless(game.gameId == challengeGameId)(
                  ValidationError(s"character ${cid.value} is not from the same game as challenge ${challengeId.value}")
                )
              } yield CharacterAcceptance(challengeId, owner.playerId, game.gameId, cid): Acceptance
            case (GameType.Plain, None) =>
              playerRepo.readByExternalId(callerExternalId).flatMap {
                case Some(player) => IO.pure(PlainAcceptance(challengeId, player.playerId, challengeGameId): Acceptance)
                case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
              }
            case (GameType.Character, None) =>
              IO.raiseError(ValidationError(s"challenge ${challengeId.value} requires a characterId to accept"))
            case (GameType.Plain, Some(_)) =>
              IO.raiseError(ValidationError(s"challenge ${challengeId.value} does not accept a characterId"))
          }
          count <- acceptanceRepo.countForChallenge(challengeId)
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

  def delete(challengeId: ChallengeId, callerExternalId: String): IO[Unit] =
    sessionPool.use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      session.transaction.use { _ =>
        for {
          challenge <- challengeRepo.read(challengeId).flatMap {
            case Some(c) => IO.pure(c)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value}"))
          }
          _ <- challenge match {
            case cc: CharacterOpenChallenge =>
              characterRepo.readWithOwnerAndGame(cc.characterId).flatMap {
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
          _ <- acceptanceRepo.deleteAllForChallenge(challengeId)
          _ <- challengeRepo.delete(challengeId)
        } yield ()
      }
    }
}
