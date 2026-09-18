package com.vivi.matchmaker.notify

import java.time.Instant
import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the four mails about a match actually say.
  *
  * Pure, as the other template spec is: composing the text needs no match and no database, and the rules worth pinning
  * down — a player with no address gets nothing, a challenge with no message is not quoted, a deadline is only
  * mentioned when there is one — are all decisions this template makes on its own.
  */
class MatchMailSpec extends FunSuite {

    private def player(nickname: String, email: Option[String] = Some("player@example.com")) =
        Player(PlayerId(1), nickname, isAdmin = false, "sub-1", email)

    private def news(
        mover: Option[String] = Some("bob"),
        nextUp: Seq[String] = Seq("alice"),
        due: Option[Instant] = None,
        others: Seq[String] = Seq("bob"),
        yourTurn: Boolean = false,
        playUrl: Option[String] = Some("https://engine/play/1"),
        acceptedBy: Option[String] = None,
        ending: Option[MatchEnding] = None,
        description: String = "friendly game"
    ) =
        MatchNews(
          "Tic-Tac-Toe",
          description,
          mover,
          nextUp,
          due,
          others,
          yourTurn,
          playUrl,
          acceptedBy,
          ending
        )

    private def compose(kind: NotificationType, news: MatchNews, recipient: Player = player("alice")) =
        MatchMail.compose("matchmaker@example.com", "https://matchmaker.example.com", recipient, kind, news)

    test("a player with no address gets nothing") {
        assertEquals(compose(NotificationType.TurnTaken, news(), player("alice", None)), None)
    }

    // -------------------------------------------------------------------------
    // A match beginning: the one mail that has to introduce the match itself
    // -------------------------------------------------------------------------

    test("a start names the game, the challenge and the other players") {
        val mail = compose(NotificationType.MatchStarted, news()).get

        assertEquals(mail.sender, "matchmaker@example.com")
        assertEquals(mail.recipient, "player@example.com")
        assertEquals(mail.subject, "Your Tic-Tac-Toe match has started")
        assert(mail.body.contains("Hello alice,"))
        assert(mail.body.contains("""Your match of Tic-Tac-Toe has started: "friendly game"."""))
        assert(mail.body.contains("Playing with you: bob."))
    }

    test("a start with no challenge message falls back to the game's name") {
        val mail = compose(NotificationType.MatchStarted, news(description = "   ")).get

        assert(mail.body.contains("Your match of Tic-Tac-Toe has started."))
        assert(!mail.body.contains("\"\""))
    }

    /* The one mail that is about two things, for a challenge that was offered as starting itself:
     * the acceptance that filled the roster is not mailed separately on that path, so this mail
     * says who joined before it says the match is under way. */
    test("a match that an acceptance started says who accepted, then that it has started") {
        val mail = compose(NotificationType.MatchStarted, news(acceptedBy = Some("carol"))).get

        assertEquals(mail.subject, "carol has accepted the Tic-Tac-Toe challenge, and the match has started")
        assert(
          mail.body.contains(
            """carol has accepted the Tic-Tac-Toe challenge, so your match has started: "friendly game"."""
          )
        )
        // Everything after that first sentence is the ordinary start mail.
        assert(mail.body.contains("Playing with you: bob."))
    }

    test("an acceptance that started a match with no challenge message still names the game") {
        val mail = compose(NotificationType.MatchStarted, news(acceptedBy = Some("carol"), description = "  ")).get

        assert(mail.body.contains("carol has accepted the Tic-Tac-Toe challenge, so your match has started."))
        assert(!mail.body.contains("\"\""))
    }

    test("a player who moves first, on a clock, is told when their turn runs out") {
        val due = Instant.parse("2030-04-05T06:07:08Z")
        val mail = compose(NotificationType.MatchStarted, news(yourTurn = true, due = Some(due))).get

        assert(mail.body.contains("It is your turn, and it is due by 2030-04-05 06:07 UTC."))
    }

    test("a player who moves first with no clock is told only that it is their turn") {
        val mail = compose(NotificationType.MatchStarted, news(yourTurn = true)).get

        assert(mail.body.contains("It is your turn."))
        assert(!mail.body.contains("due by"))
    }

