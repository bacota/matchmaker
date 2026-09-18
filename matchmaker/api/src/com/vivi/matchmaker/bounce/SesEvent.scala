package com.vivi.matchmaker.bounce

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}

/** Reading SES's own words about a mail we sent.
  *
  * The shape is SES's, and it is not tidy: an event arrives as a notification wrapped in an SNS envelope wrapped in an
  * SQS record, the key naming the kind is `eventType` when it came through a configuration set's event destination and
  * `notificationType` when it came from an identity's own notification topic, and the bounced addresses live under a
  * different key from the complained ones. All of that is decoded here, positionally and defensively, rather than into
  * case classes: matchmaker cares about four facts per recipient, and a decoder that insisted on the rest of the
  * document would fail on the parts of it AWS is free to add to.
  *
  * A notification is either understood or unreadable, and the difference is deliberate rather than incidental. A
  * `Delivery` event we have no use for is understood: it says nothing about an address, and acknowledging it is
  * correct. A document whose shape we do not recognise is unreadable, and the consumer fails it so that it reaches the
  * bounce queue's dead-letter queue instead of being acknowledged and forgotten.
  *
  * That asymmetry is the whole point. A lost suppression is not a lost message: it is an address matchmaker goes on
  * mailing after SES told us to stop, which costs sending reputation and, for a complaint, rather more than that. The
  * DLQ is where such a thing is visible — it has a queue-depth metric and a terraform output naming it, and its
  * messages can be replayed once the reader is fixed — where a log line is where it is not. If SES ever changes one of
  * these payloads, the failure should be a pile of messages in a queue, not a silence.
  */
object SesEvent {

    /** What one notification turned out to be. */
    enum Reading {

        /** Read successfully. `events` is empty for a kind that says nothing about an address, which is the ordinary
          * answer for anything the configuration set publishes beyond the three asked for.
          */
        case Understood(events: Seq[EmailSuppression.Event])

        /** Not read. Either the document is not an SES notification at all, or it is one whose shape has moved — and
          * either way somebody has to look at it, which is what the dead-letter queue is for.
          */
        case Unreadable(why: String)
    }

    /** The kinds that are about us or about good news, and so are correctly ignored.
      *
      * Named rather than matched by `case _`, because "a kind we do not act on" and "a document we cannot read" must
      * not be the same answer: the first is acknowledged, the second is failed. Listed from SES's own event types.
      */
    private val ignored =
        Set("Send", "Delivery", "Open", "Click", "Reject", "RenderingFailure", "Rendering Failure", "Subscription")

    /** Every address one notification says something about.
      *
      * Several, because one mail can have several recipients and SES reports the ones that failed. Matchmaker sends to
      * one address per mail today, so this is in practice one — but the field is a list in the document, and reading
      * only its head is the kind of shortcut that quietly drops a suppression the day that changes.
      *
      * A recognised kind whose payload cannot be read is [[Reading.Unreadable]] rather than an empty list, and that is
      * the case worth caring about: it is what a changed SES payload looks like from here, and the version of this that
      * returned `Nil` would have discarded every bounce in the account without anything failing to say so.
      */
    def suppressions(notification: ujson.Value): Reading =
        try {
            val obj = notification.obj
            val kind = obj.get("eventType").orElse(obj.get("notificationType")).flatMap(_.strOpt)

            /* The mail's own id, which is SES's and is the same across every receive of this
             * notification. Part of the identity below rather than the whole of it: one mail can
             * produce a bounce and, earlier, a delay, and those are two events about it. */
            val messageId = obj.get("mail").flatMap(_.objOpt).flatMap(_.get("messageId")).flatMap(_.strOpt)

            kind match {
                case Some("Bounce")        => attributed(kind, bounces(messageId, obj.get("bounce")))
                case Some("Complaint")     => attributed(kind, complaints(messageId, obj.get("complaint")))
                case Some("DeliveryDelay") => attributed(kind, delays(messageId, obj.get("deliveryDelay")))

                // Understood and not acted on. A configuration set subscribed to more than we asked
                // for is a configuration choice, not a corruption.
                case Some(other) if ignored.contains(other) => Reading.Understood(Nil)

                /* A kind nobody here has heard of. Also understood, deliberately: AWS adds event
                 * types, and an event destination can be pointed at this queue by someone changing
                 * terraform. Failing those would fill the dead-letter queue with things that are
                 * working as intended, which is the fastest way to make a full DLQ mean nothing. */
                case Some(_) => Reading.Understood(Nil)

                // No eventType and no notificationType: whatever this is, it is not one of these.
                case None => Reading.Unreadable("no eventType or notificationType")
            }
        } catch {
            case NonFatal(error) => Reading.Unreadable(s"could not be read as a notification: $error")
        }

    /* A recognised kind has to yield at least one address, or we have read the wrong document.
     *
     * This is the check that catches a payload whose shape has moved -- a renamed recipients key, a
     * bounce nested one level deeper -- which is otherwise indistinguishable from a bounce about
     * nobody. There is no such thing as a bounce about nobody: SES reports a bounce because a
     * recipient did not receive it. */
    private def attributed(kind: Option[String], events: Seq[EmailSuppression.Event]): Reading =
        if (events.nonEmpty) Reading.Understood(events)
        else Reading.Unreadable(s"a ${kind.getOrElse("?")} naming no readable recipient")

