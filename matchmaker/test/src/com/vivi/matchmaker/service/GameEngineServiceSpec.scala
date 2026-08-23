package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import org.scalacheck.Gen
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, ParticipantRepo, ResultRepo, TestSession}

/** The game-engine interaction, end to end against the real database with a stubbed engine.
  *
  * The engine is the one thing that cannot be stood up here — it is another system entirely — so
  * it is a stub that records what it was asked and answers with fixed urls. Everything on
  * matchmaker's side of the exchange is real: the challenge, its acceptances, the match and
  * participants written from them, and the result rows the completion callback produces.
  */
class GameEngineServiceSpec extends PropertySuite {
  TestMigration.ensure()

  /** Records the last create request so the tests can assert on what the engine was told. */
  private class StubEngine(
      response: CreateGameResponse = CreateGameResponse("https://engine/status/1", "https://engine/play/1", None),
      var status: GameStatusResponse = GameStatusResponse(completed = false, participants = Nil),
      fail: Boolean = false
  ) extends GameEngineClient {
    @volatile var lastRequest: Option[CreateGameRequest] = None
    @volatile var lastUrl: Option[String] = None

    def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
      if (fail) IO.raiseError(GameEngineError("engine is down"))
      else IO { lastRequest = Some(request); lastUrl = Some(gameUrl) }.as(response)

    def status(statusUrl: String): IO[GameStatusResponse] = IO.pure(status)
  }

  /** An engine whose createGame parks until it is released, so a test can hold a start in the
    * window between its first transaction committing and its last one running — which is exactly
    * the window a second Start used to slip through.
    */
  private class GatedEngine(entered: Deferred[IO, Unit], release: Deferred[IO, Unit]) extends GameEngineClient {
    def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
      entered.complete(()).attempt *> release.get.as(
        CreateGameResponse("https://engine/status/1", "https://engine/play/1", None)
      )

    def status(statusUrl: String): IO[GameStatusResponse] = IO.pure(GameStatusResponse(completed = false, participants = Nil))
  }

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private case class Fixture(owner: Player, game: Game, character: Character[String])

