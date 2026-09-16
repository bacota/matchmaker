package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.Instant
import org.scalacheck.{Gen, Shrink}
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.RecordingNotifier
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

/** Who is written to when a match begins, and what happens when the queue will not take it.
  *
  * What the mail *says* is `MatchMailSpec`'s business, and needs no match at all. This is the other half: a real
  * challenge, real acceptances and a real start, with the queue recorded rather than sent to.
  */
class MatchStartedNotificationSpec extends PropertySuite {
    TestMigration.ensure()

    // As in GameEngineServiceSpec: every case builds a fixture in the database, so shrinking a
    // failure runs hundreds of registrations to learn nothing about an opaque unique string.
    private given noShrink[A]: Shrink[A] = Shrink.shrinkAny

    /* A ceiling on a case that has hung, not a statement about how long one should take: a case
     * builds a game, two registrations, a challenge, an acceptance and a start, and runs alongside
     * three other test workers on a cold jvm, where several seconds is ordinary. Generous enough
     * that only a case which is never going to finish trips it. */
    private val caseTimeout = 60.seconds

    private class StubEngine extends GameEngineClient {
        def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
            IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

        def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(GameStatusResponse(completed = false, participants = Nil))
    }

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    /* A two-role game: one required role for the challenger and one more for a second player, so
     * that a match can have somebody in it who is not the person who pressed Start. */
    private def makeGame(
        gameExternalId: String,
        notifications: NotificationDefaults = NotificationDefaults.all(true)
    ): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "Tic-Tac-Toe",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false),
                  GameRole(GameRoleId(0), GameId.unassigned, "defender", optional = true)
                ),
                Seq.empty,
                gameExternalId,
                notifications = notifications
              )
            )
        }

    /** Builds a challenge in a new game, accepted by a second player, and starts it.
      *
      * @param challengerEmail
      *   the address the challenger registered with, if any
      * @param accepterEmail
      *   the address the other player registered with, if any
      * @param gameDefaults
      *   what the game says its players should hear about, which is the bottom of the chain in `NotificationPolicy`
      * @param beforeStart
      *   a chance to say something about notifications before the match exists — the accepting player and the game are
      *   handed over, because every level of the chain above the game's defaults is keyed by one or both
      */
    private def startedMatch(
        seed: String,
        notifier: RecordingNotifier,
        challengerEmail: Option[String],
        accepterEmail: Option[String],
        gameDefaults: NotificationDefaults = NotificationDefaults.all(true),
        beforeStart: (Services[String], Player, Game) => IO[Unit] = (_, _, _) => IO.unit
    ): IO[Match] = {
        val services = TestServices.servicesWith(
          new StubEngine,
          callbackBaseUrl = Some("https://matchmaker.example.com"),
          notifier = notifier,
          mail = TestServices.mailSettings
        )
        val challengerId = s"challenger-$seed"
        val accepterId = s"accepter-$seed"

        for {
            game <- makeGame(s"game-$seed", gameDefaults)
            challenger <- services.registration.register(s"challenger-$seed", challengerId, challengerEmail)
            accepter <- services.registration.register(s"accepter-$seed", accepterId, accepterEmail)
            challenge <- services.challenges.create(
              PlainOpenChallenge(
                ChallengeId(0),
                challenger.playerId,
                "friendly game",
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                isPublic = true,
                gameRoleId = game.roles.head.gameRoleId,
                timeLimitKind = TimeLimitKind.PerTurn,
                timeLimitUnit = TimeLimitUnit.Minutes
              ),
              challengerId
            )
            _ <- services.challenges.accept(
              game.gameId,
              challenge.challengeId,
              None,
              game.roles(1).gameRoleId,
              accepterId
            )
            _ <- beforeStart(services, accepter, game)
            // The fixture above has sent mail of its own: an acceptance is news to the challenger,
            // who is told about it (and, since this challenge is then full, told that it is ready to
            // start). Every property here is about what the *start* sends, so the record is cleared
            // at the moment the start begins. `ChallengeNotificationSpec` is where the accept's own
            // mail is the subject.
            _ <- IO(notifier.clear())
            started <- services.engine.start(game.gameId, challenge.challengeId, challengerId)
        } yield started
    }

    property("starting a match writes to everyone in it but the challenger") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com")
            ).map { _ =>
                // One mail, to the player who did not press Start. The challenger is looking at the
                // answer to their own click and does not need telling.
                notifier.recipients == Set(s"accepter-$seed@example.com") &&
                notifier.messages.forall(_.sender == "matchmaker@example.com") &&
                notifier.messages.forall(_.subject == "Your Tic-Tac-Toe match has started") &&
                // The challenger is named as a fellow player in the mail the other one receives.
                notifier.messages.forall(_.body.contains(s"Playing with you: challenger-$seed."))
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The nullable column exists so this question has an answer. A player with no address is
    // skipped, not sent an empty one, and does not stop anybody else's mail.
    property("a player with no address is skipped") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result =
                startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = None
                )
                    .map(_ => notifier.messages.isEmpty)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The whole reason notification is the last thing `start` does: the match exists, the engine's
    // game exists, and the challenger is owed an answer whatever the queue is doing.
    property("a queue that will not take the mail does not fail the start") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier(fail = true)
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com")
            ).map(started => started.statusUrl.contains("https://engine/status/1"))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The four levels of `NotificationPolicy`, exercised where they are actually read: a player
     * with an address, in a match that starts, who does or does not hear about it.
     *
     * Each of these says the same thing the pure spec says, once, over real rows -- so that a
     * resolution that is correct in the model but reading the wrong column, or reading nothing at
     * all, cannot pass. The accepter is the recipient throughout, because the challenger is never
     * written to. */
    property("a player who has turned match-started off is not written to") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com"),
              beforeStart = (services, accepter, _) =>
                  services.notifications.updateMine(
                    accepter.externalId,
                    NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(false))
                  )
            ).map(_ => notifier.messages.isEmpty)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Turning off a different kind leaves this one alone: the eight columns are bound positionally,
    // so a binding out of step would silently answer one question with another's answer.
    property("turning off a different notification does not stop this one") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com"),
              beforeStart = (services, accepter, _) =>
                  services.notifications.updateMine(
                    accepter.externalId,
                    NotificationType.values.toSeq
                        .filter(_ != NotificationType.MatchStarted)
                        .foldLeft(NotificationPreferences.unset)((p, kind) => p.updated(kind, Some(false)))
                  )
            ).map(_ => notifier.recipients == Set(s"accepter-$seed@example.com"))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The bottom of the chain, and the only level that cannot abstain: a game that asks for silence
    // gets it from a player who has said nothing.
    property("a game whose defaults are silent sends nothing") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com"),
              gameDefaults = NotificationDefaults.all(false)
            ).map(_ => notifier.messages.isEmpty)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // And the other direction, which is the point of the chain rather than a flag: a player may ask
    // to hear about something the game would not have told them about.
    property("a player may ask to hear about a game that would not have told them") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com"),
              gameDefaults = NotificationDefaults.all(false),
              beforeStart = (services, accepter, _) =>
                  services.notifications.updateMine(
                    accepter.externalId,
                    NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(true))
                  )
            ).map(_ => notifier.recipients == Set(s"accepter-$seed@example.com"))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The middle level, and the one that needs a row of its own: what the player says about this
    // game beats what they say in general, in both directions.
    property("what a player says about one game beats what they say in general") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val result = startedMatch(
              seed,
              notifier,
              challengerEmail = Some(s"challenger-$seed@example.com"),
              accepterEmail = Some(s"accepter-$seed@example.com"),
              beforeStart = (services, accepter, game) =>
                  services.notifications.updateMine(
                    accepter.externalId,
                    NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(true))
                  ) *> services.notifications.updateForGame(
                    accepter.externalId,
                    game.gameId,
                    NotificationPreferences.unset.updated(NotificationType.MatchStarted, Some(false))
                  )
            ).map(_ => notifier.messages.isEmpty)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // A seat cannot be set before the match it belongs to exists -- a participant row is what a start
    // creates -- so this is what there is to check about it here: that a seat starts out saying what
    // the chain said when it was stamped, that its player can change it, and that changing it is their
    // answer alone.
    property("a player in a match can set and read their preferences for it") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val muted = NotificationDefaults.all(true).copy(turnTaken = false)

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com")
                )
                accepterId = s"accepter-$seed"
                before <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                _ <- services.notifications.updateForMatch(accepterId, started.gameId, started.matchId, muted)
                after <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                // The match level is the player's own: the challenger, who is also in this match, is
                // untouched by it.
                challenger <- services.notifications
                    .forMatch(s"challenger-$seed", started.gameId, started.matchId)
                // Stamped from the chain at the start, which in this fixture is the game's own
                // defaults: every kind answered, nothing unsaid, and so nothing the form has to fill in.
            } yield before == NotificationDefaults.all(true) && after == muted &&
                challenger == NotificationDefaults.all(true)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* What making the seat the answer actually buys, and the offer that gives it back.
     *
     * A player who changes a game's settings in March has not asked to change what the matches they
     * are already in send them; a player who ticks the box has. Both directions are checked over real
     * rows, because the difference between them is two SQL statements and nothing in the model. */
    property("a later change to a game's settings leaves a running match alone") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val accepterId = s"accepter-$seed"

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com")
                )
                _ <- services.notifications.updateForGame(
                  accepterId,
                  started.gameId,
                  NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false))
                )
                seat <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                // And the level they changed did take the change: this is the match not hearing it,
                // not the write going nowhere.
                settings <- services.notifications.mine(accepterId)
            } yield seat == NotificationDefaults.all(true) &&
                settings.games.exists(g => g.gameId == started.gameId && g.preferences.turnTaken.contains(false))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    property("asking for a game's settings to reach current matches re-stamps the seat") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val accepterId = s"accepter-$seed"

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com")
                )
                _ <- services.notifications.updateForGame(
                  accepterId,
                  started.gameId,
                  NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false)),
                  applyToMatches = true
                )
                seat <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                // The other seven come from the chain as it now stands, not from what the request
                // named: the one question they answered is the only one that moved.
                challenger <- services.notifications
                    .forMatch(s"challenger-$seed", started.gameId, started.matchId)
            } yield seat == NotificationDefaults.all(true).copy(turnTaken = false) &&
                challenger == NotificationDefaults.all(true)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The same offer from the defaults form, which reaches every game rather than one -- and reaches
    // the seat through a `player_game` row that has just been aligned with it, which is why the
    // service aligns the games before it re-stamps the seats. A game that had answered this very
    // question differently is the case that tells the two orders apart: aligned first, the seat ends
    // up on the new default; re-stamped first, it would end up back on the game's old answer.
    property("asking for new defaults to reach every game and every current match re-stamps the seat") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val accepterId = s"accepter-$seed"

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com"),
                  // Said before the start, so the seat is stamped with it and the game has an answer
                  // of its own for the cascade to have to overwrite.
                  beforeStart = (services, accepter, game) =>
                      services.notifications.updateForGame(
                        accepter.externalId,
                        game.gameId,
                        NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(true))
                      )
                )
                _ <- services.notifications.updateMine(
                  accepterId,
                  NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false)),
                  applyToGames = true,
                  applyToMatches = true
                )
                seat <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                settings <- services.notifications.mine(accepterId)
            } yield seat == NotificationDefaults.all(true).copy(turnTaken = false) &&
                settings.games.exists(g => g.gameId == started.gameId && g.preferences.turnTaken.contains(false))
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The reason a cascade carries the change rather than the form.
     *
     * A player mutes one match's results, then changes something else about the game and asks for it
     * to reach the matches they are in. The question they changed moves; the mute does not. Writing
     * all eight columns from the chain would have unmuted it, which is what this is here to catch. */
    property("a cascade leaves the questions it did not change alone in the seat") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val accepterId = s"accepter-$seed"

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com")
                )
                // Said about this one match, and about nothing else.
                _ <- services.notifications.updateForMatch(
                  accepterId,
                  started.gameId,
                  started.matchId,
                  NotificationDefaults.all(true).copy(matchEnded = false)
                )
                _ <- services.notifications.updateForGame(
                  accepterId,
                  started.gameId,
                  NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false)),
                  applyToMatches = true
                )
                seat <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
            } yield seat == NotificationDefaults.all(true).copy(turnTaken = false, matchEnded = false)
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // And the same for the defaults form, which cascades through `player_game` on its way down: the
    // game's own answer to a question this save did not touch is still the game's answer, and the
    // seat's own answer to another is still the seat's.
    property("a cascade from the defaults leaves untouched questions alone at both levels") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val accepterId = s"accepter-$seed"

            val result = for {
                started <- startedMatch(
                  seed,
                  notifier,
                  challengerEmail = Some(s"challenger-$seed@example.com"),
                  accepterEmail = Some(s"accepter-$seed@example.com"),
                  beforeStart = (services, accepter, game) =>
                      services.notifications.updateForGame(
                        accepter.externalId,
                        game.gameId,
                        NotificationPreferences.unset.updated(NotificationType.MatchEnded, Some(false))
                      )
                )
                _ <- services.notifications.updateMine(
                  accepterId,
                  NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false)),
                  applyToGames = true,
                  applyToMatches = true
                )
                seat <- services.notifications.forMatch(accepterId, started.gameId, started.matchId)
                settings <- services.notifications.mine(accepterId)
            } yield
            // match-ended was never part of this save, so the game still says no to it and the seat,
            // stamped with that same no before the start, still holds it.
            seat == NotificationDefaults.all(true).copy(turnTaken = false, matchEnded = false) &&
                settings.games.exists(g =>
                    g.gameId == started.gameId && g.preferences.matchEnded.contains(false) &&
                        g.preferences.turnTaken.contains(false)
                )
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // An environment with no sender and no link cannot say anything useful, so it says nothing --
    // which is what keeps every other spec, and the local server, silent without a special case.
    property("an environment with no mail settings sends nothing") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(new StubEngine, notifier = notifier)
            val challengerId = s"quiet-challenger-$seed"

            val result = for {
                game <- makeGame(s"quiet-game-$seed")
                challenger <- services.registration.register(
                  s"quiet-challenger-$seed",
                  challengerId,
                  Some(s"quiet-$seed@example.com")
                )
                accepterId = s"quiet-accepter-$seed"
                _ <- services.registration.register(
                  s"quiet-accepter-$seed",
                  accepterId,
                  Some(s"quiet-a-$seed@example.com")
                )
                challenge <- services.challenges.create(
                  PlainOpenChallenge(
                    ChallengeId(0),
                    challenger.playerId,
                    "friendly game",
                    start = None,
                    timeLimit = None,
                    settings = "{}",
                    gameId = game.gameId,
                    isPublic = true,
                    gameRoleId = game.roles.head.gameRoleId,
                    timeLimitKind = TimeLimitKind.PerTurn,
                    timeLimitUnit = TimeLimitUnit.Minutes
                  ),
                  challengerId
                )
                _ <- services.challenges.accept(
                  game.gameId,
                  challenge.challengeId,
                  None,
                  game.roles(1).gameRoleId,
                  accepterId
                )
                _ <- IO(notifier.clear())
                _ <- services.engine.start(game.gameId, challenge.challengeId, challengerId)
            } yield notifier.messages.isEmpty
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }
}
