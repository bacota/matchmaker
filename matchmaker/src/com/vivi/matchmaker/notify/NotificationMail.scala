package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.{NotificationType, Player}

/** A template for the mails about one part of a player's game: several kinds of news, one recipient at a time.
  *
  * [[ChallengeMail]] and [[MatchMail]] had the same nine lines of `compose` between them, differing only in the type of
  * the news and in whether there was a game to link to. Those nine lines carry two rules that must not come apart:
  *
  *   - a player with no address gets nothing. `player.email` is nullable precisely so that this question has an answer,
  *     and it is answered here rather than in each caller so that a new kind of notification cannot forget it.
  *   - a kind this template is not for produces nothing rather than a guess. `lines` answers `None`, and a caller that
  *     has chosen the wrong template has chosen wrong — a mail invented from the wrong facts is worse than no mail.
  *
  * So what a subclass writes is only the part that is actually different: the subject and body for each kind it covers.
  *
  * @tparam News
  *   what happened, as that template's kinds need it. One type per template rather than one shared one, because the
  *   facts behind "somebody accepted your challenge" and "it is your turn" have nothing in common but the game's name —
  *   and a single case class holding the union of them would be mostly empty at every call site.
  */
trait NotificationMail[News] {

    /** The one mail this recipient gets, for the kind that was chosen for them.
      *
      * `final`, because a template that needed to compose differently would be a template these two rules did not apply
      * to, and that is a decision to make here rather than to override quietly.
      */
    final def compose(
        sender: String,
        uiBaseUrl: String,
        recipient: Player,
        kind: NotificationType,
        news: News
    ): Option[MailMessage] =
        recipient.email.flatMap { address =>
            lines(kind, news).map { case (subject, body) =>
                MailMessage(
                  sender = sender,
                  recipient = address,
                  subject = subject,
                  body = MailText.letter(recipient, body, uiBaseUrl, playUrl(news))
                )
            }
        }

    /** The subject and the body's own paragraphs for one kind, or `None` for a kind this template does not cover.
      *
      * Pure, and the whole of what a template is: no database, no match, no challenge — which is what lets what the
      * mail says be tested directly.
      */
    protected def lines(kind: NotificationType, news: News): Option[(String, String)]

    /** Where the game itself is played, when this news is about a game there is one for. Nothing by default: most
      * notifications are about a challenge that is not a match yet, or a match that is over.
      */
    protected def playUrl(news: News): Option[String] = None
}
