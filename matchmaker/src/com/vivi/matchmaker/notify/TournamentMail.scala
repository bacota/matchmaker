package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.NotificationType

/** What has just happened in a tournament, for its owner: which tournament, of which game, and the round that is over.
  */
case class TournamentNews(gameName: String, tournamentName: String, round: Int)

/** The mails about a tournament (V53). One kind so far: a round is over and the next waits on its owner.
  *
  * Pure, like the other templates. Who is written to is `TournamentRounds`.
  */
object TournamentMail extends NotificationMail[TournamentNews] {

    protected def lines(kind: NotificationType, news: TournamentNews): Option[(String, String)] = kind match {
        case NotificationType.TournamentRoundDone =>
            val values =
                Seq("game" -> news.gameName, "tournament" -> news.tournamentName, "round" -> news.round.toString)
            Some(
              (
                MailTemplates.render("mail.tournament.roundDone.subject", values*),
                MailTemplates.render("mail.tournament.roundDone.body", values*)
              )
            )
        case _ => None
    }
}
