package com.vivi.matchmaker.notify

import com.vivi.matchmaker.model.{NotificationType, Player}

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
object ChallengeMail {

    /** The one mail this recipient gets, for the kind that was chosen for them.
      *
      * `None` when the player has no address — the same rule `MatchStartedMail` applies, kept in the template rather
      * than in each caller so that a new notification cannot forget it.
      *
      * The four kinds are the four this template is for. Anything else is not this template's business and produces
      * nothing rather than guessing: a caller that has chosen `MatchStarted` here has chosen wrong, and a mail invented
      * from the wrong facts is worse than no mail.
      */
    def compose(
        sender: String,
        uiBaseUrl: String,
        recipient: Player,
        kind: NotificationType,
        news: ChallengeNews
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
                   |${MailText.links(uiBaseUrl, None)}
                   |""".stripMargin
                )
            }
        }

    private def lines(kind: NotificationType, news: ChallengeNews): Option[(String, String)] = {
        val name = news.gameName
        val what = MailText.described(news.description).fold("")(quoted => s" $quoted")

        kind match {
            // To the challenger, about somebody else's decision. The roster line matters most here:
            // they are the one who can start it, so what they need to know is how far off that is.
            case NotificationType.ChallengeAccepted =>
                val event =
                    if (news.joined) s"${news.actor} has accepted your $name challenge$what${as(news)}."
                    else s"${news.actor} has backed out of your $name challenge$what."
                Some((s"${news.actor} has ${verb(news.joined)} your $name challenge", s"$event\n${roster(news)}"))

            // Also to the challenger, and the one mail here that asks them to do something.
            case NotificationType.ChallengeReady =>
                Some(
                  (
                    s"Your $name challenge is ready to start",
                    s"""${news.actor} has accepted your $name challenge$what${as(news)}.
                     |
                     |Every role is now taken, so you can start the match whenever you like.""".stripMargin
                  )
                )

            // To the other players who accepted. They cannot start it and are not being asked to do
            // anything, so this says who they will be playing with and what it is still waiting for.
            case NotificationType.AcceptanceChanged =>
                val event =
                    if (news.joined) s"${news.actor} has also accepted it${as(news)}."
                    else s"${news.actor} has backed out of it."
                Some(
                  (
                    s"${news.actor} has ${verb(news.joined)} a $name challenge you accepted",
                    s"""You have accepted ${news.challenger}'s $name challenge$what.
                     |
                     |$event
                     |${roster(news)}""".stripMargin
                  )
                )

            // And to the other players when it fills up: nothing for them to do, but it says who
            // they are now waiting on, which is the honest answer to "when does this start?".
            case NotificationType.AcceptedChallengeReady =>
                Some(
                  (
                    s"A $name challenge you accepted is ready to start",
                    s"""Every role in ${news.challenger}'s $name challenge$what is now taken.
                     |
                     |${news.challenger} offered it, so it is up to them to start the match.""".stripMargin
                  )
                )

            case _ => None
        }
    }

    private def verb(joined: Boolean): String = if (joined) "accepted" else "backed out of"

    /* The role somebody took, when the news is that they took one. */
    private def as(news: ChallengeNews): String = news.role.fold("")(role => s", as $role")

    /* What the challenge is still waiting for, named rather than counted: "waiting for a defender"
     * is something a player can act on, where "one role left" is something they have to go and look
     * up. Nothing when the roster is full, because the mail that says so says it in its own words. */
    private def roster(news: ChallengeNews): String =
        if (news.waitingFor.isEmpty) "Every role is now taken."
        else s"Still waiting for: ${MailText.and(news.waitingFor)}."
}
