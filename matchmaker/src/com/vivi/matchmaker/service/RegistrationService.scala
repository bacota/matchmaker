package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.{Player, PlayerId}
import com.vivi.matchmaker.persistence.PlayerRepo

/** Registers new players. Anyone may register (no authorization rule), subject to the
  * precondition that nickname and externalId are both non-blank and not already taken.
  */
class RegistrationService(config: DbConfig) {

  def register(nickname: String, externalId: String): IO[Player] =
    for {
      _ <- IO.raiseWhen(nickname.trim.isEmpty)(ValidationError("nickname must not be blank"))
      _ <- IO.raiseWhen(externalId.trim.isEmpty)(ValidationError("externalId must not be blank"))
      player <- DbSession.resource(config).use { session =>
        new PlayerRepo(session)
          .create(Player(PlayerId.unassigned, nickname, isAdmin = false, externalId))
          .recoverWith { case SqlState.UniqueViolation(_) =>
            IO.raiseError(ConflictError(s"nickname '$nickname' or externalId '$externalId' is already registered"))
          }
      }
    } yield player
}
