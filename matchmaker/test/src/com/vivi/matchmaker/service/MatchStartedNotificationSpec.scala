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
  * What the mail *says* is `MatchStartedMailSpec`'s business, and needs no match at all. This is
  * the other half: a real challenge, real acceptances and a real start, with the queue recorded
  * rather than sent to.
  */
class MatchStartedNotificationSpec extends PropertySuite {
  TestMigration.ensure()

  // As in GameEngineServiceSpec: every case builds a fixture in the database, so shrinking a
  // failure runs hundreds of registrations to learn nothing about an opaque unique string.
  private given noShrink[A]: Shrink[A] = Shrink.shrinkAny

  private class StubEngine extends GameEngineClient {
    def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
      IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

    def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] =
      IO.pure(GameStatusResponse(completed = false, participants = Nil))
  }

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  /* A two-role game: one required role for the challenger and one more for a second player, so
   * that a match can have somebody in it who is not the person who pressed Start. */
  private def makeGame(gameExternalId: String): IO[Game] =
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
          gameExternalId
        )
      )
    }

  /** Builds a challenge in a new game, accepted by a second player, and starts it.
    *
    * @param challengerEmail the address the challenger registered with, if any
    * @param accepterEmail   the address the other player registered with, if any
    */
  private def startedMatch(
      seed: String,
      notifier: RecordingNotifier,
      challengerEmail: Option[String],
      accepterEmail: Option[String]
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
      game <- makeGame(s"game-$seed")
      challenger <- services.registration.register(s"challenger-$seed", challengerId, challengerEmail)
      _ <- services.registration.register(s"accepter-$seed", accepterId, accepterEmail)
      challenge <- services.challenges.create(
        PlainOpenChallenge(
          ChallengeId(0), challenger.playerId, "friendly game", start = None, timeLimit = None, settings = "{}",
          gameId = game.gameId, isPublic = true, gameRoleId = game.roles.head.gameRoleId,
          timeLimitKind = TimeLimitKind.PerTurn, timeLimitUnit = TimeLimitUnit.Minutes
        ),
        challengerId
      )
      _ <- services.challenges.accept(game.gameId, challenge.challengeId, None, game.roles(1).gameRoleId, accepterId)
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
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The nullable column exists so this question has an answer. A player with no address is
  // skipped, not sent an empty one, and does not stop anybody else's mail.
  property("a player with no address is skipped") {
    forAll(genUniqueString) { seed =>
      val notifier = new RecordingNotifier
      val result =
        startedMatch(seed, notifier, challengerEmail = Some(s"challenger-$seed@example.com"), accepterEmail = None)
          .map(_ => notifier.messages.isEmpty)
      result.timeout(15.seconds).unsafeRunSync()
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
      result.timeout(15.seconds).unsafeRunSync()
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
          s"quiet-challenger-$seed", challengerId, Some(s"quiet-$seed@example.com")
        )
        accepterId = s"quiet-accepter-$seed"
        _ <- services.registration.register(s"quiet-accepter-$seed", accepterId, Some(s"quiet-a-$seed@example.com"))
        challenge <- services.challenges.create(
          PlainOpenChallenge(
            ChallengeId(0), challenger.playerId, "friendly game", start = None, timeLimit = None, settings = "{}",
            gameId = game.gameId, isPublic = true, gameRoleId = game.roles.head.gameRoleId,
            timeLimitKind = TimeLimitKind.PerTurn, timeLimitUnit = TimeLimitUnit.Minutes
          ),
          challengerId
        )
        _ <- services.challenges.accept(game.gameId, challenge.challengeId, None, game.roles(1).gameRoleId, accepterId)
        _ <- services.engine.start(game.gameId, challenge.challengeId, challengerId)
      } yield notifier.messages.isEmpty
      result.timeout(15.seconds).unsafeRunSync()
    }
  }
}
