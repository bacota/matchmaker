package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.Instant
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}
import com.vivi.matchmaker.persistence.{PlayerRepo, SuppressionRepo}

/** What SES tells us about the mail we sent, and what a player can do about it.
  *
  * Two callers with nothing in common, which is why this is a service rather than a method on `NotificationService`.
  * [[record]] is the bounce consumer's, running in its own function with no request and no caller; [[mine]] and
  * [[retryMine]] are the notifications screen's, and are scoped to whoever is asking.
  *
  * The screen's two methods go through the caller's *stored* address rather than a claim, because the stored address is
  * the one the send path uses: it is what a `MailMessage` is addressed to, and so it is the only address whose
  * suppression would explain silence. A caller who has just changed their address in Cognito and has not signed in
  * again still has the old one here — and is, correctly, still being told about the old one's bounce, because that is
  * still where their mail is going.
  */
class SuppressionService(sessionPool: SessionPool) {

    /** Records what SES reported, one row per address.
      *
      * Takes a batch because a batch is what arrives: one SQS receive carries several events, and one event may name
      * several recipients. Sequential rather than concurrent, unlike the send path — these are writes to one table
      * keyed by address, and two events about the same address in the same batch are exactly the case where doing them
      * at once buys nothing and interleaves two upserts on one row.
      */
    def record(events: Seq[EmailSuppression.Event]): IO[Unit] =
        if (events.isEmpty) IO.unit
        else sessionPool.use(session => events.toList.traverse_(new SuppressionRepo(session).record))

    /** Whether mail to the caller is being held back, and why. `None` means nothing has gone wrong with it.
      *
      * Returns the row rather than a boolean: the screen says something different for a complaint than for a bounce,
      * and offers a button for one and not the other. A row that exists but is not [[EmailSuppression.active]] — one
      * transient delay, a released row — is not news, and is answered as `None` so that nothing is shown for it.
      */
    def mine(callerExternalId: String): IO[Option[EmailSuppression]] =
        sessionPool.use { session =>
            callerAddress(session, callerExternalId).flatMap {
                case None => IO.pure(None)
                case Some(address) =>
                    new SuppressionRepo(session).read(address).map(_.filter(_.active(Instant.now())))
            }
        }

    /** Try the caller's address again: clears the suppression, and says whether there was one to clear.
      *
      * Offered for a bounce, where the player has plausibly fixed a full mailbox. Refused for a complaint, and refused
      * here rather than only in the UI: a one-click undo of a spam report is exactly what the report exists to prevent,
      * and a button is not authority to grant one. A player who means it can change their address, which is a
      * deliberate act with a verification code in it — see `Account`.
      */
    def retryMine(callerExternalId: String): IO[Boolean] =
        sessionPool.use { session =>
            val repo = new SuppressionRepo(session)
            callerAddress(session, callerExternalId).flatMap {
                case None => IO.pure(false)
                case Some(address) =>
                    repo.read(address).flatMap {
                        case Some(row) if row.reason == SuppressionReason.Complaint =>
                            IO.raiseError(
                              ValidationError(
                                "This address reported our mail as spam, so we cannot start sending to it again."
                              )
                            )
                        case _ => repo.releaseFor(address)
                    }
            }
        }

    /* The caller's stored address, or nothing when they have none -- a row that predates V12, or a
     * local caller with no Cognito identity behind their `X-External-Id`. An unknown externalId is
     * `UnauthorizedError` for the same reason `PlayerService.me` says so: the caller holds a valid
     * identity that has never registered. */
    private def callerAddress(session: skunk.Session[IO], callerExternalId: String): IO[Option[String]] =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case Some(player) => IO.pure(player.email.map(_.trim).filter(_.nonEmpty))
            case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }
}
