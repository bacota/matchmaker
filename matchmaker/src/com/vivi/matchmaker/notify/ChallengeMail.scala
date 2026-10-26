package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.NotificationType

/** What has just happened to the entries of a challenge or tournament, from the point of view of everyone who has a
  * stake in it.
  *
  * One value for the whole audience rather than one per recipient: an entry is a single event, and the difference
  * between what the owner is told and what the other entrants are told is which of them is reading — which is `kind` in
  * [[ChallengeMail.compose]], not a different set of facts.
  *
  * @param what
  *   "challenge" for a tournament of the challenge model, "tournament" for any other
  * @param title
  *   a tournament's name, quoted in the mail; empty for a challenge, whose name only says that it is one
  * @param actor
  *   the nickname of whoever has just entered or withdrawn — or, for the invitation kinds, whoever took up or turned
  *   down an invitation. Unused by [[NotificationType.InvitationReceived]], whose recipient *is* the invitee and so is
  *   not named to themselves
  * @param joined
  *   true for an entry, false for a withdrawal
  * @param challenger
  *   the nickname of its owner, for the mails that are not addressed to them
  * @param room
  *   how many more entries it takes before it is full, for one that takes a limited number; `None` for one that does
  *   not. `Some(0)` is full.
  * @param isReady
  *   whether this entry has just filled it and it waits on its owner to start it
  */
case class ChallengeNews(
    gameName: String,
    what: String,
    title: String,
    actor: String,
    isJoined: Boolean,
    challenger: String,
    room: Option[Int],
    isReady: Boolean = false
)

/** What a player is told about the entries in, or invitations to, a challenge or tournament.
  *
  * Four of the nine notification kinds land here. One is about the entries, and is told to the owner: somebody entered
  * or withdrew, and, when that entry filled it, that it can now be started.
  *
  * The other three are the invitation kinds: an invitation is made, taken up, or turned down, and the first of those is
  * the only mail here addressed to somebody who is not yet in it at all.
  *
  * Rendering is separated from deciding who to write to, and is a pure function of its arguments, so what the mail says
  * can be tested without a tournament or a database. The deciding half is `Notifications`.
  */
object ChallengeMail extends NotificationMail[ChallengeNews] {

    /* No play link on any of these: nothing is a match yet, so there is nothing to play at.
     * `NotificationMail.playUrl` defaults to none, which is why it is not overridden here. */
    protected def lines(kind: NotificationType, news: ChallengeNews): Option[(String, String)] = {
        val name = news.gameName
        val what = news.what
        // Empty for a challenge, which is how the clause disappears from whichever sentence it sits inside.
        val quoted = MailText.quoted(news.title)

        // How far it is from full. The mail that says it is full says it in its own words, so this is only the
        // other half -- and nothing at all for one that takes any number.
        val roster = news.room match {
            case Some(0)     => MailTemplates.render("mail.challenge.roster.full")
            case Some(count) => MailTemplates.render("mail.challenge.roster.room", "count" -> count.toString)
            case None        => ""
        }

        kind match {
            // To the owner, about somebody else's decision. The roster line matters most here: they are the one
            // who can start it, so what they need to know is how far off that is -- and, when this entry has
            // filled it, that it is now theirs to start.
            case NotificationType.CompetitionEntered if news.isReady =>
                Some(
                  (
                    MailTemplates.render("mail.challenge.ready.subject", "game" -> name, "what" -> what),
                    MailTemplates.render(
                      "mail.challenge.ready.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted
                    )
                  )
                )

            case NotificationType.CompetitionEntered =>
                val prefix = if (news.isJoined) "mail.challenge.accepted" else "mail.challenge.withdrawn"
                Some(
                  (
                    MailTemplates.render(s"$prefix.subject", "actor" -> news.actor, "game" -> name, "what" -> what),
                    MailTemplates.render(
                      s"$prefix.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted,
                      "roster" -> roster
                    )
                  )
                )

            // To the invited player, and the only mail here that its recipient did not already know they had a
            // stake in. `actor` is not used: they are the one being written to, and the owner is who the news is
            // about.
            case NotificationType.InvitationReceived =>
                Some(
                  (
                    MailTemplates
                        .render("mail.invitation.received.subject", "challenger" -> news.challenger, "game" -> name),
                    MailTemplates.render(
                      "mail.invitation.received.body",
                      "challenger" -> news.challenger,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted
                    )
                  )
                )

            // Back to the owner, with the roster line for the reason it is on CompetitionEntered.
            case NotificationType.InvitationAccepted =>
                Some(
                  (
                    MailTemplates.render("mail.invitation.accepted.subject", "actor" -> news.actor, "game" -> name),
                    MailTemplates.render(
                      "mail.invitation.accepted.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted,
                      "roster" -> roster
                    )
                  )
                )

            // Also to the owner, and no roster line: turning an invitation down changes nothing about the entries.
            // What it says instead is that it is still open, which the owner would otherwise have to go and check.
            case NotificationType.InvitationRejected =>
                Some(
                  (
                    MailTemplates.render("mail.invitation.rejected.subject", "actor" -> news.actor, "game" -> name),
                    MailTemplates.render(
                      "mail.invitation.rejected.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted
                    )
                  )
                )

            case _ => None
        }
    }
}
