package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, OpenChallengeRepo, TextCodec}

/** Creates and deletes open challenges. Both operations are authorized by
  * `callerExternalId`, which must match the externalId of the player who owns the
  * challenge's character.
  */
class OpenChallengeService[T](config: DbConfig)(using codec: TextCodec[T]) {

  def create(challenge: OpenChallenge, callerExternalId: String): IO[OpenChallenge] =
    DbSession.resource(config).use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val challengeRepo = new OpenChallengeRepo(session)
      for {
        joined <- characterRepo.readWithOwnerAndGame(challenge.characterId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no character with id ${challenge.characterId.value}"))
        }
        (_, owner, game) = joined
        _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not create a challenge for character ${challenge.characterId.value}")
        )
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

  /** Accepts `challengeId` on behalf of `characterId`, authorized by `callerExternalId`
    * matching the externalId of the player who owns that character. The character must
    * belong to the same game as the challenge. The challenge row is locked (`FOR UPDATE`)
    * before counting existing acceptances, so that the capacity check (acceptances,
    * including this one, must not exceed the challenge's numberOfPlayers) is race-free
    * against concurrent acceptance attempts.
    */
  def accept(challengeId: ChallengeId, characterId: CharacterId, callerExternalId: String): IO[Acceptance] =
    DbSession.resource(config).use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      session.transaction.use { _ =>
        for {
          challengeInfo <- challengeRepo.readForUpdate(challengeId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value}"))
          }
          (challengeGameId, maxPlayers) = challengeInfo
          joined <- characterRepo.readWithOwnerAndGame(characterId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
          }
          (_, owner, game) = joined
          _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
            UnauthorizedError(s"caller '$callerExternalId' may not accept challenge ${challengeId.value} for character ${characterId.value}")
          )
          _ <- IO.raiseUnless(game.gameId == challengeGameId)(
            ValidationError(s"character ${characterId.value} is not from the same game as challenge ${challengeId.value}")
          )
          count <- acceptanceRepo.countForChallenge(challengeId)
          _ <- IO.raiseUnless(count + 1 <= maxPlayers.toLong)(
            ValidationError(s"challenge ${challengeId.value} already has $maxPlayers acceptance(s)")
          )
          created <- acceptanceRepo.create(Acceptance(challengeId, owner.playerId, game.gameId, characterId))
        } yield created
      }
    }

  def delete(challengeId: ChallengeId, callerExternalId: String): IO[Unit] =
    DbSession.resource(config).use { session =>
      val characterRepo = new CharacterRepo[T](session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      for {
        challenge <- challengeRepo.read(challengeId).flatMap {
          case Some(c) => IO.pure(c)
          case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value}"))
        }
        joined <- characterRepo.readWithOwnerAndGame(challenge.characterId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no character with id ${challenge.characterId.value}"))
        }
        (_, owner, _) = joined
        _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not delete challenge ${challengeId.value}")
        )
        _ <- acceptanceRepo.deleteAllForChallenge(challengeId)
        _ <- challengeRepo.delete(challengeId)
      } yield ()
    }
}
