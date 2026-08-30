package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.Player
import com.vivi.matchmaker.persistence.PlayerRepo

/** The caller's own player record: reading it, and the one part of it they may change.
  *
  * Registration lives in `RegistrationService`. Email and password are not here at all — they
  * belong to the Cognito identity, and the browser changes them against Cognito directly, so
  * matchmaker never sees a password and has no copy of the address to keep in step.
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

  /** Renames the caller.
    *
    * Only the nickname: `isAdmin` and `externalId` are copied through from the stored row rather
    * than taken from the caller, so this route cannot be used to grant oneself admin or to take
    * over another identity.
    *
    * Scoped to whoever is calling, so there is no target to authorize — a player can only rename
    * themselves, and an unregistered caller gets the same `UnauthorizedError` `me` gives.
    */
  def updateNickname(callerExternalId: String, nickname: String): IO[Player] =
    for {
      _ <- IO.raiseWhen(nickname.trim.isEmpty)(ValidationError("nickname must not be blank"))
      player <- me(callerExternalId)
      renamed = player.copy(nickname = nickname.trim)
      _ <- sessionPool.use { session =>
        new PlayerRepo(session).update(renamed).recoverWith { case SqlState.UniqueViolation(_) =>
          IO.raiseError(ConflictError(s"nickname '${renamed.nickname}' is already taken"))
        }
      }
    } yield renamed
}
