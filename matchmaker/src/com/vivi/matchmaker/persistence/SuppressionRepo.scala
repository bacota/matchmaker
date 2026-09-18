package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import skunk.data.Completion
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}

/** The addresses mail is held back from: `email_suppression` (V16).
  *
  * A repo of its own rather than a few more columns on `PlayerRepo`, because it is not about players. The key is an
  * address, which may belong to no player row, to one whose `email` has since changed, or to two players sharing a
  * household mailbox — and the two callers are the bounce handler, which has never heard of a player, and the send
  * path, which is asking about the recipients of one event.
  *
  * No `FOR UPDATE` anywhere here, which is the exception to the rule in CLAUDE.md rather than an oversight: there is no
  * read that leads to a write. [[record]] is a single insert-on-conflict that does all of its deciding in the
  * statement, so two events about the same address arriving at once cannot lose one another's increment.
  */
class SuppressionRepo(session: Session[IO]) {

    private val reason: Codec[SuppressionReason] = text.imap(SuppressionReason.fromCode)(_.code)
    private val instant = SkunkCodecs.instant

    private val suppressionRow: Codec[EmailSuppression] =
        (text *: reason *: bool *: text.opt *: instant *: instant *: int4 *: instant.opt)
            .to[EmailSuppression]

    /* One statement, and all of the policy is in it.
     *
     * Written as an upsert rather than as a read, a decision and a write because two SES events about
     * the same address are genuinely concurrent -- one queue, one batch, several recipients of the
     * same dead domain -- and the read-decide-write version of this either loses an increment or
     * needs a lock to not. `ON CONFLICT` makes the increment the database's arithmetic.
     *
     * Four columns need explaining:
     *
     *   reason     -- the most serious event wins, not the most recent. A complaint outranks
     *                 anything that follows it, because it is what decides both what the screen says
     *                 and whether there is a button on it. An unreleased complaint therefore stays.
     *   permanent  -- sticky, except across a release. A release means "start over", so the
     *                 permanence that was forgiven does not come back to re-suppress the row on the
     *                 next transient delay; a genuine second permanent bounce sets it again.
     *   occurrences-- reset to 1 when the row was released or when the previous event is older than
     *                 the window, incremented otherwise. This is what makes the threshold "three in
     *                 seven days" and not "three ever".
     *   released_at-- always cleared. Any new event is news after the fact that somebody said to try
     *                 again, and the row goes back to being counted.
     *
     * `now()` throughout rather than a timestamp from the caller: the row is a record of when *we*
     * heard, and an SES event carries a timestamp from a clock we do not own. */
    private val upsert: Command[(String, SuppressionReason, Boolean, Option[String], Double)] =
        sql"""INSERT INTO email_suppression
                  (email, reason, permanent, diagnostic, first_seen_at, last_seen_at, occurrences, released_at)
              VALUES (lower($text), $reason, $bool, ${text.opt}, now(), now(), 1, NULL)
              ON CONFLICT (email) DO UPDATE SET
                  reason = CASE
                      WHEN email_suppression.released_at IS NULL
                          AND (
                            email_suppression.reason = 'complaint'
                            OR (email_suppression.reason = 'bounce' AND EXCLUDED.reason = 'delay')
                          )
                          THEN email_suppression.reason
                      ELSE EXCLUDED.reason
                  END,
                  permanent = EXCLUDED.permanent
                      OR (email_suppression.permanent AND email_suppression.released_at IS NULL),
                  diagnostic = COALESCE(EXCLUDED.diagnostic, email_suppression.diagnostic),
                  last_seen_at = now(),
                  occurrences = CASE
                      WHEN email_suppression.released_at IS NOT NULL
                          OR email_suppression.last_seen_at < now() - make_interval(secs => $float8)
                          THEN 1
                      ELSE email_suppression.occurrences + 1
                  END,
                  released_at = NULL""".command

    private val selectByEmail: Query[String, EmailSuppression] =
        sql"""SELECT email, reason, permanent, diagnostic, first_seen_at, last_seen_at, occurrences, released_at
              FROM email_suppression WHERE email = lower($text)""".query(suppressionRow)

    /* The predicate in `EmailSuppression.active`, in SQL, asked of many addresses at once.
     *
     * The database's own `now()` rather than a time from the caller, and one query rather than one
     * per recipient: this runs on the path a player is waiting on, where the send path already went
     * to some trouble to stop doing n sequential round trips.
     *
     * A statement per list length, which is what skunk's `list` encoder is: the lengths in practice
     * are the number of players in a match, so this prepares a handful of statements and then
     * reuses them. */
    private def selectActive(count: Int): Query[(List[String], Int, Double), String] =
        sql"""SELECT email FROM email_suppression
              WHERE email IN (${text.list(count)})
                AND released_at IS NULL
                AND (permanent OR (occurrences >= $int4 AND last_seen_at > now() - make_interval(secs => $float8)))"""
            .query(text)

