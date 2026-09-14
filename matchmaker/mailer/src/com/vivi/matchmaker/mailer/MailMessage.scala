package com.vivi.matchmaker.mailer

import upickle.default.{ReadWriter, macroRW}

/** A queued mail, as this function reads one off the queue.
  *
  * A restatement of matchmaker's `notify.MailMessage`, not a shared class. The rule the engines follow for their wire
  * types applies here for the same reason: this function is packaged and deployed on its own, and depending on
  * matchmaker to name four strings would drag skunk, cats-effect and the whole model into a jar whose only job is to
  * call SES.
  *
  * The cost of restating it is that the two can drift, so `ProtocolSpec` reads each side's JSON with the other side's
  * class — which is the check that actually matters, since a mail that cannot be decoded here is one that goes round
  * the queue three times and dies in the DLQ.
  */
case class MailMessage(sender: String, recipient: String, subject: String, body: String)

object MailMessage {
    given ReadWriter[MailMessage] = macroRW
}
