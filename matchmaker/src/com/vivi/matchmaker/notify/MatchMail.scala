package com.vivi.matchmaker.notify

import java.time.Instant
import com.vivi.matchmaker.model.NotificationType

/** How a match came to an end.
  *
  * Three cases rather than a boolean, because a match ends in three quite different ways and the difference is the
  * whole content of the mail: the game finished, its creator called it off, or somebody's clock ran out.
  *
  * Each names the template that says so rather than carrying the sentence itself: what a player is told lives in
  * `mail/messages.properties`, like every other word matchmaker mails.
  */
enum MatchEnding(val template: String) {
    case Finished extends MatchEnding("mail.match.ended.finished")
    case Cancelled extends MatchEnding("mail.match.ended.cancelled")
    case Forfeited extends MatchEnding("mail.match.ended.forfeited")
}

/** What has just happened in a match, for everyone in it.
  *
  * Every field is defaulted, and each kind uses a handful of them: a match beginning has a roster to introduce and no
  * mover, a move has a mover and no roster. One case class rather than one per kind because they are all facts about
  * the same match, and four types over nine fields would be four types to thread through the same two call sites.
  *
  * @param mover
  *   the nickname of whoever has just moved, when that is what happened
  * @param nextUp
  *   the nicknames of whoever it is now the turn of, in seat order
  * @param due
  *   when the recipient's own turn runs out, if it is their turn and the match is played on a clock
  * @param others
  *   the recipient's fellow players, in seat order. For a match beginning, which is the one mail that has to introduce
  *   who is in it — after that everyone knows.
  * @param yourTurn
  *   whether it is the recipient's turn. Also for a match beginning, where whose turn it is comes as part of the
  *   introduction rather than as the news: the `YourTurn` kind *is* that news, and needs no flag to say so.
  * @param ending
  *   how the match ended, when it has
  */
case class MatchNews(
    gameName: String,
    description: String,
    mover: Option[String] = None,
    nextUp: Seq[String] = Seq.empty,
    due: Option[Instant] = None,
    others: Seq[String] = Seq.empty,
    yourTurn: Boolean = false,
    playUrl: Option[String] = None,
    ending: Option[MatchEnding] = None
)

/** What a player is told about a match: that it has begun, that somebody has moved, that it is their turn, that it is
  * over.
  *
  * All four kinds that need a match to exist. The first of them used to be a template of its own, on the grounds that
  * it introduces a match rather than reporting an event in one — which is true of what it says, and turned out not to
  * be true of anything else about it: the same recipient, the same layout, the same two rules, and a caller that had
  * already chosen a `NotificationType` to get there.
  *
  * Pure, like the other templates. Who is written to is `GameEngineService` and `MatchService`.
  */
object MatchMail extends NotificationMail[MatchNews] {

    /* The engine's own link for the match, when the news is about one still being played. `MatchNews`
     * carries none for an ending, which is how a mail about a finished match comes to have no "Play"
     * line -- see `MatchNotifications.ended`. */
    override protected def playUrl(news: MatchNews): Option[String] = news.playUrl

    /* `news.due` is the recipient's own deadline, which is why a mail is composed one recipient at a
     * time rather than for a roster: the same move produces a different sentence for the player who
     * now has to answer it. */
    protected def lines(kind: NotificationType, news: MatchNews): Option[(String, String)] = {
        val name = news.gameName
        // How every mail after the first refers back to the match: the challenger's own description
        // when there is one, which is what a player recognises it by.
        val quoted = MailText.quoted(news.description)
        val which = MailTemplates.render("mail.match.which", "game" -> name, "quoted" -> quoted)

        kind match {
            // The mail that introduces a match: what started, who is in it, whether the recipient is
            // the one everybody is waiting for, and by when.
            case NotificationType.MatchStarted =>
                val opening =
                    if (quoted.isEmpty) MailTemplates.render("mail.match.started.opening", "game" -> name)
                    else
                        MailTemplates
                            .render("mail.match.started.opening.described", "game" -> name, "quoted" -> quoted)

                val opponents =
                    if (news.others.isEmpty) MailTemplates.render("mail.match.started.alone")
                    else MailTemplates.render("mail.match.started.opponents", "others" -> news.others.mkString(", "))

                val turn =
                    if (!news.yourTurn) MailTemplates.render("mail.match.started.turn.later")
                    else
                        news.due.fold(MailTemplates.render("mail.match.started.turn.now"))(by =>
                            MailTemplates.render("mail.match.started.turn.due", "due" -> MailText.at(by))
                        )

                Some(
                  (
                    MailTemplates.render("mail.match.started.subject", "game" -> name),
                    MailTemplates.render(
                      "mail.match.started.body",
                      "opening" -> opening,
                      "opponents" -> opponents,
                      "turn" -> turn
                    )
                  )
                )

            // The plainer of the two turn mails, and the one a player gets when it is somebody
            // else's move: it says who moved and who is holding things up now, which between them
            // are the only two facts a spectator of their own match can act on.
            case NotificationType.TurnTaken =>
                val moved = news.mover.fold(
                  MailTemplates.render("mail.match.turn.moved.unknown", "which" -> which)
                )(who => MailTemplates.render("mail.match.turn.moved", "mover" -> who, "which" -> which))

                val next =
                    if (news.nextUp.isEmpty) MailTemplates.render("mail.match.turn.next.unknown")
                    else MailTemplates.render("mail.match.turn.next", "next" -> MailText.and(news.nextUp))

                Some(
                  (
                    MailTemplates.render(
                      "mail.match.turn.subject",
                      "mover" -> news.mover.getOrElse(MailTemplates.render("mail.match.turn.mover.unknown")),
                      "game" -> name
                    ),
                    MailTemplates.render("mail.match.turn.body", "moved" -> moved, "next" -> next)
                  )
                )

            // The one notification in the whole set that asks the player to do something, so it says
            // the deadline if there is one.
            case NotificationType.YourTurn =>
                val deadline =
                    news.due.fold("")(by =>
                        MailTemplates.render("mail.match.yourTurn.deadline", "due" -> MailText.at(by))
                    )

                val body = news.mover.fold(
                  MailTemplates.render("mail.match.yourTurn.body", "which" -> which, "deadline" -> deadline)
                )(who =>
                    MailTemplates.render(
                      "mail.match.yourTurn.body.moved",
                      "mover" -> who,
                      "which" -> which,
                      "deadline" -> deadline
                    )
                )

                Some((MailTemplates.render("mail.match.yourTurn.subject", "game" -> name), body))

            case NotificationType.MatchEnded =>
                val how = news.ending.getOrElse(MatchEnding.Finished)
                Some(
                  (
                    MailTemplates.render("mail.match.ended.subject", "game" -> name),
                    MailTemplates.render(
                      "mail.match.ended.body",
                      "which" -> which,
                      "how" -> MailTemplates.render(how.template)
                    )
                  )
                )

            case _ => None
        }
    }
}
