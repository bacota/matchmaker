package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model.Player
import com.vivi.matchmaker.persistence.PlayerRepo

/** Reads the caller's own player record. Registration lives in `RegistrationService`; this is
  * only the "who am I" lookup, which every authenticated screen needs.
  */
class PlayerService(sessionPool: SessionPool) {

  /** The player registered under `callerExternalId`.
    *
    * An unknown externalId is `UnauthorizedError`, not `NotFoundError`: the caller holds a valid
    * identity that has never registered, and the fix is to register, not to look elsewhere.
    */
  def me(callerExternalId: String): IO[Player] =
    sessionPool.use { session =>
      new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
        case Some(player) => IO.pure(player)
        case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
      }
    }
}
