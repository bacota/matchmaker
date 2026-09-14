package com.vivi.matchmaker.notify

import java.time.Instant
import com.vivi.matchmaker.model.{NotificationType, Player}

/** How a match came to an end, in the words the players are told it in.
  *
  * Three cases rather than a boolean, because a match ends in three quite different ways and the difference is the
  * whole content of the mail: the game finished, its creator called it off, or somebody's clock ran out.
  */
enum MatchEnding(val summary: String) {
    case Finished extends MatchEnding("The game is over.")
    case Cancelled extends MatchEnding("Its creator has called it off. The game board itself stays open.")
    case Forfeited
        extends MatchEnding("A turn ran out of time, so the match ended on a forfeit — see the result for who won.")
}

/** What has just happened in a match, for everyone in it.
  *
  * @param mover
  *   the nickname of whoever has just moved, when that is what happened
  * @param nextUp
  *   the nicknames of whoever it is now the turn of, in seat order
  * @param due
  *   when the recipient's own turn runs out, if it is their turn and the match is played on a clock
  * @param ending
  *   how the match ended, when it has
  */
case class MatchNews(
    gameName: String,
    description: String,
    mover: Option[String] = None,
    nextUp: Seq[String] = Seq.empty,
    due: Option[Instant] = None,
    playUrl: Option[String] = None,
    ending: Option[MatchEnding] = None
)

/** What a player is told about a match they are playing, once it has started.
  *
  * The three kinds that are about play rather than about getting a match together: somebody moved, it is your turn, and
  * it is over. The fourth, a match beginning, is [[MatchStartedMail]] — kept separate because it is the one that has to
  * introduce the match rather than report on it.
  *
  * Pure, like the other templates. Who is written to is `GameEngineService` and `MatchService`.
  */
object MatchMail {

    /** The one mail this recipient gets, for the kind that was chosen for them. `None` when they have no address, or
      * when the kind is not one of this template's three.
      *
      * `due` is the recipient's own deadline, so this takes one recipient at a time rather than composing for a roster:
      * the same move produces a different sentence for the player who now has to answer it.
      */
    def compose(
        sender: String,
        uiBaseUrl: String,
        recipient: Player,
        kind: NotificationType,
        news: MatchNews
    ): Option[MailMessage] =
        recipient.email.flatMap { address =>
            lines(kind, news).map { case (subject, body) =>
                MailMessage(
                  sender = sender,
                  recipient = address,
                  subject = subject,
                  body = s"""Hello ${recipient.nickname},
                   |
                   |$body
                   |
                   |${MailText.links(uiBaseUrl, news.playUrl)}
                   |""".stripMargin
                )
            }
        }

    private def lines(kind: NotificationType, news: MatchNews): Option[(String, String)] = {
        val name = news.gameName
        val which =
            MailText.described(news.description).fold(s"your $name match")(quoted => s"your $name match $quoted")

        kind match {
            // The plainer of the two turn mails, and the one a player gets when it is somebody
            // else's move: it says who moved and who is holding things up now, which between them
            // are the only two facts a spectator of their own match can act on.
            case NotificationType.TurnTaken =>
                val moved = news.mover.fold("A turn has been taken")(who => s"$who has taken a turn")
                val waiting =
                    if (news.nextUp.isEmpty) "Nobody is listed as being up next."
                    else s"It is now ${MailText.and(news.nextUp)}'s turn."
                Some(
                  (s"${news.mover.getOrElse("Someone")} has moved in your $name match", s"$moved in $which.\n$waiting")
                )

            // The one notification in the whole set that is asking the player to do something, so it
            // says the deadline if there is one and leads with the link to the board.
            case NotificationType.YourTurn =>
                val deadline = news.due.fold("")(by => s" It is due by ${MailText.at(by)}.")
                val moved = news.mover.fold("")(who => s"$who has moved, and ")
                Some(
                  (
                    s"It is your turn in your $name match",
                    s"${moved}it is your turn in $which.$deadline"
                  )
                )

            case NotificationType.MatchEnded =>
                val how = news.ending.getOrElse(MatchEnding.Finished)
                Some((s"Your $name match is over", s"$which has ended.\n\n${how.summary}"))

            case _ => None
        }
    }
}
