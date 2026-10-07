package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.NotificationType

/** What has happened to a tournament: its name, its game, the round, and who invited the reader. */
case class TournamentNews(gameName: String, tournament: String, round: Int = 0, owner: String = "")

/** What a player is told about a tournament (V54): that a round of one they run is over, and that they have been
  * invited to one. Pure, as every template is; the deciding halves are `TournamentPlayService` and
  * `TournamentService.invite`.
  */
object TournamentMail extends NotificationMail[TournamentNews] {

    protected def lines(kind: NotificationType, news: TournamentNews): Option[(String, String)] =
        kind match {
            case NotificationType.TournamentRoundComplete =>
                Some(
                  (
                    MailTemplates.render(
                      "mail.tournament.round.subject",
                      "tournament" -> news.tournament,
                      "round" -> news.round.toString
                    ),
                    MailTemplates.render(
                      "mail.tournament.round.body",
                      "tournament" -> news.tournament,
                      "game" -> news.gameName,
                      "round" -> news.round.toString
                    )
                  )
                )
            case NotificationType.TournamentInvitation =>
                Some(
                  (
                    MailTemplates.render(
                      "mail.tournament.invitation.subject",
                      "owner" -> news.owner,
                      "tournament" -> news.tournament
                    ),
                    MailTemplates.render(
                      "mail.tournament.invitation.body",
                      "owner" -> news.owner,
                      "tournament" -> news.tournament,
                      "game" -> news.gameName
                    )
                  )
                )
            case _ => None
        }
}
