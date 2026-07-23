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