    /* `bounceType` is Permanent, Transient or Undetermined, and only the first means the mailbox is
     * gone for good.
     *
     * Undetermined is treated as transient deliberately. SES uses it when the remote server said
     * something it could not classify, which is at least as often a badly behaved mail server as a
     * dead address -- and the cost of guessing wrong in this direction is two more bounces before
     * the threshold suppresses it anyway, where guessing wrong in the other direction is a playing
     * player who silently stops hearing from us. */
    private def bounces(messageId: Option[String], bounce: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        bounce.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val permanent = obj.get("bounceType").flatMap(_.strOpt).contains("Permanent")
            recipients(obj.get("bouncedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                val subType = obj.get("bounceSubType").flatMap(_.strOpt)
                EmailSuppression.Event(
                  identity("Bounce", messageId, obj, address),
                  address,
                  SuppressionReason.Bounce,
                  permanent,
                  diagnostic.orElse(
                    subType.map(sub => s"bounce ${obj.get("bounceType").flatMap(_.strOpt).getOrElse("")}/$sub".trim)
                  )
                )
            }
        }

    /* Always permanent. A complaint is the person, not the mailbox: there is nothing to retry and
     * nothing that expires. */
    private def complaints(messageId: Option[String], complaint: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        complaint.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val feedback = obj.get("complaintFeedbackType").flatMap(_.strOpt)
            recipients(obj.get("complainedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                EmailSuppression.Event(
                  identity("Complaint", messageId, obj, address),
                  address,
                  SuppressionReason.Complaint,
                  true,
                  feedback.orElse(diagnostic)
                )
            }
        }

    /* Never permanent, and not even a failure yet: SES is still trying. Counted because three of
     * them in a week is a mailbox that is not accepting our mail, whatever the reason given. */
    private def delays(messageId: Option[String], delay: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        delay.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val reason = obj.get("delayType").flatMap(_.strOpt)
            recipients(obj.get("delayedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                EmailSuppression.Event(
                  identity("DeliveryDelay", messageId, obj, address),
                  address,
                  SuppressionReason.Delay,
                  false,
                  reason.orElse(diagnostic)
                )
            }
        }

    /** What makes two receives of one event the same event, and two different events different.
      *
      * The threshold in `email_suppression` counts transient failures, and SQS is at-least-once: a visibility timeout
      * that expires while this function is still writing, a function that times out, a retry after a partial-batch
      * failure. Any of those delivers a notification whose effects are already recorded. Counting it twice would
      * suppress an address after two real failures instead of three, which is a reachable player silenced by the
      * queue's ordinary behaviour rather than by anything wrong with their mailbox.
      *
      * So the id is computed from what SES itself generated, and from nothing the queue or SNS added:
      *
      *   - the event type, since one mail can produce a delay and then a bounce;
      *   - the `mail.messageId`, which identifies the mail SES sent;
      *   - the recipient, since one notification can name several and each is a row;
      *   - and the event's own discriminator: `feedbackId` for a bounce or a complaint, and the event `timestamp` for a
      *     delivery delay, which has no feedback id. Neither repeats, so a *second* genuine delay about the same mail
      *     is a different id and is counted, while a redelivery of the first is not.
      *
      * Hashed rather than concatenated so that the column has a fixed width and no separator can appear inside a
      * component — an address may contain almost anything, and "a@b|c" and "a@b" "|c" must not collide.
      *
      * The fallback, when a document carries neither a feedback id nor a timestamp, is the whole document: two events
      * that are byte-identical and carry nothing to tell them apart are indistinguishable to us as well, so treating
      * them as one is the only answer available, and is the safe direction — it under-counts rather than suppressing
      * somebody who is reachable.
      */
    private def identity(
        kind: String,
        messageId: Option[String],
        detail: scala.collection.Map[String, ujson.Value],
        recipient: String
    ): String = {
        val discriminator = detail
            .get("feedbackId")
            .flatMap(_.strOpt)
            .orElse(detail.get("timestamp").flatMap(_.strOpt))
            .getOrElse(ujson.write(ujson.Obj.from(detail.toSeq)))

        val digest = MessageDigest.getInstance("SHA-256")
        // NUL-separated, which cannot occur in any of the parts, so the join is unambiguous before
        // it is hashed.
        val parts = Seq(kind, messageId.getOrElse(""), recipient.toLowerCase, discriminator)
        digest
            .digest(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8))
            .map(byte => String.format("%02x", Integer.valueOf(byte & 0xff)))
            .mkString
    }

    /* The `emailAddress` of each entry, with whatever it said about why. An entry with no address
     * is skipped: there is nothing to key a row by. */
    private def recipients(list: Option[ujson.Value], diagnosticKey: String): Seq[(String, Option[String])] =
        list.flatMap(_.arrOpt).toSeq.flatten.flatMap { entry =>
            for {
                obj <- entry.objOpt
                address <- obj.get("emailAddress").flatMap(_.strOpt)
                trimmed = address.trim
                if trimmed.nonEmpty
            } yield (trimmed, obj.get(diagnosticKey).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty))
        }
}
