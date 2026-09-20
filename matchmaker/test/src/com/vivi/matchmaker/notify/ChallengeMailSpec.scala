package com.vivi.matchmaker.notify

import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the seven mails about a challenge actually say.
  *
  * Pure, like `MatchMailSpec`, and for the same reason: the decisions worth pinning down — who the mail is addressed as
  * being about, whether it asks the player to do anything, what it says is still missing — are all made here, with no
  * challenge and no database in sight.
  *
  * Four are about an acceptance and three about an invitation (V22). The invitation three are the same shape one level
  * out — an offer is made, taken up, or turned down — and what is worth a test of its own in each is the thing that
  * makes it *not* the acceptance mail beside it: who it is addressed to, and whether the roster is any of their
  * business.
  */
class ChallengeMailSpec extends FunSuite {

    private def player(nickname: String, email: Option[String] = Some("player@example.com")) =
        Player(PlayerId(1), nickname, isAdmin = false, "sub-1", email)

    private def news(
        joined: Boolean = true,
        role: Option[String] = Some("defender"),
        waitingFor: Seq[String] = Seq.empty,
        description: String = "friendly game"
    ) =
        ChallengeNews(
          gameName = "Tic-Tac-Toe",
          description = description,
          actor = "bob",
          role = role,
          joined = joined,
          challenger = "carol",
          waitingFor = waitingFor
        )

    private def compose(kind: NotificationType, news: ChallengeNews, recipient: Player = player("alice")) =
        ChallengeMail.compose("matchmaker@example.com", "https://matchmaker.example.com", recipient, kind, news)

    test("a player with no address gets nothing") {
        assertEquals(
          compose(NotificationType.ChallengeAccepted, news(), player("alice", None)),
          None
        )
    }