    /* Read in order to decide whether to write, so the read takes the row's lock and the decision is
     * re-checked inside it -- `requireMatchForUpdate` and its counterparts in the other repos are
     * the same shape for the same reason (see CLAUDE.md).
     *
     * This table contends for exactly one pair of writers, and they are the interesting pair: a
     * player pressing "try again" while the bounce consumer records a complaint about the same
     * address. Without the lock the check and the release are two autocommit transactions, and a
     * complaint landing between them is a spam report undone by a button -- the one outcome this
     * feature must never produce. `reason <> 'complaint'` in the UPDATE would refuse it even then,
     * but that leaves the safety in a WHERE clause the service cannot see, and leaves the caller
     * told "nothing was holding your mail back" when the truth is "refused". */
    private val lockForRelease: Query[String, SuppressionReason] =
        sql"""SELECT reason FROM email_suppression
              WHERE email = lower($text) AND released_at IS NULL
              FOR UPDATE""".query(reason)

    private val release: Command[String] =
        sql"""UPDATE email_suppression SET released_at = now()
              WHERE email = lower($text) AND released_at IS NULL AND reason <> 'complaint'""".command

    /* The event's own identity, claimed before it is counted (V17).
     *
     * `ON CONFLICT DO NOTHING` reports `Insert(0)` when the id is already there, which is the whole
     * deduplication mechanism: a redelivered notification loses the race to claim its id, and the
     * count below is then skipped. */
    private val claimEvent: Command[(String, String)] =
        sql"""INSERT INTO email_suppression_event (event_id, email, recorded_at)
              VALUES ($text, lower($text), now())
              ON CONFLICT (event_id) DO NOTHING""".command

    /** Records one SES event against its address, creating the row or adding to it, and says whether it counted.
      *
      * `false` means this event had already been recorded and nothing changed. That is not an anomaly: SQS is
      * at-least-once, so a visibility timeout that expires mid-write, a function that times out, or a retry after a
      * partial-batch failure all deliver a notification whose effects are already here.
      *
      * Counting such a redelivery would be a real fault rather than a rounding error. The transient threshold is three,
      * so a single duplicate suppresses an address after two genuine failures — and the player it silences is
      * reachable. Hence the event id, derived from what SES generated (`SesEvent.identity`) and never from the SQS
      * message id: a redelivery *is* a new receipt of the same message, and a partial-batch retry carries the same
      * message id as the attempt that already succeeded for its siblings.
      *
      * Both statements in one transaction, which is what makes the pair honest in either direction: a crash between
      * them cannot leave an event claimed but uncounted (the suppression would never arrive) nor counted but unclaimed
      * (the next redelivery would count it again).
      */
    def record(event: EmailSuppression.Event): IO[Boolean] =
        session.transaction.use { _ =>
            session.execute(claimEvent)((event.eventId, event.email.trim)).flatMap {
                case Completion.Insert(0) => IO.pure(false)
                case _ =>
                    session
                        .execute(upsert)(
                          (
                            event.email.trim,
                            event.reason,
                            event.permanent,
                            event.diagnostic.map(_.trim).filter(_.nonEmpty),
                            EmailSuppression.windowSeconds
                          )
                        )
                        .as(true)
            }
        }

    /** Everything known about one address, suppressed or merely counted. `None` means nothing has ever gone wrong. */
    def read(email: String): IO[Option[EmailSuppression]] =
        session.option(selectByEmail)(email.trim)

    /** Which of these addresses mail is currently held back from, lowercased.
      *
      * Takes and returns a set because the caller has a recipient list and wants to filter it; the answer is folded to
      * lower case, as the table is, so a caller comparing against its own addresses must fold too.
      */
    def activeFor(emails: Set[String]): IO[Set[String]] = {
        val folded = emails.map(_.trim.toLowerCase).filter(_.nonEmpty).toList
        if (folded.isEmpty) IO.pure(Set.empty)
        else
            session
                .execute(selectActive(folded.length))(
                  (folded, EmailSuppression.transientThreshold, EmailSuppression.windowSeconds)
                )
                .map(_.toSet)
    }

    /** Marks an address as worth trying again, and says what it decided.
      *
      * Three answers rather than a boolean, because the caller is a button and the three mean different things to
      * whoever pressed it: it worked, there was nothing to do, and no. A complaint is refused here rather than only in
      * the service, so that the refusal and the write are one decision taken under one lock — the service checks too,
      * because that is where the sentence the player reads is composed, but not *only* there.
      *
      * All of it in one transaction, and the read takes the row's lock. That lock is what makes the check mean
      * anything: a complaint recorded between a lockless read and this update would be released by it, and re-enabling
      * mail to an address that has just reported us as spam is the one outcome this must never produce.
      */
    def releaseFor(email: String): IO[SuppressionRepo.Release] =
        session.transaction.use { _ =>
            session.option(lockForRelease)(email.trim).flatMap {
                case None                              => IO.pure(SuppressionRepo.Release.NotSuppressed)
                case Some(SuppressionReason.Complaint) => IO.pure(SuppressionRepo.Release.RefusedComplaint)
                case Some(_) => session.execute(release)(email.trim).as(SuppressionRepo.Release.Released)
            }
        }
}

object SuppressionRepo {

    /** What became of a request to try an address again. */
    enum Release {

        /** The suppression is lifted, and the next notification to this address will be sent. */
        case Released

        /** Nothing was holding mail back: an address that never failed, or one already released. */
        case NotSuppressed

        /** The address reported our mail as spam, and no button undoes that. */
        case RefusedComplaint
    }
}
