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
  */
case class ChallengeNews(
    gameName: String,
    what: String,
    title: String,
    actor: String,
    joined: Boolean,
    challenger: String,
    room: Option[Int]
)

/** What a player is told when a challenge or tournament they own or have entered changes.
  *
  * Seven of the twelve notification kinds land here, and they are two pairs: something happened (an entry, a
  * withdrawal) and it is now full. Each pair is said once to its owner and once to the players who entered it, because
  * those are different pieces of news — only one of them can start it.
  *
  * The other three are the invitation kinds, which are the same shape one level out: an invitation is made, taken up,
  * or turned down, and the first of those is the only mail here addressed to somebody who is not yet in it at all.
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
            // who can start it, so what they need to know is how far off that is.
            case NotificationType.ChallengeAccepted =>
                val prefix = if (news.joined) "mail.challenge.accepted" else "mail.challenge.withdrawn"
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

            // Also to the owner, and the one mail here that asks them to do something.
            case NotificationType.ChallengeReady =>
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

            // To the other entrants. They cannot start it and are not being asked to do anything, so this says
            // who they will be playing with and how far it is from full.
            case NotificationType.AcceptanceChanged =>
                val event =
                    if (news.joined)
                        MailTemplates.render("mail.challenge.changed.accepted.event", "actor" -> news.actor)
                    else MailTemplates.render("mail.challenge.changed.withdrawn.event", "actor" -> news.actor)

                val subject =
                    if (news.joined) "mail.challenge.changed.accepted.subject"
                    else "mail.challenge.changed.withdrawn.subject"

                Some(
                  (
                    MailTemplates.render(subject, "actor" -> news.actor, "game" -> name, "what" -> what),
                    MailTemplates.render(
                      "mail.challenge.changed.body",
                      "challenger" -> news.challenger,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted,
                      "event" -> event,
                      "roster" -> roster
                    )
                  )
                )

            // And to the other entrants when it fills up: nothing for them to do, but it says who they are now
            // waiting on, which is the honest answer to "when does this start?".
            case NotificationType.AcceptedChallengeReady =>
                Some(
                  (
                    MailTemplates.render("mail.challenge.acceptedReady.subject", "game" -> name, "what" -> what),
                    MailTemplates.render(
                      "mail.challenge.acceptedReady.body",
                      "challenger" -> news.challenger,
                      "game" -> name,
                      "what" -> what,
                      "quoted" -> quoted
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

            // Back to the owner, with the roster line for the reason it is on ChallengeAccepted.
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
