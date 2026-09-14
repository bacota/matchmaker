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
  * What the mail *says* is `MatchStartedMailSpec`'s business, and needs no match at all. This is the other half: a real
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

    // The most specific level cannot be set before the match it is about exists -- a participant row
    // is what a start creates -- so this is what there is to check about it here: that a player in a
    // match can say something about it, and that it is their answer that comes back.
    property("a player in a match can set and read their preferences for it") {
        forAll(genUniqueString) { seed =>
            val notifier = new RecordingNotifier
            val services = TestServices.servicesWith(
              new StubEngine,
              callbackBaseUrl = Some("https://matchmaker.example.com"),
              notifier = notifier,
              mail = TestServices.mailSettings
            )
            val muted = NotificationPreferences.unset.updated(NotificationType.TurnTaken, Some(false))

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
            } yield before == NotificationPreferences.unset && after == muted &&
                challenger == NotificationPreferences.unset
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
                _ <- services.engine.start(game.gameId, challenge.challengeId, challengerId)
            } yield notifier.messages.isEmpty
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }
}
