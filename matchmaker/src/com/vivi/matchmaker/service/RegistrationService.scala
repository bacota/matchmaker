package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.{Player, PlayerId}
import com.vivi.matchmaker.persistence.PlayerRepo

/** Registers new players. Anyone may register (no authorization rule), subject to the precondition that nickname and
  * externalId are both non-blank and not already taken.
  */
class RegistrationService(sessionPool: SessionPool) {

    /** @param email
      *   the address the identity signs in with, as the authenticator vouches for it — the verified `email` claim of
      *   the caller's token, never anything the request body says. Optional because the caller need not have a Cognito
      *   identity at all — locally the caller is a header — and a player registered without one is recorded at the next
      *   sign-in. See `PlayerService.updateEmail`.
      */
    def register(nickname: String, externalId: String, email: Option[String] = None): IO[Player] =
        for {
            _ <- IO.raiseWhen(nickname.trim.isEmpty)(ValidationError(ErrorText.player.blankNickname))
            _ <- IO.raiseWhen(externalId.trim.isEmpty)(ValidationError(ErrorText.registration.blankExternalId))
            player <- sessionPool.use { session =>
                new PlayerRepo(session)
                    .create(
                      Player(
                        playerId = PlayerId.unassigned,
                        nickname = nickname,
                        isAdmin = false,
                        externalId,
                        email.map(_.trim).filter(_.nonEmpty)
                      )
                    )
                    .recoverWith { case SqlState.UniqueViolation(_) =>
                        IO.raiseError(ConflictError(ErrorText.registration.taken(nickname)))
                    }
            }
        } yield player
}
