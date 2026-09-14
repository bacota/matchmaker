package com.vivi.matchmaker.notify

import java.time.Instant
import com.vivi.matchmaker.model.NotificationType

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
        val which =
            MailText.described(news.description).fold(s"your $name match")(quoted => s"your $name match $quoted")

        kind match {
            // The mail that introduces a match: what started, who is in it, whether the recipient is
            // the one everybody is waiting for, and by when. Every other mail here can assume the
            // player knows what the match is, because this one told them.
            case NotificationType.MatchStarted =>
                val opponents =
                    if (news.others.isEmpty) "You are the only player."
                    else s"Playing with you: ${news.others.mkString(", ")}."

                val turn =
                    if (news.yourTurn)
                        news.due.fold("It is your turn.")(by =>
                            s"It is your turn, and it is due by ${MailText.at(by)}."
                        )
                    else "You will be told when it is your turn."

                Some(
                  (
                    s"Your $name match has started",
                    s"""${started(news)}
                     |
                     |$opponents
                     |$turn""".stripMargin
                  )
                )

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

    /* The challenger's own words when they wrote any, since that is what a player recognises their
     * challenge by, and the game's name alone when they did not. Its own phrasing rather than the
     * `which` above, because this sentence announces the match where the others refer back to it. */
    private def started(news: MatchNews): String =
        MailText
            .described(news.description)
            .fold(s"Your match of ${news.gameName} has started.")(quoted =>
                s"Your match of ${news.gameName} has started: $quoted."
            )
}
