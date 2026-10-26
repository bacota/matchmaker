package com.vivi.matchmaker.notify

import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the mails about a challenge's or tournament's entries and invitations actually say.
  *
  * Pure, like `MatchMailSpec`, and for the same reason: the decisions worth pinning down — who the mail is addressed as
  * being about, whether it asks the player to do anything, how far from full it says it is — are all made here, with no
  * tournament and no database in sight.
  *
  * One kind is about an entry and three about an invitation. The invitation three are the same shape one level out — an
  * offer is made, taken up, or turned down — and what is worth a test of its own in each is the thing that makes it
  * *not* the entry mail beside it: who it is addressed to, and whether how full it is is any of their business.
  */
class ChallengeMailSpec extends FunSuite {

    private def player(nickname: String, email: Option[String] = Some("player@example.com")) =
        Player(playerId = PlayerId(1), nickname = nickname, isAdmin = false, "sub-1", email)

    private def news(
        joined: Boolean = true,
        room: Option[Int] = Some(1),
        what: String = "challenge",
        title: String = "",
        isReady: Boolean = false
    ) =
        ChallengeNews(
          gameName = "Tic-Tac-Toe",
          what = what,
          title = title,
          actor = "bob",
          isJoined = joined,
          challenger = "carol",
          room = room,
          isReady = isReady
        )

    private def compose(kind: NotificationType, news: ChallengeNews, recipient: Player = player("alice")) =
        ChallengeMail.compose(
          sender = "matchmaker@example.com",
          uiBaseUrl = "https://matchmaker.example.com",
          recipient = recipient,
          kind = kind,
          news = news
        )

    test("a player with no address gets nothing") {
        assertEquals(
          compose(kind = NotificationType.CompetitionEntered, news = news(), recipient = player("alice", None)),
          None
        )
    }

    test("the owner is told who entered, and how many more it takes") {
        val mail = compose(kind = NotificationType.CompetitionEntered, news = news(room = Some(2))).get

        assertEquals(mail.sender, "matchmaker@example.com")
        assertEquals(mail.recipient, "player@example.com")
        assertEquals(mail.subject, "bob has entered your Tic-Tac-Toe challenge")
        assert(mail.body.contains("Hello alice,"))
        assert(mail.body.contains("bob has entered your Tic-Tac-Toe challenge."))
        assert(mail.body.contains("It has room for 2 more."))
        assert(mail.body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    test("the owner is told who withdrew") {
        val mail = compose(kind = NotificationType.CompetitionEntered, news = news(joined = false)).get

        assertEquals(mail.subject, "bob has withdrawn from your Tic-Tac-Toe challenge")
        assert(mail.body.contains("bob has withdrawn from your Tic-Tac-Toe challenge."))
    }

    // A tournament is named, where a challenge's name would only say that it is one.
    test("a tournament is called one, and named") {
        val mail = compose(
          kind = NotificationType.CompetitionEntered,
          news = news(what = "tournament", title = "Spring Open")
        ).get

        assertEquals(mail.subject, "bob has entered your Tic-Tac-Toe tournament")
        assert(mail.body.contains("bob has entered your Tic-Tac-Toe tournament \"Spring Open\"."))
    }

    // One that takes any number of entries has no "how full" to say.
    test("one with no limit on entries says nothing about room") {
        val mail = compose(kind = NotificationType.CompetitionEntered, news = news(room = None)).get

        assert(!mail.body.contains("room for"), mail.body)
        assert(!mail.body.contains("now full"), mail.body)
    }

    // The one mail of these that asks the player to do something.
    test("the owner is asked to start it once it is full") {
        val mail =
            compose(kind = NotificationType.CompetitionEntered, news = news(room = Some(0), isReady = true)).get

        assertEquals(mail.subject, "Your Tic-Tac-Toe challenge is ready to start")
        assert(mail.body.contains("It is now full, so you can start it whenever you like."))
    }

    // One that starts itself once full is starting, not waiting on its owner.
    test("an entry that fills one that is not waiting on its owner says only that it is full") {
        val mail = compose(kind = NotificationType.CompetitionEntered, news = news(room = Some(0))).get

        assertEquals(mail.subject, "bob has entered your Tic-Tac-Toe challenge")
        assert(mail.body.contains("It is now full."))
    }

    // A challenge has no name worth repeating, so nothing is quoted.
    test("a challenge is not quoted") {
        Seq(
          NotificationType.CompetitionEntered,
          NotificationType.InvitationReceived,
          NotificationType.InvitationAccepted,
          NotificationType.InvitationRejected
        ).foreach { kind =>
            val mail = compose(kind = kind, news = news()).get
            assert(!mail.body.contains("\""), s"$kind: ${mail.body}")
        }
    }

    /* The invitation an invitee is sent, which is the only mail here that its recipient did not
     * already know they had a stake in -- so it says what it *is* rather than what has changed about
     * it, and it is the owner it names. `actor` is deliberately unused by this kind: naming the
     * recipient to themselves would read as somebody else having done something. */
    test("the invited player is told who invited them, and to what") {
        val mail = compose(kind = NotificationType.InvitationReceived, news = news()).get

        assertEquals(mail.subject, "carol has invited you to play Tic-Tac-Toe")
        assert(mail.body.contains("carol has invited you to a Tic-Tac-Toe challenge."))
        assert(mail.body.contains("Open matchmaker to enter it, or to turn it down."))
        assert(!mail.body.contains("bob"), mail.body)
    }

    // How full it is is the owner's problem, not the invitee's.
    test("an invitation does not tell the invitee how full it is") {
        val mail = compose(kind = NotificationType.InvitationReceived, news = news(room = Some(2))).get

        assert(!mail.body.contains("room for"), mail.body)
    }

    test("the owner is told their invitation was accepted, and how many more it takes") {
        val mail = compose(kind = NotificationType.InvitationAccepted, news = news(room = Some(1))).get

        assertEquals(mail.subject, "bob has accepted your Tic-Tac-Toe invitation")
        assert(mail.body.contains("bob has accepted your invitation to your Tic-Tac-Toe challenge."))
        assert(mail.body.contains("It has room for 1 more."))
    }

    /* And the refusal, which deliberately says nothing about how full it is: turning an invitation down
     * changes nothing about the entries. What it says instead is that it is still open. */
    test("a refusal says it is still open, and nothing about how full it is") {
        val mail = compose(kind = NotificationType.InvitationRejected, news = news(joined = false, room = Some(1))).get

        assertEquals(mail.subject, "bob has turned down your Tic-Tac-Toe invitation")
        assert(mail.body.contains("bob has turned down your invitation to your Tic-Tac-Toe challenge."))
        assert(mail.body.contains("It is still open, and you can invite somebody else."))
        assert(!mail.body.contains("room for"), mail.body)
    }

    // The template covers seven of the twelve kinds. A caller that has chosen one of the others has chosen wrong, and
    // a mail invented from the wrong facts is worse than no mail.
    test("a kind this template is not for produces nothing") {
        Seq(
          NotificationType.MatchStarted,
          NotificationType.TurnTaken,
          NotificationType.YourTurn,
          NotificationType.MatchEnded,
          NotificationType.TournamentRoundDone
        ).foreach(kind => assertEquals(compose(kind = kind, news = news()), None, s"$kind"))
    }
}
