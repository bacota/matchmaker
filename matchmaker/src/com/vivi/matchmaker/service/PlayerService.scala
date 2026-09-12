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

  /** Records the address the caller signs in with.
    *
    * One caller, and one moment: the browser, on sign-in, when the `email` claim of the token it
    * has just been issued disagrees with what is stored. Both halves of that matter.
    *
    * *On sign-in* rather than when the player changes their address. Cognito fixes the claims when
    * it issues a token, so a session that has just changed its address still carries the old one
    * for the rest of the token's life — and a client reporting the change directly would be
    * reporting something no token yet agrees with. Waiting until the next sign-in means the value
    * written always came from a token Cognito issued *after* the change. Nothing is lost by
    * waiting: the address is the username on that pool, so the next sign-in is with the new one.
    *
    * *From the claim* rather than from anything the player typed. Nothing here can verify an
    * address — matchmaker cannot mail a code and cannot ask Cognito — so the only statement worth
    * storing is the one Cognito already made by issuing a token that names it.
    *
    * What remains, and is worth being plain about: this is still the client's word for what its
    * token said, because the claim does not reach the router — `Authenticator` carries `sub` and
    * nothing else. A caller driving the API directly can therefore record an address that is not
    * theirs, and would have their own notifications sent to it. The blast radius is their own
    * mail, since the row updated is always the caller's. Carrying the claim through the
    * authenticator would close it.
    *
    * Writes the address and nothing else — `PlayerRepo.updateEmail`, not `update` — so that this
    * cannot restate a nickname from a `Player` read before it, and a rename cannot restate an
    * address. Idempotent: the ordinary case is that the claim and the stored value already agree,
    * and the caller does not send anything then.
    */
  def updateEmail(callerExternalId: String, email: String): IO[Player] =
    for {
      address <- validEmail(email)
      player <- me(callerExternalId)
      changed = player.copy(email = Some(address))
      _ <- sessionPool.use(session => new PlayerRepo(session).updateEmail(player.playerId, Some(address)))
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
