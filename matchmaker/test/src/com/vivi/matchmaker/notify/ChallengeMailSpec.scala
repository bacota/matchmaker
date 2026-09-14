package com.vivi.matchmaker.notify

import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the four mails about a challenge actually say.
  *
  * Pure, like `MatchStartedMailSpec`, and for the same reason: the decisions worth pinning down — who the mail is
  * addressed as being about, whether it asks the player to do anything, what it says is still missing — are all made
  * here, with no challenge and no database in sight.
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

    // The one mail of the four that asks the player to do something.
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

    // The template covers four of the eight kinds. A caller that has chosen one of the others has
    // chosen wrong, and a mail invented from the wrong facts is worse than no mail.
    test("a kind this template is not for produces nothing") {
        Seq(NotificationType.MatchStarted, NotificationType.TurnTaken, NotificationType.YourTurn)
            .foreach(kind => assertEquals(compose(kind, news()), None, s"$kind"))
    }
}
