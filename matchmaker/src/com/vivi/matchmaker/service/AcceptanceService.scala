package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.AcceptanceRepo

/** Deletes acceptances. Authorized by `callerExternalId` matching either the player who made
  * the acceptance or the player who owns the challenge (i.e. the challenger).
  */
class AcceptanceService(config: DbConfig) {

  def delete(challengeId: ChallengeId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
    DbSession.resource(config).use { session =>
      val acceptanceRepo = new AcceptanceRepo(session)
      for {
        joined <- acceptanceRepo.readWithChallengeAndPlayers(challengeId, playerId).flatMap {
          case Some(t) => IO.pure(t)
          case None    => IO.raiseError(NotFoundError(s"no acceptance for challenge ${challengeId.value} and player ${playerId.value}"))
        }
        (_, acceptor, challenger) = joined
        _ <- IO.raiseUnless(callerExternalId == acceptor.externalId || callerExternalId == challenger.externalId)(
          UnauthorizedError(
            s"caller '$callerExternalId' may not delete acceptance for challenge ${challengeId.value} and player ${playerId.value}"
          )
        )
        _ <- acceptanceRepo.delete(challengeId, playerId)
      } yield ()
    }
}
