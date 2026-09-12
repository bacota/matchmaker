package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.{Player, PlayerId}
import com.vivi.matchmaker.persistence.PlayerRepo

/** Registers new players. Anyone may register (no authorization rule), subject to the
  * precondition that nickname and externalId are both non-blank and not already taken.
  */
class RegistrationService(sessionPool: SessionPool) {

  /** @param email the address the identity signs in with, as the browser read it out of the ID
    *              token. Optional because the caller need not have a Cognito identity at all —
    *              locally the caller is a header — and because a client that does not send it
    *              should still be able to register; the account form records it later either
    *              way. See `PlayerService.updateEmail` for how far it is trusted.
    */
  def register(nickname: String, externalId: String, email: Option[String] = None): IO[Player] =
    for {
      _ <- IO.raiseWhen(nickname.trim.isEmpty)(ValidationError("nickname must not be blank"))
      _ <- IO.raiseWhen(externalId.trim.isEmpty)(ValidationError("externalId must not be blank"))
      player <- sessionPool.use { session =>
        new PlayerRepo(session)
          .create(Player(PlayerId.unassigned, nickname, isAdmin = false, externalId, email.map(_.trim).filter(_.nonEmpty)))
          .recoverWith { case SqlState.UniqueViolation(_) =>
            IO.raiseError(ConflictError(s"nickname '$nickname' or externalId '$externalId' is already registered"))
          }
      }
    } yield player
}
