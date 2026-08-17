package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, PlayerRepo}

/** Lists and deletes acceptances. `delete` is authorized by `callerExternalId` matching either
  * the player who made the acceptance or the player who owns the challenge (i.e. the challenger);
  * `mine` is scoped to the caller's own player and so needs no further check.
  */
class AcceptanceService(sessionPool: SessionPool) {

  /** Every acceptance the caller has outstanding.
    *
    * Deliberately takes no player id: it lists the caller's own acceptances and nobody else's, so
    * there is no parameter that could ask for someone else's and no check needed to refuse it.
    */
  def mine(callerExternalId: String): IO[List[Acceptance]] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      for {
        player <- playerRepo.readByExternalId(callerExternalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$callerExternalId'"))
        }
        acceptances <- acceptanceRepo.listForPlayer(player.playerId)
      } yield acceptances
    }

  def delete(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
    sessionPool.use { session =>
      val acceptanceRepo = new AcceptanceRepo(session)
      // The repo's delete removes the character_acceptance row and the acceptance row as separate
      // statements, and the authorization checked below must hold for both.
      session.transaction.use { _ =>
        for {
          joined <- acceptanceRepo.readWithChallengeAndPlayers(gameId, challengeId, playerId).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no acceptance for challenge ${challengeId.value} and player ${playerId.value}"))
          }
          (_, acceptor, challenger) = joined
          _ <- IO.raiseUnless(callerExternalId == acceptor.externalId || callerExternalId == challenger.externalId)(
            UnauthorizedError(
              s"caller '$callerExternalId' may not delete acceptance for challenge ${challengeId.value} and player ${playerId.value}"
            )
          )
          _ <- acceptanceRepo.delete(gameId, challengeId, playerId)
        } yield ()
      }
    }
}
