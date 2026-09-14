package com.vivi.matchmaker.notify

import java.time.Instant
import munit.FunSuite
import com.vivi.matchmaker.model._

/** What the mail actually says.
  *
  * Pure, so it is tested here rather than through a started match: composing the text needs no database, and the rules
  * worth pinning down — a player with no address gets nothing, a challenge with no message falls back to the game's
  * name, a deadline is only quoted when there is one — are all decisions this function makes on its own.
  */
class MatchStartedMailSpec extends FunSuite {

    private val game =
        Game(
          GameId(1),
          GameType.Plain,
          "Tic-Tac-Toe",
          "a game",
          "https://engine/games",
          active = true,
          Seq.empty,
          Seq.empty,
          "ttt"
        )

    private def player(nickname: String, email: Option[String]) =
        Player(PlayerId(1), nickname, isAdmin = false, "sub-1", email)

    private def compose(
        recipient: Player = player("alice", Some("alice@example.com")),
        description: String = "friendly game",
        others: Seq[String] = Seq("bob"),
        yourTurn: Boolean = false,
        due: Option[Instant] = None,
        playUrl: Option[String] = Some("https://engine/play/1")
    ): Option[MailMessage] =
        MatchStartedMail.compose(
          sender = "matchmaker@example.com",
          uiBaseUrl = "https://matchmaker.example.com",
          game = game,
          description = description,
          recipient = recipient,
          others = others,
          yourTurn = yourTurn,
          due = due,
          playUrl = playUrl
        )

    test("the mail names the game, the challenge and the other players") {
        val message = compose().get
        assertEquals(message.sender, "matchmaker@example.com")
        assertEquals(message.recipient, "alice@example.com")
        assertEquals(message.subject, "Your Tic-Tac-Toe match has started")
        assert(message.body.contains("Hello alice,"))
        assert(message.body.contains("""Your match of Tic-Tac-Toe has started: "friendly game"."""))
        assert(message.body.contains("Playing with you: bob."))
    }

    // Nowhere to write to is the one reason there is nothing to send, and it is decided here so
    // that every kind of notification skips such a player the same way.
    test("a player with no address produces no mail at all") {
        assertEquals(compose(recipient = player("alice", None)), None)
    }

    test("a challenge with no message falls back to the game's name") {
        assert(compose(description = "   ").get.body.contains("Your match of Tic-Tac-Toe has started."))
    }

    test("a player whose turn it is, on a clock, is told when it runs out") {
        val body = compose(yourTurn = true, due = Some(Instant.parse("2030-04-05T06:07:08Z"))).get.body
        assert(body.contains("It is your turn, and it is due by 2030-04-05 06:07 UTC."))
    }

    test("a player whose turn it is with no clock is told only that it is their turn") {
        val body = compose(yourTurn = true).get.body
        assert(body.contains("It is your turn."))
        assert(!body.contains("due by"))
    }

    test("a player who is not first is told they will hear when it is their turn") {
        assert(compose().get.body.contains("You will be told when it is your turn."))
    }

    test("the only player in a match is not told about the others") {
        assert(compose(others = Seq.empty).get.body.contains("You are the only player."))
    }

    /* Both links, and the engine's first: it is where the game is played, and matchmaker's is a
     * list this match is one row of. A match whose engine gave no play url still has somewhere for
     * the player to go. */

    test("both links are offered when the engine gave one") {
        val body = compose().get.body
        assert(body.contains("Play: https://engine/play/1"))
        assert(body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    test("a match with no play url still links to matchmaker") {
        val body = compose(playUrl = None).get.body
        assert(!body.contains("Play:"))
        assert(body.contains("Open matchmaker: https://matchmaker.example.com"))
    }

    test("a mail round-trips through the queue's json") {
        val message = compose().get
        assertEquals(upickle.default.read[MailMessage](upickle.default.write(message)), message)
    }
}
