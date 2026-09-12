package com.vivi.matchmaker.notify

import cats.effect.IO

/** A notifier that keeps what it was given instead of sending it.
  *
  * The queue is the one part of notification no test can stand up, so it is stubbed the way the
  * game engine is: what matters here is which mails were produced and what they say, not that
  * SQS accepted them.
  */
class RecordingNotifier(fail: Boolean = false) extends Notifier {
  @volatile private var sent: List[MailMessage] = Nil

  def enqueue(message: MailMessage): IO[Unit] =
    if (fail) IO.raiseError(new RuntimeException("queue is down"))
    else IO(synchronized { sent = sent :+ message })

  def messages: List[MailMessage] = synchronized(sent)

  def recipients: Set[String] = messages.map(_.recipient).toSet
}