    test("a player who is not first is told they will hear when it is their turn") {
        assert(
          compose(NotificationType.MatchStarted, news()).get.body.contains("You will be told when it is your turn.")
        )
    }

    test("the only player in a match is not told about the others") {
        assert(
          compose(NotificationType.MatchStarted, news(others = Seq.empty)).get.body.contains("You are the only player.")
        )
    }

    /* Both links, and the engine's first: it is where the game is played, and matchmaker's is a list
     * this match is one row of. A match whose engine gave no play url still has somewhere for the
     * player to go. */
    test("both links are offered when the engine gave one") {
        val body = compose(NotificationType.MatchStarted, news()).get.body

        assert(body.contains("Play: https://engine/play/1"))
        assert(body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    test("a match with no play url still links to matchmaker") {
        val body = compose(NotificationType.MatchStarted, news(playUrl = None)).get.body

        assert(!body.contains("Play:"))
        assert(body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    // Not about this template but about what carries its output: the queue's json is how a composed
    // mail reaches the thing that sends it, and a mail that will not survive the trip sends nothing.
    test("a mail round-trips through the queue's json") {
        val mail = compose(NotificationType.MatchStarted, news()).get

        assertEquals(upickle.default.read[MailMessage](upickle.default.write(mail)), mail)
    }

    // -------------------------------------------------------------------------
    // A match in progress, and a match over
    // -------------------------------------------------------------------------

    test("a move says who made it and who it is now the turn of") {
        val mail = compose(NotificationType.TurnTaken, news(nextUp = Seq("alice", "carol"))).get

        assertEquals(mail.subject, "bob has moved in your Tic-Tac-Toe match")
        assert(mail.body.contains("bob has taken a turn in your Tic-Tac-Toe match \"friendly game\"."))
        assert(mail.body.contains("It is now alice and carol's turn."))
        assert(mail.body.contains("Play: https://engine/play/1"))
    }

    // The one notification in the whole set that asks the player to do something, so it says by when.
    test("your turn quotes the deadline when there is one") {
        val due = Instant.parse("2030-01-02T03:04:05Z")
        val mail = compose(NotificationType.YourTurn, news(due = Some(due))).get

        assertEquals(mail.subject, "It is your turn in your Tic-Tac-Toe match")
        assert(mail.body.contains("bob has moved, and it is your turn"))
        assert(mail.body.contains("It is due by 2030-01-02 03:04 UTC."))
    }

    test("your turn says nothing about a deadline when the match has no clock") {
        val mail = compose(NotificationType.YourTurn, news()).get

        assert(mail.body.contains("it is your turn in your Tic-Tac-Toe match"))
        assert(!mail.body.contains("due by"))
    }

    // Three endings rather than one, because the difference is the whole content of the mail.
    test("an ending says how it ended") {
        val cancelled = compose(NotificationType.MatchEnded, news(ending = Some(MatchEnding.Cancelled))).get
        val forfeited = compose(NotificationType.MatchEnded, news(ending = Some(MatchEnding.Forfeited))).get
        val finished = compose(NotificationType.MatchEnded, news(ending = Some(MatchEnding.Finished))).get

        assertEquals(cancelled.subject, "Your Tic-Tac-Toe match is over")
        assert(cancelled.body.contains("Its creator has called it off."))
        assert(forfeited.body.contains("ran out of time"))
        assert(finished.body.contains("The game is over."))
    }

    // No play link on a match that is over: the board may still be there, but inviting a player to
    // take a turn in a finished match is worse than saying nothing.
    test("an ending carries no play link") {
        val mail = compose(NotificationType.MatchEnded, news(playUrl = None, ending = Some(MatchEnding.Finished))).get

        assert(!mail.body.contains("Play:"))
        assert(mail.body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    test("a kind this template is not for produces nothing") {
        Seq(NotificationType.ChallengeAccepted, NotificationType.ChallengeReady, NotificationType.AcceptanceChanged)
            .foreach(kind => assertEquals(compose(kind, news()), None, s"$kind"))
    }
}
