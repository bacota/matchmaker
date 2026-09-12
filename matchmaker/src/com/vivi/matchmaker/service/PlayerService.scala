package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.Player
import com.vivi.matchmaker.persistence.PlayerRepo

/** The caller's own player record: reading it, and the two parts of it they may change.
  *
  * Registration lives in `RegistrationService`. The password is not here at all and never will
  * be — it belongs to the Cognito identity, the browser changes it against Cognito directly, and
  * matchmaker never sees it.
  *
  * The email address is a halfway case. Cognito still owns it: it is the username on the pool,
  * the browser still changes it there, and this service cannot verify an address or refuse a
  * change. What `updateEmail` does is record the change *after* Cognito has accepted it, so that
  * matchmaker has somewhere to write to (see V12). That makes the account form the one writer,
  * and makes a browser that changes the address and then fails to report it the one way the copy
  * can go stale.
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

  /** Records the address the caller now signs in with.
    *
    * Called by the account form once Cognito has confirmed the change — that is, once the code
    * mailed to the new address has come back — so what arrives here is an address Cognito has
    * verified, reported by the browser that watched it happen. Nothing here re-verifies it,
    * because nothing here can: matchmaker cannot mail a code and has no way to ask Cognito.
    *
    * What that means is worth being plain about: the stored address is exactly as trustworthy as
    * the caller's own client. A caller who drives the API directly can record an address that is
    * not theirs, and would then have their own notifications delivered to it. The blast radius is
    * their own mail, not another player's — the row updated is always the caller's. Moving this to
    * the token's verified `email` claim would close it, and would need the claim to reach the
    * router, which today only carries `sub`.
    *
    * Idempotent, since the form calls it after every confirmation and a repeat is the same row.
    */
  def updateEmail(callerExternalId: String, email: String): IO[Player] =
    for {
      address <- validEmail(email)
      player <- me(callerExternalId)
      changed = player.copy(email = Some(address))
      _ <- sessionPool.use(session => new PlayerRepo(session).update(changed))
    } yield changed

  /* Enough of a check to catch a blank field or an obvious mistype, and no more.
   *
   * Deliberately not a grammar for addresses: Cognito has already mailed this one and had the
   * code answered, which is a stronger statement about deliverability than any pattern, and a
   * stricter rule here could only reject an address that demonstrably works. */
  private def validEmail(email: String): IO[String] = {
    val trimmed = email.trim
    val plausible = trimmed.count(_ == '@') == 1 && !trimmed.startsWith("@") && !trimmed.endsWith("@") &&
      !trimmed.exists(_.isWhitespace)
    IO.raiseUnless(plausible)(ValidationError(s"'$email' is not an email address")).as(trimmed)
  }
}
