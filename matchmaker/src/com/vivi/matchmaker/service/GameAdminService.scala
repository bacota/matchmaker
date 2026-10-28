package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import com.vivi.matchmaker.model.{GameAdmin, GameId, Player, PlayerId}
import com.vivi.matchmaker.persistence.{GameAdminRepo, Repos, TextCodec}

/** Who administers a game: the players with special privileges in managing its matches.
  *
  * Two kinds of admin, and they differ in what they may take away. An overall admin (`Player.isAdmin`) may make any
  * player an admin of any game, and take it away from any of them. A game's admin may make other players admins of that
  * game too, but may take it away only from those they made admins themselves, and only while they are still one. Who
  * made whom is the row's `granted_by`, and making an admin of somebody who already is one does not change it.
  *
  * An overall admin is not a row here: they administer every game already, so a check of a game admin's privilege is
  * `player.isAdmin || GameAdminRepo.isAdminForShare(...)`.
  */
class GameAdminService[T](sessionPool: SessionPool)(using TextCodec[T]) {

    /** The game's admins, for any registered player: who runs a game is not a secret from the people who play it.
      * Overall admins are not listed, as they are not rows.
      */
    def list(gameId: GameId, callerExternalId: String): IO[List[GameAdmin]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                _ <- requireCaller(repos.players.readByExternalId(callerExternalId), callerExternalId)
                // Read plainly, and the game's existence in the same query: nothing is written, so there is
                // nothing for a lock to protect.
                admins <- repos.gameAdmins.listForGame(gameId).flatMap {
                    case Some(admins) => IO.pure(admins)
                    case None         => IO.raiseError(NotFoundError(ErrorText.game.notFound))
                }
            } yield admins
        }

    /** Makes `playerId` an admin of the game, if the caller is an overall admin or an admin of that game. Making an
      * admin of somebody who already is one is not an error: what was asked for is true either way.
      */
    def grant(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                for {
                    // FOR SHARE throughout: the caller's own row, so that being demoted from overall admin
                    // waits for this grant; their row here, so that a revoke of it does too; and the game and
                    // the player, which the insert references.
                    caller <- requireCaller(repos.players.readByExternalIdForShare(callerExternalId), callerExternalId)
                    _ <- requireGame(repos.games[T].lockForShare(gameId), gameId)
                    allowed <-
                        if (caller.isAdmin) IO.pure(true) else repos.gameAdmins.isAdminForShare(caller.playerId, gameId)
                    _ <- IO.raiseUnless(allowed)(
                      UnauthorizedError(ErrorText.gameAdmin.grantNotAllowed)
                    )
                    _ <- repos.players.readForShare(playerId).flatMap {
                        case Some(_) => IO.unit
                        case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
                    }
                    _ <- repos.gameAdmins.grant(player = playerId, game = gameId, grantedBy = caller.playerId)
                } yield ()
            }
        }

    /** Takes `playerId`'s admin of the game away: an overall admin's to do for anybody, and a game's admin's for the
      * admins they made. A 404 for a player who was not one, so that a screen showing them as one learns it was out of
      * date.
      */
    def revoke(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                for {
                    // FOR SHARE, so that the caller's own demotion cannot land between this check and the delete.
                    caller <- requireCaller(
                      repos.players.readByExternalIdForShare(callerExternalId),
                      callerExternalId
                    )
                    // FOR UPDATE: the row about to be deleted, whose `granted_by` decides whether it may be.
                    grantedBy <- repos.gameAdmins.grantedByForUpdate(playerId, gameId).flatMap {
                        case Some(by) => IO.pure(by)
                        case None =>
                            IO.raiseError(NotFoundError(ErrorText.gameAdmin.notAnAdmin))
                    }
                    // A game's admin who made this one, and is still an admin of the game themselves — FOR SHARE,
                    // so that losing it waits for this revoke rather than landing in the middle of it.
                    allowed <-
                        if (caller.isAdmin) IO.pure(true)
                        else if (grantedBy != caller.playerId) IO.pure(false)
                        else repos.gameAdmins.isAdminForShare(caller.playerId, gameId)
                    _ <- IO.raiseUnless(allowed)(
                      UnauthorizedError(ErrorText.gameAdmin.revokeNotAllowed)
                    )
                    _ <- repos.gameAdmins.revoke(playerId, gameId)
                } yield ()
            }
        }

    private def requireCaller(read: IO[Option[Player]], callerExternalId: String): IO[Player] =
        read.flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
        }

    private def requireGame(read: IO[Option[GameId]], gameId: GameId): IO[Unit] =
        read.flatMap {
            case Some(_) => IO.unit
            case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
        }
}
