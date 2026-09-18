package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** Why an address is on the suppression list.
  *
  * Three of SES's event types, not all of them. These are the ones that say something about the *address*: a bounce is
  * the mailbox answering, a complaint is the person answering, a delivery delay is the road being closed. A `Send`, a
  * `Delivery` or a `Reject` says something about us instead, and none of them would ever be a reason to stop writing to
  * somebody.
  */
enum SuppressionReason(val code: String) {

    /** The mailbox refused it. Permanent when SES says the address does not exist; transient when it was full, or the
      * receiving server was having a bad day.
      */
    case Bounce extends SuppressionReason("bounce")

    /** The person reported it as spam. Always permanent, and the one reason with no self-service undo. */
    case Complaint extends SuppressionReason("complaint")

    /** SES could not hand it over yet and is still trying. Never permanent by itself; three of them are. */
    case Delay extends SuppressionReason("delay")

    /** How much this outranks another, for the "most serious event wins" rule in the upsert. */
    def severity: Int = this match {
        case Complaint => 2
        case Bounce    => 1
        case Delay     => 0
    }
}

object SuppressionReason {

    def fromCode(code: String): SuppressionReason =
        values
            .find(_.code == code)
            .getOrElse(throw new IllegalArgumentException(s"not a suppression reason: '$code'"))
}

/** One address we have stopped writing to, or are counting failures against.
  *
  * A row exists as soon as anything goes wrong, which is not the same as the address being suppressed — see
  * [[EmailSuppression.active]]. That split is deliberate: a single transient failure is worth recording and not worth
  * acting on, and a table that only held the acted-on ones could not count to three.
  */
case class EmailSuppression(
    email: String,
    reason: SuppressionReason,
    permanent: Boolean,
    diagnostic: Option[String],
    firstSeenAt: Instant,
    lastSeenAt: Instant,
    occurrences: Int,
    releasedAt: Option[Instant]
) {

    /** Whether mail to this address should be held back, as of `now`.
      *
      * Stated here as well as in SQL, which is a duplication worth its cost: the send path asks the database (one query
      * for a whole event's recipients, and the answer has to be the database's own so that a row written a millisecond
      * ago counts), while the screens and the tests ask a value they are already holding. Both are
      * `SuppressionRepoSpec`'s to keep honest, and that spec asserts the two agree rather than trusting that they do.
      */
    def active(now: Instant): Boolean =
        releasedAt.isEmpty && (permanent || (
          occurrences >= EmailSuppression.transientThreshold &&
              lastSeenAt.isAfter(now.minus(EmailSuppression.transientWindow))
        ))
}

object EmailSuppression {

    /** How many transient failures inside [[transientWindow]] it takes to stop writing to an address.
      *
      * Three, which is a judgement and not a finding: fewer and a mailbox that was briefly full costs a player their
      * notifications; more and we spend a week mailing an address that plainly is not working. It lives here rather
      * than in the SQL so that the query and the model cannot disagree about it.
      */
    val transientThreshold: Int = 3

    /** How far back a transient failure counts. A failure older than this does not add to the count — the next one
      * starts over at one, rather than joining a tally from months ago.
      */
    val transientWindow: Duration = Duration.ofDays(7)

    /** [[transientWindow]] as `make_interval(secs => ...)` wants it, since that is the one place SQL needs it. */
    val windowSeconds: Double = transientWindow.getSeconds.toDouble

    /** What one SES event says, as the bounce handler hands it over. `permanent` is the handler's reading of the event,
      * not a property of the reason: a bounce is permanent or transient depending on what SES called it.
      */
    case class Event(email: String, reason: SuppressionReason, permanent: Boolean, diagnostic: Option[String])
}
