package com.vivi.matchmaker.model

import munit.FunSuite

/** The precedence rule, on its own.
  *
  * No database and no match: the whole point of keeping the rule in the model is that "which of three answers wins" can
  * be settled here, once, rather than inferred from whether a mail turned up at the end of a fixture. That it is wired
  * to the right three answers is `MatchStartedNotificationSpec`'s business.
  *
  * The chain is what a new seat is stamped with, and what an audience that has no seat yet is asked by. What decides
  * for a seat is the seat's own eight columns, which is why `NotificationPolicy` here is over `NotificationDefaults`
  * and has nothing left to resolve.
  */
class NotificationPolicySpec extends FunSuite {

    private val kind = NotificationType.MatchStarted

    private def levels(
        playerGame: Option[Boolean] = None,
        player: Option[Boolean] = None,
        game: Boolean
    ): NotificationLevels =
        NotificationLevels(
          playerGame = NotificationPreferences.unset.updated(kind, playerGame),
          player = NotificationPreferences.unset.updated(kind, player),
          game = NotificationDefaults.all(game)
        )

    private def resolved(
        playerGame: Option[Boolean] = None,
        player: Option[Boolean] = None,
        game: Boolean
    ): Boolean = levels(playerGame, player, game).resolve(kind)

    test("with nothing said anywhere, the game decides") {
        assert(resolved(game = true))
        assert(!resolved(game = false))
    }

    test("the player overrides the game") {
        assert(!resolved(player = Some(false), game = true))
        assert(resolved(player = Some(true), game = false))
    }

    test("the player's setting for the game overrides their setting in general") {
        assert(!resolved(playerGame = Some(false), player = Some(true), game = true))
        assert(resolved(playerGame = Some(true), player = Some(false), game = false))
    }

    // The reason the specific levels are nullable rather than defaulted: "no" and "I have not said"
    // are different answers, and only the second one falls through.
    test("false at a level is an answer, not an absence") {
        assert(!resolved(playerGame = Some(false), game = true))
        // Where a level that says nothing lets the one below it through.
        assert(resolved(playerGame = None, player = Some(true), game = false))
    }

    // One kind's answer must not be read from another's column, which is the mistake a positional
    // binding invites. Every kind is asked for separately and every other kind is left unset.
    test("each kind is answered by its own setting") {
        NotificationType.values.foreach { asked =>
            val said = NotificationPreferences.unset.updated(asked, Some(false))
            val answers = NotificationLevels(player = said, game = NotificationDefaults.all(true)).resolve
            assert(!NotificationPolicy.wants(asked, answers), s"$asked should be refused by its own setting")
            NotificationType.values.filter(_ != asked).foreach { other =>
                assert(NotificationPolicy.wants(other, answers), s"$other should be unaffected by $asked")
            }
        }
    }

    // A seat is stamped with the whole of this, not with the one kind that happened to be asked for.
    test("resolving answers every kind, each from its own most specific level") {
        val answers = NotificationLevels(
          playerGame = NotificationPreferences.unset.updated(NotificationType.YourTurn, Some(false)),
          player = NotificationPreferences.unset
              .updated(NotificationType.YourTurn, Some(true))
              .updated(NotificationType.TurnTaken, Some(false)),
          game = NotificationDefaults.all(true)
        ).resolve

        assertEquals(answers.yourTurn, false, "the per-game answer wins")
        assertEquals(answers.turnTaken, false, "the player's answer wins where the game level said nothing")
        assertEquals(answers.matchEnded, true, "and the game answers the rest")
    }

    /* The "one email per event" rule. Several things can be true of one event -- the acceptance that
     * fills a roster, the move that hands over the turn -- and the recipient is owed one mail. */
    test("choose takes the fullest reason the player has not refused") {
        assertEquals(
          NotificationPolicy
              .choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), NotificationDefaults.all(true)),
          Some(NotificationType.YourTurn)
        )
    }

    test("choose falls back to a plainer reason rather than sending nothing") {
        val refusedTheFullest = NotificationDefaults.all(true).copy(yourTurn = false)
        assertEquals(
          NotificationPolicy.choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), refusedTheFullest),
          Some(NotificationType.TurnTaken)
        )
    }

    test("choose sends nothing only when every reason has been refused") {
        val refusedBoth = NotificationDefaults.all(true).copy(yourTurn = false, turnTaken = false)
        assertEquals(
          NotificationPolicy.choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), refusedBoth),
          None
        )
        assertEquals(NotificationPolicy.choose(Seq.empty, NotificationDefaults.all(true)), None)
    }

    // What a cascade is allowed to touch. The three cases that matter are an answer changing, an
    // answer being withdrawn, and a question the save did not touch at all.
    test("differences names the questions a save changed and no others") {
        val before = NotificationPreferences.unset
            .updated(NotificationType.YourTurn, Some(true))
            .updated(NotificationType.MatchEnded, Some(false))
        val after = before
            .updated(NotificationType.YourTurn, Some(false))
            .updated(NotificationType.MatchEnded, None)
            .updated(NotificationType.TurnTaken, Some(true))

        assertEquals(
          before.differences(after),
          Set(NotificationType.YourTurn, NotificationType.MatchEnded, NotificationType.TurnTaken)
        )
        // Saving the same answers again changes nothing, and so carries nothing anywhere.
        assertEquals(before.differences(before), Set.empty[NotificationType])
    }

    test("a column name per kind, all distinct, all derived from the code") {
        assertEquals(NotificationType.MatchStarted.column, "notify_match_started")
        assertEquals(NotificationType.values.map(_.column).distinct.length, NotificationType.values.length)
    }

    // What the game form relies on: eight answers make a set of defaults, and anything less does not.
    test("preferences are complete only when every kind is answered") {
        assertEquals(NotificationPreferences.unset.complete, None)
        assertEquals(NotificationPreferences.unset.unsaid.length, NotificationType.values.length)

        val answered = NotificationType.values.foldLeft(NotificationPreferences.unset)((p, k) =>
            p.updated(k, Some(k != NotificationType.TurnTaken))
        )
        assertEquals(answered.unsaid, Seq.empty)
        assertEquals(answered.complete.map(_.turnTaken), Some(false))
        assertEquals(answered.complete.map(_.matchStarted), Some(true))
        // And round-trips: a game's defaults read back as preferences with everything said.
        assertEquals(answered.complete.map(_.asPreferences), Some(answered))
    }
}
