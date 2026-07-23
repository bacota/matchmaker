package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, OpenChallengeRepo, PlayerRepo}

/** Deletes acceptances. Authorized by `callerExternalId` matching either the player who made
  * the acceptance or the player who owns the challenge (i.e. the challenger).
  */
class AcceptanceService(config: DbConfig) {

  def delete(challengeId: ChallengeId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
    DbSession.resource(config).use { session =>
      val acceptanceRepo = new AcceptanceRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val playerRepo = new PlayerRepo(session)
      for {
        acceptance <- acceptanceRepo.read(challengeId, playerId).flatMap {
          case Some(a) => IO.pure(a)
          case None    => IO.raiseError(NotFoundError(s"no acceptance for challenge ${challengeId.value} and player ${playerId.value}"))
        }
        challenge <- challengeRepo.read(challengeId).flatMap {
          case Some(c) => IO.pure(c)
          case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value}"))
        }
        acceptor <- playerRepo.read(acceptance.playerId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with id ${acceptance.playerId.value}"))
        }
        challenger <- playerRepo.read(challenge.challenger).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with id ${challenge.challenger.value}"))
        }
        _ <- IO.raiseUnless(callerExternalId == acceptor.externalId || callerExternalId == challenger.externalId)(
          UnauthorizedError(
            s"caller '$callerExternalId' may not delete acceptance for challenge ${challengeId.value} and player ${playerId.value}"
          )
        )
        _ <- acceptanceRepo.delete(challengeId, playerId)
      } yield ()
    }
}
