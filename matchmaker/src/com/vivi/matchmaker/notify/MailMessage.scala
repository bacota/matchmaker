package com.vivi.matchmaker.notify

import upickle.default.{ReadWriter, macroRW}

/** One email, as it travels from whatever decided to send it to whatever actually sends it.
  *
  * Four fields and nothing else — no match id, no player id, no template name, no kind. That is the whole design: the
  * queue and the function draining it know nothing about games, so "your turn has come round" and "your match is over"
  * need no change to either. Whoever enqueues has already decided what the mail says; the mailer's only job is
  * delivery.
  *
  * `sender` travels in the message rather than being configured on the mailer for the same reason. It is a decision
  * about a particular mail — the address a reply should reach, which may one day differ between a notification and an
  * invitation — and the mailer is not the place that knows which is which. It must still be an identity SES will send
  * from.
  *
  * One recipient per message, not a list: a message is the unit of delivery, of retry and of failure, and an address
  * that bounces should not take four good ones down with it.
  */
case class MailMessage(sender: String, recipient: String, subject: String, body: String)

object MailMessage {
    given ReadWriter[MailMessage] = macroRW
}