    test("the challenger is told who accepted, in what role, and what is still missing") {
        val mail = compose(NotificationType.ChallengeAccepted, news(waitingFor = Seq("attacker"))).get

        assertEquals(mail.sender, "matchmaker@example.com")
        assertEquals(mail.recipient, "player@example.com")
        assertEquals(mail.subject, "bob has accepted your Tic-Tac-Toe challenge")
        assert(mail.body.contains("Hello alice,"))
        assert(mail.body.contains("bob has accepted your Tic-Tac-Toe challenge \"friendly game\", as defender."))
        assert(mail.body.contains("Still waiting for: attacker."))
        assert(mail.body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    // A withdrawal says nothing about the role it freed: the roster line names it whenever it is a
    // role a start waits for, and there is no acceptance row left to read it from anyway.
    test("the challenger is told who backed out, without a role") {
        val mail = compose(NotificationType.ChallengeAccepted, news(joined = false, role = None)).get

        assertEquals(mail.subject, "bob has backed out of your Tic-Tac-Toe challenge")
        assert(mail.body.contains("bob has backed out of your Tic-Tac-Toe challenge \"friendly game\"."))
        assert(!mail.body.contains("as defender"))
    }

    // The one mail of the seven that asks the player to do something.
    test("the challenger is asked to start it once the roster is full") {
        val mail = compose(NotificationType.ChallengeReady, news()).get

        assertEquals(mail.subject, "Your Tic-Tac-Toe challenge is ready to start")
        assert(mail.body.contains("Every role is now taken, so you can start the match whenever you like."))
    }

    // To the other acceptors, who cannot start it: named by whoever offered it, because that is how
    // they know which challenge this is -- it is not theirs.
    test("another acceptor is told whose challenge it is and who has joined it") {
        val mail = compose(NotificationType.AcceptanceChanged, news(waitingFor = Seq("attacker", "healer"))).get

        assertEquals(mail.subject, "bob has accepted a Tic-Tac-Toe challenge you accepted")
        assert(mail.body.contains("You have accepted carol's Tic-Tac-Toe challenge \"friendly game\"."))
        assert(mail.body.contains("bob has also accepted it, as defender."))
        assert(mail.body.contains("Still waiting for: attacker and healer."))
    }

    test("another acceptor is told who they are now waiting on") {
        val mail = compose(NotificationType.AcceptedChallengeReady, news()).get

        assertEquals(mail.subject, "A Tic-Tac-Toe challenge you accepted is ready to start")
        assert(mail.body.contains("carol offered it, so it is up to them to start the match."))
    }

    // A challenge with no message of its own must not produce a mail quoting nothing.
    test("a challenge with no description is not quoted") {
        val mail = compose(NotificationType.ChallengeAccepted, news(description = "   ")).get

        assert(mail.body.contains("bob has accepted your Tic-Tac-Toe challenge, as defender."))
        assert(!mail.body.contains("\"\""))
    }

    /* The invitation an invitee is sent, which is the only mail here that its recipient did not
     * already know they had a stake in -- so it says what the challenge *is* rather than what has
     * changed about it, and it is the challenger it names.
     *
     * `actor` is deliberately unused by this kind: the person who acted is the recipient's
     * correspondent, not a third party, and naming them to themselves would read as somebody else
     * having done something. The assertion on "bob" is what holds that, since `news` supplies an
     * actor for every kind. */
    test("the invited player is told who invited them, to what, and as what") {
        val mail = compose(NotificationType.InvitationReceived, news()).get

        assertEquals(mail.recipient, "player@example.com")
        assertEquals(mail.subject, "carol has invited you to play Tic-Tac-Toe")
        assert(mail.body.contains("Hello alice,"))
        assert(mail.body.contains("carol has invited you to a Tic-Tac-Toe challenge \"friendly game\", as defender."))
        assert(mail.body.contains("Open matchmaker to accept it, or to turn it down."))
        // The actor is not a third party here. It is them.
        assert(!mail.body.contains("bob"), mail.body)
    }

    // An invitation naming no seat is an offer of any that is free, and says so by not saying
    // otherwise -- the same absence `mail.role` produces everywhere else it is empty.
    test("an invitation to no particular seat names none") {
        val mail = compose(NotificationType.InvitationReceived, news(role = None)).get

        assert(mail.body.contains("carol has invited you to a Tic-Tac-Toe challenge \"friendly game\"."))
        assert(!mail.body.contains("as defender"))
    }

    // What the challenge is waiting for is the challenger's problem, not the invitee's: they have
    // been asked for one seat, and they can neither fill the others nor start it. Asserted with a
    // roster that would print if the template carried one, so this fails if the line is ever added.
    test("an invitation does not tell the invitee what the challenge is still waiting for") {
        val mail = compose(NotificationType.InvitationReceived, news(waitingFor = Seq("attacker"))).get

        assert(!mail.body.contains("Still waiting for"), mail.body)
        assert(!mail.body.contains("Every role is now taken"), mail.body)
    }

    // Back to the challenger, and the roster line is here for the reason it is on ChallengeAccepted:
    // they are the one who can start it, so how far off that is is the part they need.
    test("the challenger is told their invitation was accepted, and what is still missing") {
        val mail = compose(NotificationType.InvitationAccepted, news(waitingFor = Seq("attacker"))).get

        assertEquals(mail.subject, "bob has accepted your Tic-Tac-Toe invitation")
        assert(mail.body.contains("bob has accepted your Tic-Tac-Toe invitation \"friendly game\", as defender."))
        assert(mail.body.contains("Still waiting for: attacker."))
    }

    /* And the rejection, which is the one mail here that deliberately has no roster line.
     *
     * A rejection changes nothing about what the challenge is waiting for -- the invitation was
     * never an acceptance, so no seat has just come free in the sense the roster counts. What it
     * says instead is that the challenge is still there, which is the thing a challenger would
     * otherwise have to go and check. The roster is supplied here and must still not appear. */
    test("a rejection says the challenge is still open, and names no roster") {
        val mail = compose(NotificationType.InvitationRejected, news(joined = false, waitingFor = Seq("attacker"))).get

        assertEquals(mail.subject, "bob has turned down your Tic-Tac-Toe invitation")
        assert(mail.body.contains("bob has turned down your Tic-Tac-Toe invitation \"friendly game\"."))
        assert(mail.body.contains("The challenge is still open, and the seat can be offered to somebody else."))
        assert(!mail.body.contains("Still waiting for"), mail.body)
    }

    // The seat a rejection was for is not named, for the same reason a withdrawal's is not: what it
    // freed is either a role a start waits for -- in which case the next mail's roster names it --
    // or one that nobody was waiting on.
    test("a rejection names no seat even when the invitation held one") {
        val mail = compose(NotificationType.InvitationRejected, news(role = Some("defender"))).get

        assert(!mail.body.contains("as defender"), mail.body)
    }

    // A challenge with no message of its own, on the invitation kinds too: the quoted clause
    // disappears from a different sentence in each of them, and an empty pair of quotes in any
    // would be the same bug.
    test("an invitation to a challenge with no description is not quoted either") {
        Seq(
          NotificationType.InvitationReceived,
          NotificationType.InvitationAccepted,
          NotificationType.InvitationRejected
        ).foreach { kind =>
            val mail = compose(kind, news(description = "   ")).get
            assert(!mail.body.contains("\"\""), s"$kind: ${mail.body}")
        }
    }

    // The template covers seven of the eleven kinds. A caller that has chosen one of the others has
    // chosen wrong, and a mail invented from the wrong facts is worse than no mail.
    test("a kind this template is not for produces nothing") {
        Seq(
          NotificationType.MatchStarted,
          NotificationType.TurnTaken,
          NotificationType.YourTurn,
          NotificationType.MatchEnded
        )
            .foreach(kind => assertEquals(compose(kind, news()), None, s"$kind"))
    }
}