  private def makeFixture(nickname: String, externalId: String, gameExternalId: String): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- TestServices.services.registration.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(
            GameId.unassigned,
            GameType.Character,
            "game",
            "description",
            "https://engine.example.com/games",
            active = true,
            Seq(GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false)),
            Seq.empty,
            gameExternalId,
            // One player is enough to start, which keeps these tests to a challenger and their
            // own implicit acceptance unless a second player is what is being tested.
            minPlayers = 1,
            maxPlayers = 4
          )
        )
        character <- new CharacterRepo[String](session).create(
          Character(CharacterId(0), game.gameId, "character", "description", "", Some(owner.playerId))
        )
      } yield Fixture(owner, game, character)
    }

  private def challengeFor(
      fixture: Fixture,
      isPublic: Boolean = false,
      timeLimit: Option[Duration] = None
  ): OpenChallenge =
    CharacterOpenChallenge(
      ChallengeId(0),
      fixture.owner.playerId,
      "message",
      numberOfPlayers = 2,
      start = None,
      timeLimit = timeLimit,
      settings = "{}",
      gameId = fixture.game.gameId,
      characterId = fixture.character.characterId,
      isPublic = isPublic,
      gameRoleId = fixture.game.roles.headOption.map(_.gameRoleId)
    )

  private def participantsOf(m: Match): IO[List[Participant]] =
    TestSession.resource.use(session => new ParticipantRepo(session).listForMatch(m.gameId, m.matchId).map(_.map(_._1)))

  property("start creates the match from the engine's answer, with one participant per acceptance") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val engine = StubEngine()
      val services = TestServices.servicesWith(engine, callbackBaseUrl = Some("https://matchmaker.example.com"))
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture, isPublic = true), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
        // The challenge is retired by starting it, so it can never become a second match.
        remaining <- services.challenges.listByGame(fixture.game.gameId, externalId)
      } yield {
        val request = engine.lastRequest.get
        started.statusUrl.contains("https://engine/status/1") &&
        started.playUrl.contains("https://engine/play/1") &&
        started.isPublic &&
        engine.lastUrl.contains(fixture.game.url) &&
        request.isPublic &&
        request.matchId == started.matchId.value &&
        // The engine is told the participant ids it will quote back in its callbacks, the
        // player's Cognito id, and the role they are playing.
        request.players.map(_.participantId).toSet == participants.map(_.participantId.value).toSet &&
        request.players.map(_.cognitoId) == List(externalId) &&
        request.players.flatMap(_.role) == List("attacker") &&
        request.moveCallbackUrl.exists(_.endsWith(s"/matches/${started.matchId.value}/moves")) &&
        participants.size == 1 &&
        participants.head.playerId == fixture.owner.playerId &&
        remaining.forall(_.challengeId != challenge.challengeId)
      }
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("start refuses a caller who is not the challenger") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val services = TestServices.servicesWith(StubEngine())
        val result = for {
          fixture <- makeFixture(nickname, externalId, gameExternalId)
          _ <- services.registration.register(s"other-$nickname", otherExternalId)
          challenge <- services.challenges.create(challengeFor(fixture), externalId)
          attempt <- services.engine.start(fixture.game.gameId, challenge.challengeId, otherExternalId).attempt
        } yield attempt.left.exists(_.isInstanceOf[UnauthorizedError])
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // A game the engine never created must not leave a match behind: the challenge stays open and
  // the challenger can try again once the engine is back.
  property("a failed engine call leaves no match and keeps the challenge") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(new StubEngine(fail = true))
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        attempt <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
        remaining <- services.challenges.listByGame(fixture.game.gameId, externalId)
        matches <- services.matches.active(externalId)
      } yield attempt.isLeft &&
        remaining.exists(_.challengeId == challenge.challengeId) &&
        matches.isEmpty
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The bug this guards: start cannot hold one transaction across the engine call, so the
  // challenge's FOR UPDATE lock is released long before the challenge is deleted. A second Start
  // arriving in that window used to re-read a challenge that still looked startable, pass every
  // check again, and produce a second match and a second engine game. The engine here is parked
  // mid-call to put the second attempt squarely in that window.
  property("a second start while the first is still in the engine is refused, leaving one match") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val result = for {
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        services = TestServices.servicesWith(new GatedEngine(entered, release))
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        first <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).start
        // The first start has written and committed its claim and is now inside the engine call.
        _ <- entered.get
        second <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
        _ <- release.complete(())
        started <- first.joinWithNever
        matches <- services.matches.active(externalId)
      } yield second.left.exists(_.isInstanceOf[ConflictError]) &&
        matches.size == 1 &&
        matches.head.matchId == started.matchId
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The claim the fix takes must not outlive a failed start, or the engine being briefly down
  // would strand the challenge as permanently unstartable.
  property("a start after a failed engine call succeeds, because the failure released the claim") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val failing = TestServices.servicesWith(new StubEngine(fail = true))
      val working = TestServices.servicesWith(StubEngine())
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- failing.challenges.create(challengeFor(fixture), externalId)
        attempt <- failing.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
        retried <- working.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        matches <- working.matches.active(externalId)
      } yield attempt.isLeft &&
        retried.playUrl.contains("https://engine/play/1") &&
        matches.size == 1
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("a move callback moves the turn on, and is authorized by the game's external id") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(StubEngine())
      val prevMoveAt = Instant.parse("2030-01-01T00:00:00Z")
      // The engine reports when the move was made; the deadline for whoever moves next is that
      // plus the match's time limit.
      val due = prevMoveAt.plusSeconds(300)
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture, timeLimit = Some(Duration.ofMinutes(5))), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
        seat = participants.head.participantId
        // A player's own token is not a game's secret, and the callback is the game's route.
        refused <- services.engine.recordMove(fixture.game.gameId, started.matchId, seat, Nil, None, externalId).attempt
        _ <- services.engine.recordMove(fixture.game.gameId, started.matchId, seat, List(seat), Some(prevMoveAt), gameExternalId)
        after <- participantsOf(started)
      } yield refused.left.exists(_.isInstanceOf[UnauthorizedError]) &&
        after.head.pending &&
        after.head.due.contains(due)
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("a results callback completes the match and writes the result rows") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(StubEngine())
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
        seat = participants.head.participantId
        reported = ReportedResult(seat, rank = 1, scores = Map("points" -> 42.0), isWinner = true)
        _ <- services.engine.recordResults(fixture.game.gameId, started.matchId, List(reported), gameExternalId)
        stored <- TestSession.resource.use(session => new ResultRepo(session).read(fixture.game.gameId, seat))
        after <- participantsOf(started)
        completed <- services.matches.completed(externalId)
      } yield stored.contains(model.Result(fixture.game.gameId, seat, 1, Map("points" -> 42.0), true)) &&
        after.head.completed &&
        !after.head.pending &&
        completed.exists(_.matchId == started.matchId)
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("refresh applies what the engine reports, for a participant") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val prevMoveAt = Instant.parse("2031-02-03T04:05:00Z")
        // The engine says when the turn started; the deadline is that plus the match's own time
        // limit, which came from the challenge below.
        val due = prevMoveAt.plusSeconds(600)
        val result = for {
          fixture <- makeFixture(nickname, externalId, gameExternalId)
          _ <- services.registration.register(s"other-$nickname", otherExternalId)
          challenge <- services.challenges.create(challengeFor(fixture, timeLimit = Some(Duration.ofMinutes(10))), externalId)
          started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
          participants <- participantsOf(started)
          seat = participants.head.participantId
          _ <- IO {
            engine.status = GameStatusResponse(
              completed = false,
              participants = List(EngineParticipantStatus(seat.value, pending = true, completed = false, prevMoveAt = Some(prevMoveAt)))
            )
          }
          // Only someone playing the match may ask about it.
          refused <- services.engine.refresh(fixture.game.gameId, started.matchId, otherExternalId).attempt
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, externalId)
          after <- participantsOf(started)
        } yield refused.left.exists(_.isInstanceOf[UnauthorizedError]) &&
          !refreshed.completed &&
          after.head.pending &&
          after.head.due.contains(due)
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // A match with no time limit has no deadline to compute, whatever the engine reports as the
  // start of the turn — there is nothing for the player to run out of.
  property("refresh leaves the due date unset when the match has no time limit") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val engine = StubEngine()
      val services = TestServices.servicesWith(engine)
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture, timeLimit = None), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
        seat = participants.head.participantId
        _ <- IO {
          engine.status = GameStatusResponse(
            completed = false,
            participants = List(
              EngineParticipantStatus(
                seat.value,
                pending = true,
                completed = false,
                prevMoveAt = Some(Instant.parse("2031-02-03T04:05:00Z"))
              )
            )
          )
        }
        _ <- services.engine.refresh(fixture.game.gameId, started.matchId, externalId)
        after <- participantsOf(started)
      } yield after.head.pending && after.head.due.isEmpty
      result.timeout(15.seconds).unsafeRunSync()
    }
  }
}
