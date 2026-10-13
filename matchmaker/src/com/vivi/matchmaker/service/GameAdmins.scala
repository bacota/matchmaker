package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameAdminRepo, PlayerRepo}

/** Who administers a game: an overall admin (`player.is_admin`), or one of the game's own (V35). */
object GameAdmins {

    /** That `caller` administers game `gameId`, or an `UnauthorizedError` saying `refusal`. Their player row and their
      * admin row are both held FOR SHARE, so that losing either waits for whatever this is permitting to be written.
      */
    def require(session: Session[IO], caller: PlayerId, gameId: GameId, refusal: => String): IO[Unit] =
        for {
            player <- new PlayerRepo(session).readForShare(caller).flatMap {
                case Some(p) => IO.pure(p)
                case None    => IO.raiseError(NotFoundError(s"no player with id ${caller.value}"))
            }
            allowed <-
                if (player.isAdmin) IO.pure(true)
                else new GameAdminRepo(session).isAdminForShare(player.playerId, gameId)
            _ <- IO.raiseUnless(allowed)(UnauthorizedError(refusal))
        } yield ()
}
