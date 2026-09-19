package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Gen
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

/** Recording what a player wants to be told about, at the two levels that are theirs to set from the account panel.
  *
  * Which of the levels wins is `NotificationPolicySpec`, with no database in sight; that the levels are read from the
  * right rows is here. The per-match level needs a match and so lives in `MatchStartedNotificationSpec`, along with the
  * cascades that reach it.
  */
class NotificationServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val caseTimeout = 60.seconds
    private val services = TestServices.services

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private def makeGame(seed: String, defaults: NotificationDefaults = NotificationDefaults.all(true)): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                s"game-$seed",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(GameRole(GameRoleId(0), GameId.unassigned, "player", optional = false)),
                Seq.empty,
                s"game-$seed",
                notifications = defaults
              )
            )
        }

    // A registered player has said nothing, which is not the same as having said no -- so the form
    // opens on "Use Default" throughout, and every kind falls through to the levels below.
    property("a new player has said nothing about anything") {
        forAll(genUniqueString) { seed =>
            val caller = s"quiet-$seed"
            val result = for {
                _ <- services.registration.register(s"quiet-$seed", caller, None)
                settings <- services.notifications.mine(caller)
            } yield settings.player == NotificationPreferences.unset &&
                settings.player.unsaid.length == NotificationType.values.length &&
                settings.games.isEmpty
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    property("what a player says in general is what comes back") {
        forAll(genUniqueString) { seed =>
            val caller = s"overall-$seed"
            val said = NotificationPreferences.unset
                .updated(NotificationType.TurnTaken, Some(false))
                .updated(NotificationType.MatchStarted, Some(true))

            val result = for {
                _ <- services.registration.register(s"overall-$seed", caller, None)
                _ <- services.notifications.updateMine(caller, said)
                settings <- services.notifications.mine(caller)
                // And a second save replaces the first rather than merging into it: clearing an answer
                // back to "Use Default" is a thing the form can do, and a write that only ever set
                // values could not express it.
                _ <- services.notifications.updateMine(caller, NotificationPreferences.unset)
                cleared <- services.notifications.mine(caller)
            } yield settings.player == said && cleared.player == NotificationPreferences.unset
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The `player_game` row is created by the first save and updated by the second, which is one
    // statement either way -- a player editing a game's settings does not know whether they have
    // edited them before.
    property("a game's settings are created and then replaced") {
        forAll(genUniqueString) { seed =>
            val caller = s"pergame-$seed"
            val first = NotificationPreferences.unset.updated(NotificationType.YourTurn, Some(false))
            val second = NotificationPreferences.unset.updated(NotificationType.YourTurn, Some(true))

            val result = for {
                _ <- services.registration.register(s"pergame-$seed", caller, None)
                game <- makeGame(seed)
                other <- makeGame(s"other-$seed")
                _ <- services.notifications.updateForGame(caller, game.gameId, first)
                afterFirst <- services.notifications.mine(caller)
                _ <- services.notifications.updateForGame(caller, game.gameId, second)
                afterSecond <- services.notifications.mine(caller)
                // A second game the player has said nothing about stays absent: the table holds
                // answers, not a row per game in existence.
                _ <- services.notifications.updateForGame(caller, other.gameId, NotificationPreferences.unset)
                withOther <- services.notifications.mine(caller)
            } yield afterFirst.games == Seq(GameNotificationPreferences(game.gameId, first)) &&
                afterSecond.games == Seq(GameNotificationPreferences(game.gameId, second)) &&
                withOther.games.map(_.gameId).toSet == Set(game.gameId, other.gameId)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Two players' answers are two players' answers. Worth stating because every level here is
    // keyed by the caller rather than by anything they send, so a query that lost the key would
    // pass every test above and fail this one.
    // The offer the defaults form makes: a player who has answered one game differently and then asks
    // for their defaults to be used everywhere gets one answer rather than two for the question they
    // changed -- and keeps the game's own answer to every question they did not.
    property("a change to the defaults can be copied into every game the player has answered") {
        forAll(genUniqueString) { seed =>
            val caller = s"cascader-$seed"
            val forGame = NotificationPreferences.unset
                .updated(NotificationType.MatchStarted, Some(false))
                .updated(NotificationType.TurnTaken, Some(true))
            val result = for {
                _ <- services.registration.register(s"cascader-$seed", caller, None)
                game <- makeGame(seed)
                _ <- services.notifications.updateForGame(caller, game.gameId, forGame)
                defaults = NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(true))
                _ <- services.notifications.updateMine(caller, defaults, applyToGames = true)
                settings <- services.notifications.mine(caller)
            } yield settings.player == defaults &&
                // match-started was the question that changed, so the game now agrees about it;
                // turn-taken was not, so the game still says what it said.
                settings.games.map(_.preferences) == Seq(forGame.updated(NotificationType.MatchStarted, Some(true)))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Withdrawing an answer is a change like any other, and is carried as the NULL it now is rather
    // than leaving the game frozen on what the player used to think.
    property("going back to unsaid is carried into the games too") {
        forAll(genUniqueString) { seed =>
            val caller = s"withdrawn-$seed"
            val said = NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(false))
            val result = for {
                _ <- services.registration.register(s"withdrawn-$seed", caller, None)
                game <- makeGame(seed)
                _ <- services.notifications.updateMine(caller, said, applyToGames = true)
                _ <- services.notifications.updateForGame(caller, game.gameId, said)
                _ <- services.notifications.updateMine(caller, NotificationPreferences.unset, applyToGames = true)
                settings <- services.notifications.mine(caller)
            } yield settings.player == NotificationPreferences.unset &&
                settings.games.map(_.preferences) == Seq(NotificationPreferences.unset)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The lock the diffing read takes, over two real connections.
     *
     * Two saves at once is one player with two tabs open, or one impatient double click. Each works
     * out what it changed by comparing against what it read, so whichever goes second has to compare
     * against the first's result -- otherwise it computes a change that has already happened, carries
     * that into the game row, and leaves the two levels disagreeing about which save occurred.
     *
     * Asserted as the invariant rather than as an interleaving: this game row has no answer of its
     * own, so whichever save wins, the row must end up saying exactly what the player says. Which one
     * wins is not the point and is not ours to decide.
     *
     * A passing run does not prove the absence of a race -- an unlocked read can serialize by luck.
     * What it does do is fail while the lock is missing, which it did before the lock was added. */
    property("two saves at once leave the player and their games agreeing") {
        forAll(genUniqueString) { seed =>
            val caller = s"racer-$seed"
            val first = NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(false))
            val second = NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(true))
            val result = for {
                _ <- services.registration.register(s"racer-$seed", caller, None)
                game <- makeGame(seed)
                // Something for the cascade to write to: aligning deliberately creates no row for a
                // game the player has never said anything about, so this is what makes one.
                _ <- services.notifications.updateForGame(caller, game.gameId, NotificationPreferences.unset)
                _ <- IO.both(
                  services.notifications.updateMine(caller, first, applyToGames = true),
                  services.notifications.updateMine(caller, second, applyToGames = true)
                )
                settings <- services.notifications.mine(caller)
            } yield settings.games.map(_.preferences) == Seq(settings.player)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // And without the box ticked, saving the defaults is saving the defaults: the game the player
    // answered separately goes on answering separately, which is what answering it separately meant.
    property("new defaults leave a game's own answers alone unless asked") {
        forAll(genUniqueString) { seed =>
            val caller = s"nocascade-$seed"
            val forGame = NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(false))
            val result = for {
                _ <- services.registration.register(s"nocascade-$seed", caller, None)
                game <- makeGame(seed)
                _ <- services.notifications.updateForGame(caller, game.gameId, forGame)
                _ <- services.notifications.updateMine(
                  caller,
                  NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(true))
                )
                settings <- services.notifications.mine(caller)
            } yield settings.games.map(_.preferences) == Seq(forGame)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    property("one player's answers are not another's") {
        forAll(genUniqueString) { seed =>
            val mine = s"mine-$seed"
            val theirs = s"theirs-$seed"
            val said = NotificationPreferences.unset.updated(NotificationType.MatchEnded, Some(false))

            val result = for {
                _ <- services.registration.register(s"mine-$seed", mine, None)
                _ <- services.registration.register(s"theirs-$seed", theirs, None)
                game <- makeGame(seed)
                _ <- services.notifications.updateMine(mine, said)
                _ <- services.notifications.updateForGame(mine, game.gameId, said)
                ours <- services.notifications.mine(mine)
                other <- services.notifications.mine(theirs)
            } yield ours.player == said && other.player == NotificationPreferences.unset && other.games.isEmpty
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // A bad game id is the caller's mistake, and a 404 tells them so. Left to the foreign key it
    // would be a constraint violation, which reaches them as a 500 about nothing they can act on.
    property("settings for a game that does not exist are refused as not found") {
        forAll(genUniqueString) { seed =>
            val caller = s"nogame-$seed"
            val result = for {
                _ <- services.registration.register(s"nogame-$seed", caller, None)
                outcome <- services.notifications
                    .updateForGame(caller, GameId(-1), NotificationPreferences.unset)
                    .attempt
            } yield outcome.left.exists(_.isInstanceOf[NotFoundError])
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Asking about a match you are not in says the same thing as asking about one that is not there,
    // and deliberately so: the alternative distinguishes them, which tells a stranger that somebody
    // else's match exists.
    property("a match the caller has no seat in is not found") {
        forAll(genUniqueString) { seed =>
            val caller = s"noseat-$seed"
            val result = for {
                _ <- services.registration.register(s"noseat-$seed", caller, None)
                game <- makeGame(seed)
                read <- services.notifications.forMatch(caller, game.gameId, MatchId(s"absent-$seed")).attempt
                written <- services.notifications
                    .updateForMatch(caller, game.gameId, MatchId(s"absent-$seed"), SeatNotifications.all(true))
                    .attempt
            } yield read.left.exists(_.isInstanceOf[NotFoundError]) &&
                written.left.exists(_.isInstanceOf[NotFoundError])
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Every route here acts on the caller's own settings, so a caller who is not a player has
    // nothing to act on. Unauthorized rather than NotFound: the identity is real, it is just not a
    // player yet -- the same distinction `AcceptanceService.mine` makes.
    property("a caller with no player is refused") {
        forAll(genUniqueString) { seed =>
            val result = services.notifications.mine(s"stranger-$seed").attempt.map {
                _.left.exists(_.isInstanceOf[UnauthorizedError])
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }
}
