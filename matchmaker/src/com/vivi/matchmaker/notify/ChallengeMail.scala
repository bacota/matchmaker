package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.NotificationType

/** What has just happened to a challenge, from the point of view of everyone who has a stake in it.
  *
  * One value for the whole audience rather than one per recipient: an acceptance is a single event, and the difference
  * between what the challenger is told and what the other acceptors are told is which of them is reading — which is
  * `kind` in [[ChallengeMail.compose]], not a different set of facts.
  *
  * @param actor
  *   the nickname of whoever has just accepted or backed out
  * @param role
  *   the role they took, when they took one. A withdrawal says nothing about the role it freed: the roster line below
  *   already names it whenever it is a role a start waits for, and naming an optional one adds nothing.
  * @param joined
  *   true for an acceptance, false for a withdrawal
  * @param challenger
  *   the nickname of whoever offered the challenge, for the mails that are not addressed to them
  * @param waitingFor
  *   the required roles still unclaimed, in the game's role order. Empty means the challenge is ready to start.
  */
case class ChallengeNews(
    gameName: String,
    description: String,
    actor: String,
    role: Option[String],
    joined: Boolean,
    challenger: String,
    waitingFor: Seq[String]
)

/** What a player is told when a challenge they are in changes.
  *
  * Four of the eight notification kinds land here, and they are two pairs: something happened (an acceptance, a
  * withdrawal) and the roster is now complete. Each pair is said once to the player who offered the challenge and once
  * to the players who accepted it, because those are different pieces of news — one of them can press Start.
  *
  * Rendering is separated from deciding who to write to, and is a pure function of its arguments, so what the mail says
  * can be tested without a challenge or a database. The deciding halves are `OpenChallengeService.accept` and
  * `AcceptanceService.delete`.
  */
object ChallengeMail extends NotificationMail[ChallengeNews] {

    /* No play link on any of these four: a challenge is not a match yet, so there is nothing to play
     * at. `NotificationMail.playUrl` defaults to none, which is why it is not overridden here. */
    protected def lines(kind: NotificationType, news: ChallengeNews): Option[(String, String)] = {
        val name = news.gameName
        // Empty when the challenger wrote no description, which is how the clause disappears from
        // whichever sentence it sits inside.
        val quoted = MailText.quoted(news.description)
        // The part they took, for an acceptance; a withdrawal has no row left to read one from.
        val role = news.role.fold("")(taken => MailTemplates.render("mail.role", "role" -> taken))

        // What the challenge is still waiting for, named rather than counted. The mail that says the
        // roster is full says it in its own words, so this is only the other half.
        val roster =
            if (news.waitingFor.isEmpty) MailTemplates.render("mail.challenge.roster.full")
            else MailTemplates.render("mail.challenge.roster.waiting", "roles" -> MailText.and(news.waitingFor))

        kind match {
            // To the challenger, about somebody else's decision. The roster line matters most here:
            // they are the one who can start it, so what they need to know is how far off that is.
            case NotificationType.ChallengeAccepted =>
                val prefix = if (news.joined) "mail.challenge.accepted" else "mail.challenge.withdrawn"
                Some(
                  (
                    MailTemplates.render(s"$prefix.subject", "actor" -> news.actor, "game" -> name),
                    MailTemplates.render(
                      s"$prefix.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "quoted" -> quoted,
                      "role" -> role,
                      "roster" -> roster
                    )
                  )
                )

            // Also to the challenger, and the one mail here that asks them to do something.
            case NotificationType.ChallengeReady =>
                Some(
                  (
                    MailTemplates.render("mail.challenge.ready.subject", "game" -> name),
                    MailTemplates.render(
                      "mail.challenge.ready.body",
                      "actor" -> news.actor,
                      "game" -> name,
                      "quoted" -> quoted,
                      "role" -> role
                    )
                  )
                )

            // To the other players who accepted. They cannot start it and are not being asked to do
            // anything, so this says who they will be playing with and what it is still waiting for.
            case NotificationType.AcceptanceChanged =>
                val event =
                    if (news.joined)
                        MailTemplates
                            .render("mail.challenge.changed.accepted.event", "actor" -> news.actor, "role" -> role)
                    else MailTemplates.render("mail.challenge.changed.withdrawn.event", "actor" -> news.actor)

                val subject =
                    if (news.joined) "mail.challenge.changed.accepted.subject"
                    else "mail.challenge.changed.withdrawn.subject"

                Some(
                  (
                    MailTemplates.render(subject, "actor" -> news.actor, "game" -> name),
                    MailTemplates.render(
                      "mail.challenge.changed.body",
                      "challenger" -> news.challenger,
                      "game" -> name,
                      "quoted" -> quoted,
                      "event" -> event,
                      "roster" -> roster
                    )
                  )
                )

            // And to the other players when it fills up: nothing for them to do, but it says who
            // they are now waiting on, which is the honest answer to "when does this start?".
            case NotificationType.AcceptedChallengeReady =>
                Some(
                  (
                    MailTemplates.render("mail.challenge.acceptedReady.subject", "game" -> name),
                    MailTemplates.render(
                      "mail.challenge.acceptedReady.body",
                      "challenger" -> news.challenger,
                      "game" -> name,
                      "quoted" -> quoted
                    )
                  )
                )

            case _ => None
        }
    }
}
