package com.vivi.matchmaker.model

import munit.FunSuite

/** The precedence rule, on its own.
  *
  * No database and no match: the whole point of keeping `NotificationPolicy` in the model is that "which of four
  * answers wins" can be settled here, once, rather than inferred from whether a mail turned up at the end of a fixture.
  * That it is wired to the right four answers is `MatchStartedNotificationSpec`'s business.
  */
class NotificationPolicySpec extends FunSuite {

    private val kind = NotificationType.MatchStarted

    private def levels(
        participant: Option[Boolean] = None,
        playerGame: Option[Boolean] = None,
        player: Option[Boolean] = None,
        game: Boolean
    ): NotificationLevels =
        NotificationLevels(
          participant = NotificationPreferences.unset.updated(kind, participant),
          playerGame = NotificationPreferences.unset.updated(kind, playerGame),
          player = NotificationPreferences.unset.updated(kind, player),
          game = NotificationDefaults.all(game)
        )

    test("with nothing said anywhere, the game decides") {
        assert(NotificationPolicy.wants(kind, levels(game = true)))
        assert(!NotificationPolicy.wants(kind, levels(game = false)))
    }

    test("the player overrides the game") {
        assert(!NotificationPolicy.wants(kind, levels(player = Some(false), game = true)))
        assert(NotificationPolicy.wants(kind, levels(player = Some(true), game = false)))
    }

    test("the player's setting for the game overrides their setting in general") {
        assert(!NotificationPolicy.wants(kind, levels(playerGame = Some(false), player = Some(true), game = true)))
        assert(NotificationPolicy.wants(kind, levels(playerGame = Some(true), player = Some(false), game = false)))
    }

    test("the match overrides everything") {
        assert(
          !NotificationPolicy.wants(
            kind,
            levels(participant = Some(false), playerGame = Some(true), player = Some(true), game = true)
          )
        )
        assert(
          NotificationPolicy.wants(
            kind,
            levels(participant = Some(true), playerGame = Some(false), player = Some(false), game = false)
          )
        )
    }

    // The reason the specific levels are nullable rather than defaulted: "no" and "I have not said"
    // are different answers, and only the second one falls through.
    test("false at a level is an answer, not an absence") {
        assert(!NotificationPolicy.wants(kind, levels(playerGame = Some(false), game = true)))
        // Where a level that says nothing lets the one below it through.
        assert(NotificationPolicy.wants(kind, levels(playerGame = None, player = Some(true), game = false)))
    }

    // One kind's answer must not be read from another's column, which is the mistake a positional
    // binding invites. Every kind is asked for separately and every other kind is left unset.
    test("each kind is answered by its own setting") {
        NotificationType.values.foreach { asked =>
            val said = NotificationPreferences.unset.updated(asked, Some(false))
            val chain = NotificationLevels(player = said, game = NotificationDefaults.all(true))
            assert(!NotificationPolicy.wants(asked, chain), s"$asked should be refused by its own setting")
            NotificationType.values.filter(_ != asked).foreach { other =>
                assert(NotificationPolicy.wants(other, chain), s"$other should be unaffected by $asked")
            }
        }
    }

    /* The "one email per event" rule. Several things can be true of one event -- the acceptance that
     * fills a roster, the move that hands over the turn -- and the recipient is owed one mail. */
    test("choose takes the fullest reason the player has not refused") {
        val chain = NotificationLevels(game = NotificationDefaults.all(true))
        assertEquals(
          NotificationPolicy.choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), chain),
          Some(NotificationType.YourTurn)
        )
    }

    test("choose falls back to a plainer reason rather than sending nothing") {
        val refusedTheFullest = NotificationLevels(
          player = NotificationPreferences.unset.updated(NotificationType.YourTurn, Some(false)),
          game = NotificationDefaults.all(true)
        )
        assertEquals(
          NotificationPolicy.choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), refusedTheFullest),
          Some(NotificationType.TurnTaken)
        )
    }

    test("choose sends nothing only when every reason has been refused") {
        val refusedBoth = NotificationLevels(
          player = NotificationPreferences.unset
              .updated(NotificationType.YourTurn, Some(false))
              .updated(NotificationType.TurnTaken, Some(false)),
          game = NotificationDefaults.all(true)
        )
        assertEquals(
          NotificationPolicy.choose(Seq(NotificationType.YourTurn, NotificationType.TurnTaken), refusedBoth),
          None
        )
        assertEquals(
          NotificationPolicy.choose(Seq.empty, NotificationLevels(game = NotificationDefaults.all(true))),
          None
        )
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
