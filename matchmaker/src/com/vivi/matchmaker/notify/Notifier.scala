package com.vivi.matchmaker.notify

import cats.effect.IO

/** Somewhere to put a mail that something else will send.
  *
  * An interface for the same reason `GameEngineClient` is one: the thing behind it is a remote
  * system. Tests pass a recorder, the local server prints to the console, and only a deployment
  * puts anything on a queue.
  *
  * Implementations may fail, and every caller is expected to treat that as survivable — see
  * `GameEngineService.start`, which notifies after the match exists and swallows what comes back.
  */
trait Notifier {
  def enqueue(message: MailMessage): IO[Unit]
}

object Notifier {

  /** Sends nothing. The default everywhere it is not configured, including every test that has
    * no opinion about mail.
    */
  val disabled: Notifier = _ => IO.unit

  /** Prints instead of sending, for the local server: there is no queue locally, and a developer
    * wanting to see what a notification says should not have to deploy to read it.
    */
  val logging: Notifier = message =>
    IO(println(s"[mail] to ${message.recipient} from ${message.sender}: ${message.subject}\n${message.body}"))
}

/** What a notification needs that is true of the deployment rather than of the match.
  *
  * Both are `Option` and both are read from the environment, so an environment that has not been
  * given them sends nothing at all rather than sending something wrong: a mail with no `From` is
  * not a mail SES will accept, and one with no link is worse than no mail, since a notification
  * whose whole purpose is to bring someone back to the game would arrive with nowhere to go.
  */
case class MailSettings(sender: Option[String], uiBaseUrl: Option[String]) {
  def configured: Boolean = sender.isDefined && uiBaseUrl.isDefined
}

object MailSettings {
  val none: MailSettings = MailSettings(None, None)

  def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): MailSettings =
    MailSettings(
      sender = env("MAIL_SENDER").map(_.trim).filter(_.nonEmpty),
      uiBaseUrl = env("UI_BASE_URL").map(_.trim.stripSuffix("/")).filter(_.nonEmpty)
    )
}
