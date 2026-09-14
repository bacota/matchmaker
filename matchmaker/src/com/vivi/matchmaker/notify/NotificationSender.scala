package com.vivi.matchmaker.notify

import cats.effect.IO
import cats.syntax.all._

/** Putting an event's mail on the queue, on the three terms every notification in this codebase is sent on.
  *
  * Those terms were written out once, in `GameEngineService.start`, and then there were eight kinds of notification
  * sent from four services. They are:
  *
  *   - *Nothing without a sender and a link.* An environment given neither cannot say anything useful — a mail with no
  *     `From` is not one SES will accept, and one with no link is worse than no mail — so it says nothing. This is also
  *     what keeps the local server and every test that has no opinion about mail silent, with no special case in
  *     either.
  *   - *Never fails the caller.* A notification is a report of something that has already happened. The match exists,
  *     the acceptance is recorded, the turn is taken; failing the request that did it because the queue would not take
  *     a mail would undo nothing and help nobody.
  *   - *Never silently, either.* A failure is printed with what it was about, because a queue that cannot be reached
  *     and an event nobody was owed a mail for look identical from everywhere else: nothing retries, and nothing
  *     records that a notification was owed. That is a real cost of this design and it is written down rather than
  *     hidden — making it durable means writing the mail beside the event in its transaction and draining that table,
  *     which is a bigger change than this one and worth making the day a lost notification matters more than the action
  *     does.
  */
class NotificationSender(notifier: Notifier, mail: MailSettings) {

    /** Whether this environment can send at all. Not consulted by `dispatch`, which checks for itself — it is here for
      * the callers that would otherwise do a pile of reads to build a mail nobody is going to send.
      */
    val enabled: Boolean = mail.sender.isDefined && mail.uiBaseUrl.isDefined

    /** Composes and enqueues one event's mail.
      *
      * `compose` is handed the sender and the UI's base url and answers with every message the event is worth — at most
      * one per recipient, which is the caller's business to arrange (see `NotificationPolicy.choose`). It is not run at
      * all when the environment cannot send, so the reads it needs are not done either.
      *
      * @param about
      *   what this event was, for the log line if it fails. Specific enough to find the thing again: "match m-17", not
      *   "a match".
      */
    def dispatch(about: String)(compose: (String, String) => IO[Seq[MailMessage]]): IO[Unit] =
        mail.sender
            .zip(mail.uiBaseUrl)
            .traverse_ { (sender, uiBaseUrl) =>
                compose(sender, uiBaseUrl).flatMap(_.traverse_(notifier.enqueue))
            }
            .handleError(error => System.err.println(s"could not queue notifications for $about: $error"))
}
