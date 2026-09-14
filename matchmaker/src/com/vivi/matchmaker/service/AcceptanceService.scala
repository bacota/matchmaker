package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.{MailSettings, NotificationSender, Notifier}
import com.vivi.matchmaker.persistence.{AcceptanceRepo, OpenChallengeRepo, PlayerRepo}

/** Lists and deletes acceptances. `delete` is authorized by `callerExternalId` matching either the player who made the
  * acceptance or the player who owns the challenge (i.e. the challenger); `mine` is scoped to the caller's own player
  * and so needs no further check. Backing out is refused once the challenge is being started, for the reason given in
  * `delete`.
  */
class AcceptanceService(
    sessionPool: SessionPool,
    /* Silent by default, as in `OpenChallengeService`: an environment with no queue and no sender
     * sends nothing, and a spec with no opinion about mail constructs this exactly as it did before
     * notifications existed. */
    sender: NotificationSender = new NotificationSender(Notifier.disabled, MailSettings.none)
) {

    private val notifications = new ChallengeNotifications(sender)

    /** Every acceptance the caller has outstanding.
      *
      * Deliberately takes no player id: it lists the caller's own acceptances and nobody else's, so there is no
      * parameter that could ask for someone else's and no check needed to refuse it.
      */
    def mine(callerExternalId: String): IO[List[PendingAcceptance]] =
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
            val challengeRepo = new OpenChallengeRepo(session)
            // The repo's delete removes the character_acceptance row and the acceptance row as separate
            // statements, and the authorization checked below must hold for both.
            val withdrawn = session.transaction.use { _ =>
                for {
                    // Locked first, and for more than the read below: this is what serializes backing out
                    // against a start of the same challenge. Once a start has claimed the challenge the
                    // roster is already participants in a match the engine has been told about, so backing
                    // out here would remove the acceptance while leaving that player seated in the game —
                    // there is nothing left to back out of.
                    locked <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
                        case Some(l) => IO.pure(l)
                        case None =>
                            IO.raiseError(
                              NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}")
                            )
                    }
                    _ <- locked.startedMatchId.traverse_ { existing =>
                        IO.raiseError(
                          ConflictError(
                            s"challenge ${challengeId.value} is being started as match ${existing.value}; player ${playerId.value} can no longer back out"
                          )
                        )
                    }
                    joined <- acceptanceRepo.readWithChallengeAndPlayers(gameId, challengeId, playerId).flatMap {
                        case Some(t) => IO.pure(t)
                        case None =>
                            IO.raiseError(
                              NotFoundError(
                                s"no acceptance for challenge ${challengeId.value} and player ${playerId.value}"
                              )
                            )
                    }
                    (_, acceptor, challenger) = joined
                    _ <- IO.raiseUnless(
                      callerExternalId == acceptor.externalId || callerExternalId == challenger.externalId
                    )(
                      UnauthorizedError(
                        s"caller '$callerExternalId' may not delete acceptance for challenge ${challengeId.value} and player ${playerId.value}"
                      )
                    )
                    _ <- acceptanceRepo.delete(gameId, challengeId, playerId)
                    // Carried out of the transaction because the row that knows them is about to be
                    // gone: the mail tells the other players whose seat has opened up, and `acceptor`
                    // is the last thing holding their nickname.
                } yield (acceptor, challenger)
            }

            /* After the commit, outside the challenge's lock, and unable to fail the withdrawal --
             * the same three terms as `OpenChallengeService.accept`; see `NotificationSender`.
             *
             * `except` is the caller rather than the acceptor, because these are not always the same
             * player: a challenger may remove somebody else's acceptance, and the news then belongs to
             * everyone except the challenger who did it. */
            withdrawn.flatMap { (acceptor, challenger) =>
                val actedFor =
                    if (callerExternalId == challenger.externalId) Some(challenger.playerId)
                    else Some(acceptor.playerId)
                notifications.rosterChanged(
                  session,
                  gameId,
                  challengeId,
                  actor = acceptor,
                  joined = false,
                  except = actedFor
                )
            }
        }
}
