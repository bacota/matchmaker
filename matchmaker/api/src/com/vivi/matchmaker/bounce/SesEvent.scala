package com.vivi.matchmaker.bounce

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
  * Anything that cannot be read is no events rather than an error. A notification we cannot parse is one we will not
  * parse on the next attempt either, so failing it would only send it round the queue to its dead-letter queue three
  * receives later — see the same argument in the mailer's `Handler.records`.
  */
object SesEvent {

    /** Every address one notification says something about.
      *
      * Several, because one mail can have several recipients and SES reports the ones that failed. Matchmaker sends to
      * one address per mail today, so this is in practice one — but the field is a list in the document, and reading
      * only its head is the kind of shortcut that quietly drops a suppression the day that changes.
      */
    def suppressions(notification: ujson.Value): Seq[EmailSuppression.Event] =
        try {
            val obj = notification.obj
            val kind = obj.get("eventType").orElse(obj.get("notificationType")).flatMap(_.strOpt).getOrElse("")

            kind match {
                case "Bounce"        => bounces(obj.get("bounce"))
                case "Complaint"     => complaints(obj.get("complaint"))
                case "DeliveryDelay" => delays(obj.get("deliveryDelay"))

                // Send, Delivery, Open, Click, Reject, Rendering Failure, Subscription: all either
                // good news or news about us rather than about the address. None of them is a reason
                // to stop writing to somebody, and a configuration set that is subscribed to more
                // than we asked for should be ignored rather than misread.
                case _ => Nil
            }
        } catch {
            case NonFatal(_) => Nil
        }

    /* `bounceType` is Permanent, Transient or Undetermined, and only the first means the mailbox is
     * gone for good.
     *
     * Undetermined is treated as transient deliberately. SES uses it when the remote server said
     * something it could not classify, which is at least as often a badly behaved mail server as a
     * dead address -- and the cost of guessing wrong in this direction is two more bounces before
     * the threshold suppresses it anyway, where guessing wrong in the other direction is a playing
     * player who silently stops hearing from us. */
    private def bounces(bounce: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        bounce.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val permanent = obj.get("bounceType").flatMap(_.strOpt).contains("Permanent")
            recipients(obj.get("bouncedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                val subType = obj.get("bounceSubType").flatMap(_.strOpt)
                EmailSuppression.Event(
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
    private def complaints(complaint: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        complaint.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val feedback = obj.get("complaintFeedbackType").flatMap(_.strOpt)
            recipients(obj.get("complainedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                EmailSuppression.Event(address, SuppressionReason.Complaint, true, feedback.orElse(diagnostic))
            }
        }

    /* Never permanent, and not even a failure yet: SES is still trying. Counted because three of
     * them in a week is a mailbox that is not accepting our mail, whatever the reason given. */
    private def delays(delay: Option[ujson.Value]): Seq[EmailSuppression.Event] =
        delay.flatMap(_.objOpt).toSeq.flatMap { obj =>
            val reason = obj.get("delayType").flatMap(_.strOpt)
            recipients(obj.get("delayedRecipients"), "diagnosticCode").map { (address, diagnostic) =>
                EmailSuppression.Event(address, SuppressionReason.Delay, false, reason.orElse(diagnostic))
            }
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
