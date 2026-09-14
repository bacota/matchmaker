package com.vivi.matchmaker.notify

import java.time.Instant
import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the three mails about a match in progress actually say. Pure, as the other two template specs are. */
class MatchMailSpec extends FunSuite {

    private def player(nickname: String, email: Option[String] = Some("player@example.com")) =
        Player(PlayerId(1), nickname, isAdmin = false, "sub-1", email)

    private def news(
        mover: Option[String] = Some("bob"),
        nextUp: Seq[String] = Seq("alice"),
        due: Option[Instant] = None,
        playUrl: Option[String] = Some("https://engine/play/1"),
        ending: Option[MatchEnding] = None,
        description: String = "friendly game"
    ) =
        MatchNews("Tic-Tac-Toe", description, mover, nextUp, due, playUrl, ending)

    private def compose(kind: NotificationType, news: MatchNews, recipient: Player = player("alice")) =
        MatchMail.compose("matchmaker@example.com", "https://matchmaker.example.com", recipient, kind, news)

    test("a player with no address gets nothing") {
        assertEquals(compose(NotificationType.TurnTaken, news(), player("alice", None)), None)
    }

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
        Seq(NotificationType.MatchStarted, NotificationType.ChallengeReady)
            .foreach(kind => assertEquals(compose(kind, news()), None, s"$kind"))
    }
}
