package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import org.scalacheck.{Gen, Shrink}
import skunk.implicits._
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, GameRepo, OpenChallengeRepo, ParticipantRepo, ResultRepo, TestSession, TurnRepo}

/** The game-engine interaction, end to end against the real database with a stubbed engine.
  *
  * The engine is the one thing that cannot be stood up here — it is another system entirely — so
  * it is a stub that records what it was asked and answers with fixed urls. Everything on
  * matchmaker's side of the exchange is real: the challenge, its acceptances, the match and
  * participants written from them, and the result rows the completion callback produces.
  */
class GameEngineServiceSpec extends PropertySuite {
  TestMigration.ensure()

  // Every generator here feeds a fixture built in the database, so a shrink candidate is not a
  // cheap retry — it is another round of registrations and inserts. Shrinking a failure of one
  // of these properties runs hundreds of them and buries the failure it was meant to report.
  // The inputs are opaque unique strings that shrink tells us nothing about anyway.
  private given noShrink[A]: Shrink[A] = Shrink.shrinkAny

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

    def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] = IO.pure(status)
  }

  /** An engine that answers a status call by naming the seat it was given first as the one to
    * move, which is what a real engine says about a match that has only just begun. The stub
    * above cannot do this: the participant ids do not exist until `start` has written them, so
    * the answer has to be built from the create request rather than set up in advance.
    */
  private class FirstTurnEngine extends GameEngineClient {
    @volatile private var firstSeat: Option[Long] = None

    def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
      IO { firstSeat = request.players.headOption.map(_.participantId) }
        .as(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

    def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] =
      IO.pure(
        GameStatusResponse(
          completed = false,
          participants =
            firstSeat.toList.map(EngineParticipantStatus(_, pending = true, completed = false, prevMoveAt = None))
        )
      )
  }

  /** An engine whose *first* createGame parks until it is released, so a test can hold a start in
    * the window between its first transaction committing and its last one running — which is
    * exactly the window a second Start used to slip through.
    *
    * Only the first call parks. A later one answers at once, so that a regression shows up as an
    * assertion about how many matches exist rather than as a test that deadlocks against its own
    * gate and has to be timed out.
    */
  private class GatedEngine(entered: Deferred[IO, Unit], release: Deferred[IO, Unit]) extends GameEngineClient {
    private val response = CreateGameResponse("https://engine/status/1", "https://engine/play/1", None)
    @volatile var calls: Int = 0

    def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
      IO { calls += 1; calls == 1 }.flatMap { first =>
        if (first) entered.complete(()).attempt *> release.get.as(response)
        else IO.pure(response)
      }

    def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] = IO.pure(GameStatusResponse(completed = false, participants = Nil))
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
            // attacker is required, defender is not: a start waits for the first to be taken and
            // never for the second, which is what lets these tests start a match with the
            // challenger alone and still have a second role for a second player to accept as.
            Seq(
              GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false),
              GameRole(GameRoleId(0), GameId.unassigned, "defender", optional = true)
            ),
            Seq.empty,
            gameExternalId
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
      timeLimit: Option[Duration] = None,
      message: String = "message",
      timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
      start: Option[Instant] = None
  ): OpenChallenge =
    CharacterOpenChallenge(
      ChallengeId(0),
      fixture.owner.playerId,
      message,
      start = start,
      timeLimit = timeLimit,
      settings = "{}",
      gameId = fixture.game.gameId,
      characterId = fixture.character.characterId,
      isPublic = isPublic,
      gameRoleId = fixture.game.roles.head.gameRoleId,
      timeLimitKind = timeLimitKind
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
        remaining.forall(_.challenge.challengeId != challenge.challengeId)
      }
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("start refuses a challenge with a required role nobody has taken") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(StubEngine())
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        // The challenger takes 'defender', which is optional, leaving the required 'attacker'
        // with nobody playing it — the only thing standing between this challenge and a match.
        challenge <- services.challenges.create(
          challengeFor(fixture) match {
            case c: CharacterOpenChallenge => c.copy(gameRoleId = fixture.game.roles(1).gameRoleId)
            case other                     => other
          },
          externalId
        )
        attempt <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
      } yield attempt.left.exists {
        case e: ValidationError => e.getMessage.contains("attacker")
        case _                  => false
      }
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("accept refuses a role another acceptance has already taken") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val services = TestServices.servicesWith(StubEngine())
        val result = for {
          fixture <- makeFixture(nickname, externalId, gameExternalId)
          other <- services.registration.register(s"other-$nickname", otherExternalId)
          otherCharacter <- TestSession.resource.use { session =>
            new CharacterRepo[String](session)
              .create(Character(CharacterId(0), fixture.game.gameId, "other", "description", "", Some(other.playerId)))
          }
          // challengeFor puts the challenger on the first role, so asking for it again is asking
          // for a seat that is taken.
          challenge <- services.challenges.create(challengeFor(fixture), externalId)
          attempt <- services.challenges
            .accept(
              fixture.game.gameId, challenge.challengeId, Some(otherCharacter.characterId),
              fixture.game.roles.head.gameRoleId, otherExternalId
            )
            .attempt
        } yield attempt.left.exists(_.isInstanceOf[ConflictError])
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
        remaining.exists(_.challenge.challengeId == challenge.challengeId) &&
        matches.isEmpty
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The bug these guard: start cannot hold one transaction across the engine call, so the
  // challenge's FOR UPDATE lock is released long before the challenge is deleted. A second Start
  // arriving in that window used to re-read a challenge that still looked startable, pass every
  // check again, and produce a second match and a second engine game.
  //
  // Split in two on purpose. This one pins down *when* the claim exists — it parks the engine to
  // hold a start open and looks at the row underneath it — and deliberately makes no second
  // service call while parked, so it cannot deadlock against its own gate. The next one covers
  // what the claim then refuses.
  property("a start in flight leaves the challenge claimed for the whole engine call") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val result = for {
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        engine = new GatedEngine(entered, release)
        services = TestServices.servicesWith(engine)
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        first <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).start
        // The first start has committed its claim and is now inside the engine call — the window
        // a second Start used to slip through. A connection of its own, not the services' pool,
        // so this read cannot be waiting on the session the parked start is holding.
        claimed <- entered.get *> TestSession.resource.use { session =>
          new OpenChallengeRepo(session).readForUpdate(fixture.game.gameId, challenge.challengeId)
        }
        _ <- release.complete(())
        started <- first.joinWithNever
        matches <- services.matches.active(externalId)
      } yield claimed.flatMap(_.startedMatchId).contains(started.matchId) &&
        matches.size == 1 &&
        engine.calls == 1
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // What the claim refuses. Set directly rather than by parking a real start: the three
  // operations are guarded by the presence of the claim, so that is what the test establishes.
  property("while a challenge is claimed by a start, it can be neither started, accepted, deleted nor backed out of") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val services = TestServices.servicesWith(StubEngine())
        val result = for {
          fixture <- makeFixture(nickname, externalId, gameExternalId)
          other <- services.registration.register(s"other-$nickname", otherExternalId)
          otherCharacter <- TestSession.resource.use { session =>
            new CharacterRepo[String](session)
              .create(Character(CharacterId(0), fixture.game.gameId, "other", "description", "", Some(other.playerId)))
          }
          challenge <- services.challenges.create(challengeFor(fixture), externalId)
          // Accepted before the claim, so that backing out is something that would otherwise
          // succeed — the point is that the claim is what stops it, not a missing acceptance.
          _ <- services.challenges
            .accept(fixture.game.gameId, challenge.challengeId, Some(otherCharacter.characterId), fixture.game.roles(1).gameRoleId, otherExternalId)
          _ <- TestSession.resource.use { session =>
            new OpenChallengeRepo(session)
              .claimForStart(fixture.game.gameId, challenge.challengeId, MatchId("in-flight"))
          }
          restarted <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
          accepted <- services.challenges
            .accept(fixture.game.gameId, challenge.challengeId, Some(fixture.character.characterId), fixture.game.roles(1).gameRoleId, externalId)
            .attempt
          deleted <- services.challenges.delete(fixture.game.gameId, challenge.challengeId, externalId).attempt
          backedOut <- services.acceptances
            .delete(fixture.game.gameId, challenge.challengeId, other.playerId, otherExternalId)
            .attempt
          // Nothing the claim refused may have taken effect. Read from the repository rather than
          // from `mine`, which now hides a claimed challenge's acceptances: they are kept, but
          // they are no longer anything the player can act on, so the question here is only
          // whether the row survived the refused back-out.
          roster <- TestSession.resource.use { session =>
            new AcceptanceRepo(session).listForChallenge(fixture.game.gameId, challenge.challengeId)
          }
        } yield restarted.left.exists(_.isInstanceOf[ConflictError]) &&
          accepted.left.exists(_.isInstanceOf[ConflictError]) &&
          deleted.left.exists(_.isInstanceOf[ConflictError]) &&
          backedOut.left.exists(_.isInstanceOf[ConflictError]) &&
          roster.exists((acceptance, _, _) => acceptance.playerId == other.playerId)
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The claim must not outlive a failed start, or the engine being briefly down would strand the
  // challenge as permanently unstartable.
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

  /** A trigger that refuses to update the match whose description is `explode`, so that a test
    * can fail the database work that happens *after* the engine has created its game — the one
    * step of a start that cannot simply be rolled back.
    */
  // The engine's game exists by then, so the start cannot be undone, and the claim stays: a
  // challenge whose game exists must never be startable again, and the claim is now the permanent
  // mark of that rather than something to be cleaned up. What is left is the documented
  // recoverable state — a match with no urls, which `refresh` can fill in.
  property("a database failure after the engine call leaves the challenge spent, not startable again") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(StubEngine())
      // The trigger this leans on is installed once by `TestMigration`, not created here: DDL on
      // `match` locks the table against every other suite sharing the pool. It fires only on a
      // match described as 'explode', which is what the challenge below is for.
      val result = {
        for {
          fixture <- makeFixture(nickname, externalId, gameExternalId)
          // The message becomes the match's description, which is what the trigger keys on.
          challenge <- services.challenges.create(challengeFor(fixture, message = "explode"), externalId)
          attempt <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
          // Claimed, so no longer offered as an open challenge and no longer startable.
          remaining <- services.challenges.listByGame(fixture.game.gameId, externalId)
          restart <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId).attempt
          acceptances <- services.acceptances.mine(externalId)
          matches <- services.matches.active(externalId)
          // What is left behind is the documented recoverable state: a match with no urls.
          stranded <- matches.headOption match {
            case Some(m) => services.engine.read(fixture.game.gameId, m.matchId, externalId).map(Some(_))
            case None    => IO.pure(None)
          }
        } yield attempt.isLeft &&
          restart.isLeft &&
          remaining.forall(_.challenge.challengeId != challenge.challengeId) &&
          // The acceptances are kept with the challenge, but stop being things the player can
          // act on — nothing is left in their list to back out of.
          acceptances.forall(_.acceptance.challengeId != challenge.challengeId) &&
          matches.size == 1 &&
          stranded.exists(_.playUrl.isEmpty)
      }
      result.timeout(30.seconds).unsafeRunSync()
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
        // Not just that it is over, but when: the column is a timestamp, and a match completed
        // by this callback is stamped as the callback runs.
        reread <- services.engine.read(fixture.game.gameId, started.matchId, externalId)
      } yield stored.contains(model.Result(fixture.game.gameId, seat, 1, Map("points" -> 42.0), true)) &&
        after.head.completed &&
        !after.head.pending &&
        reread.completedAt.exists(at => !at.isBefore(started.start)) &&
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

  // The complaint this was written for: a match began and the player due to move first was never
  // told. Whose turn it is is the engine's to decide and every participant is written with
  // `pending = false`, so without asking, the match would sit in nobody's "waiting on you" list
  // until somebody happened to press Refresh on it.
  /* A two-seat match with a turn clock, started and left with the challenger to move.
   *
   * Both the timeout properties below need the same thing: a real match with somebody's clock
   * running, so that the only question the test asks is what happens when it runs out. */
  private def startedWithClock(
      services: Services[String],
      engine: StubEngine,
      nickname: String,
      externalId: String,
      gameExternalId: String,
      otherExternalId: String,
      prevMoveAt: Instant
  ): IO[(Fixture, Match, Participant, Participant)] =
    for {
      fixture <- makeFixture(nickname, externalId, gameExternalId)
      other <- services.registration.register(s"other-$nickname", otherExternalId)
      otherCharacter <- TestSession.resource.use { session =>
        new CharacterRepo[String](session)
          .create(Character(CharacterId(0), fixture.game.gameId, "other", "description", "", Some(other.playerId)))
      }
      challenge <- services.challenges.create(challengeFor(fixture, timeLimit = Some(Duration.ofMinutes(10))), externalId)
      _ <- services.challenges.accept(
        fixture.game.gameId, challenge.challengeId, Some(otherCharacter.characterId),
        fixture.game.roles(1).gameRoleId, otherExternalId
      )
      started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
      participants <- participantsOf(started)
      mine = participants.find(_.playerId == fixture.owner.playerId).get
      theirs = participants.find(_.playerId == other.playerId).get
      // The other player moved at `prevMoveAt`, so it is now the challenger's turn and their
      // deadline is that plus the match's ten minutes. Recorded through the move callback rather
      // than through a refresh, because a refresh is one of the two places the clock is
      // enforced — it would decide the very thing these tests are set up to ask.
      _ <- services.engine.recordMove(
        fixture.game.gameId,
        started.matchId,
        moved = theirs.participantId,
        next = List(mine.participantId),
        prevMoveAt = Some(prevMoveAt),
        callerExternalId = gameExternalId
      )
      // What the engine will say when it is asked: the same thing, so that by default the
      // status call confirms the deadline rather than overturning it.
      _ <- IO {
        engine.status = GameStatusResponse(
          completed = false,
          participants = List(
            EngineParticipantStatus(mine.participantId.value, pending = true, completed = false, prevMoveAt = Some(prevMoveAt)),
            EngineParticipantStatus(theirs.participantId.value, pending = false, completed = false, prevMoveAt = None)
          )
        )
      }
    } yield (fixture, started, mine, theirs)

  private def turnsOf(m: Match): IO[List[Turn]] =
    TestSession.resource.use(session => new TurnRepo(session).listForMatch(m.gameId, m.matchId))

  /* A two-seat match under a clock of a stated kind, started at a stated time, with two moves
   * already played through the move callback.
   *
   * The two moves are the point: a total limit is only different from a per-turn one once
   * somebody has spent part of their budget, so a fixture with no history behind it cannot tell
   * the two apart. `firstMoveAt` is when the challenger moved and `secondMoveAt` when the other
   * player replied, after which it is the challenger's turn again and their deadline is whatever
   * the match's kind of limit makes it. */
  private def playedTwice(
      services: Services[String],
      engine: StubEngine,
      nickname: String,
      externalId: String,
      gameExternalId: String,
      otherExternalId: String,
      matchStart: Instant,
      timeLimit: Duration,
      kind: TimeLimitKind,
      firstMoveAt: Instant,
      secondMoveAt: Instant
  ): IO[(Fixture, Match, Participant, Participant)] =
    for {
      fixture <- makeFixture(nickname, externalId, gameExternalId)
      other <- services.registration.register(s"other-$nickname", otherExternalId)
      otherCharacter <- TestSession.resource.use { session =>
        new CharacterRepo[String](session)
          .create(Character(CharacterId(0), fixture.game.gameId, "other", "description", "", Some(other.playerId)))
      }
      challenge <- services.challenges.create(
        challengeFor(fixture, timeLimit = Some(timeLimit), timeLimitKind = kind, start = Some(matchStart)),
        externalId
      )
      _ <- services.challenges.accept(
        fixture.game.gameId, challenge.challengeId, Some(otherCharacter.characterId),
        fixture.game.roles(1).gameRoleId, otherExternalId
      )
      started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
      participants <- participantsOf(started)
      mine = participants.find(_.playerId == fixture.owner.playerId).get
      theirs = participants.find(_.playerId == other.playerId).get
      // The challenger moves first, spending firstMoveAt - matchStart of their own budget...
      _ <- services.engine.recordMove(
        fixture.game.gameId, started.matchId, moved = mine.participantId, next = List(theirs.participantId),
        prevMoveAt = Some(firstMoveAt), callerExternalId = gameExternalId
      )
      // ...and the other player replies, which puts the challenger back on the clock.
      _ <- services.engine.recordMove(
        fixture.game.gameId, started.matchId, moved = theirs.participantId, next = List(mine.participantId),
        prevMoveAt = Some(secondMoveAt), callerExternalId = gameExternalId
      )
      // What the engine confirms when asked: exactly this, so a recheck upholds the deadline
      // rather than overturning it.
      _ <- IO {
        engine.status = GameStatusResponse(
          completed = false,
          participants = List(
            EngineParticipantStatus(mine.participantId.value, pending = true, completed = false, prevMoveAt = Some(secondMoveAt)),
            EngineParticipantStatus(theirs.participantId.value, pending = false, completed = false, prevMoveAt = None)
          )
        )
      }
    } yield (fixture, started, mine, theirs)

  private def resultsOf(m: Match): IO[List[model.Result]] =
    TestSession.resource.use { session =>
      val repo = new ResultRepo(session)
      new ParticipantRepo(session)
        .listForMatch(m.gameId, m.matchId)
        .flatMap(_.traverse((p, _, _) => repo.read(m.gameId, p.participantId)))
        .map(_.flatten)
    }

  // The whole point of the feature: a clock that ran out ends the match, the player who ran out
  // loses, and the other one wins by forfeit — recorded as such, so a completed-match list can
  // say which kind of win it was.
  property("a turn that has run out ends the match by forfeit") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          // An hour ago, against a ten-minute limit: fifty minutes over.
          prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { long_ago =>
            startedWithClock(services, engine, nickname, externalId, gameExternalId, otherExternalId, long_ago)
          }
          (fixture, started, mine, theirs) = prepared
          // Asked by the player who is *not* out of time — the clock is enforced for whoever
          // looks, not only against the person who looks.
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, otherExternalId)
          results <- resultsOf(started)
          loser = results.find(_.participantId == mine.participantId)
          winner = results.find(_.participantId == theirs.participantId)
          after <- participantsOf(started)
        } yield refreshed.completed &&
          results.forall(_.forfeit) &&
          loser.exists(r => !r.isWinner && r.rank == 2) &&
          winner.exists(r => r.isWinner && r.rank == 1) &&
          after.forall(p => p.completed && !p.pending && p.due.isEmpty)
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The verification step: matchmaker's copy of whose turn it is arrives by callback, and a
  // callback can be lost. A turn that looks overdue here but that the engine says has been taken
  // must not cost anybody the match.
  property("a turn the engine says was taken is not forfeited, however overdue it looked") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { long_ago =>
            startedWithClock(services, engine, nickname, externalId, gameExternalId, otherExternalId, long_ago)
          }
          (fixture, started, mine, theirs) = prepared
          // The move landed after all: it is now the other player's turn, and their clock has
          // only just started.
          justNow <- IO.realTimeInstant
          _ <- IO {
            engine.status = GameStatusResponse(
              completed = false,
              participants = List(
                EngineParticipantStatus(mine.participantId.value, pending = false, completed = false, prevMoveAt = None),
                EngineParticipantStatus(theirs.participantId.value, pending = true, completed = false, prevMoveAt = Some(justNow))
              )
            )
          }
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, externalId)
          results <- resultsOf(started)
        } yield !refreshed.completed && results.isEmpty
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The engine is the only witness to whether a turn was actually taken, so a status call that
  // fails is not evidence that it was not: ending somebody's match on it would be ending it on
  // nothing. The player keeps a turn they may have run out of, which is the recoverable error of
  // the two — the next successful check settles it either way.
  property("a turn is not forfeited while the engine cannot be reached to confirm it") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { longAgo =>
            startedWithClock(services, engine, nickname, externalId, gameExternalId, otherExternalId, longAgo)
          }
          (fixture, started, _, _) = prepared
          // The status call now fails, with the deadline long past and matchmaker's own copy
          // still saying it is the challenger's turn.
          unreachable = TestServices.servicesWith(new GameEngineClient {
            def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
              IO.raiseError(GameEngineError("engine is down"))
            def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] = IO.raiseError(GameEngineError("engine is down"))
          })
          // `read` is the path that rechecks — `refresh` would fail on the status call itself.
          seen <- unreachable.engine.read(fixture.game.gameId, started.matchId, externalId)
          results <- resultsOf(started)
          after <- participantsOf(started)
        } yield !seen.completed &&
          results.isEmpty &&
          after.exists(p => p.pending && !p.completed)
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The turn table is what a total limit is charged against, so a move that is not recorded is
  // time nobody is billed for. Both moves of the fixture are checked, since the first one's cost
  // is measured from the match's start rather than from a turn before it.
  property("a move callback records the turn it reports, and what it cost") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          start <- IO.realTimeInstant.map(_.minusSeconds(3600))
          first = start.plusSeconds(240)
          second = first.plusSeconds(180)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.Total,
            firstMoveAt = first, secondMoveAt = second
          )
          (_, started, mine, theirs) = prepared
          turns <- turnsOf(started)
        } yield turns.map(t => (t.participantId, t.takenAt, t.elapsed)) == List(
          // Four minutes from the match's start, then three from the move before.
          (mine.participantId, first, Duration.ofMinutes(4)),
          (theirs.participantId, second, Duration.ofMinutes(3))
        )
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // What a chess clock is: the deadline for the turn in front of a player is what is left of
  // their budget, so the four minutes they spent earlier are four minutes they do not get now.
  property("under a total limit a deadline is the budget less what that player has already spent") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          start <- IO.realTimeInstant.map(_.minusSeconds(3600))
          first = start.plusSeconds(240)
          second = first.plusSeconds(180)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.Total,
            firstMoveAt = first, secondMoveAt = second
          )
          (_, started, mine, theirs) = prepared
          after <- participantsOf(started)
          challenger = after.find(_.participantId == mine.participantId).get
          opponent = after.find(_.participantId == theirs.participantId).get
        } yield challenger.pending &&
          // Ten minutes less the four already spent, from the moment this turn began.
          challenger.due.contains(second.plus(Duration.ofMinutes(6))) &&
          // Not on the clock, so no deadline at all — their three minutes are spent but only
          // count against them once it is their move again.
          opponent.due.isEmpty
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The same two moves under the other kind of limit, which is the control for the property
  // above: per turn, the four minutes already spent buy nothing and cost nothing.
  property("under a per-turn limit a deadline is the whole limit, whatever was spent before") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          start <- IO.realTimeInstant.map(_.minusSeconds(3600))
          first = start.plusSeconds(240)
          second = first.plusSeconds(180)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.PerTurn,
            firstMoveAt = first, secondMoveAt = second
          )
          (_, started, mine, _) = prepared
          after <- participantsOf(started)
          challenger = after.find(_.participantId == mine.participantId).get
        } yield challenger.due.contains(second.plus(Duration.ofMinutes(10)))
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  /* The decision the whole thing exists for: a player loses on time without any single turn
   * having been long.
   *
   * Nine of the ten minutes went on the first move, the reply took forty-nine, and the turn now
   * in front of the challenger has one minute on it — which ran out a minute ago. Under a
   * per-turn limit nothing here is overdue at all, which is the next property. */
  property("a player whose total budget runs out forfeits, though no single turn was long") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          now <- IO.realTimeInstant
          start = now.minusSeconds(3600)
          first = start.plusSeconds(540)
          second = now.minusSeconds(120)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.Total,
            firstMoveAt = first, secondMoveAt = second
          )
          (fixture, started, mine, theirs) = prepared
          // Asked by the player who is not out of time, as a real opponent would.
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, otherExternalId)
          results <- resultsOf(started)
        } yield refreshed.completed &&
          results.forall(_.forfeit) &&
          results.find(_.participantId == mine.participantId).exists(r => !r.isWinner && r.rank == 2) &&
          results.find(_.participantId == theirs.participantId).exists(r => r.isWinner && r.rank == 1)
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("the same match under a per-turn limit is not forfeited at all") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          now <- IO.realTimeInstant
          start = now.minusSeconds(3600)
          first = start.plusSeconds(540)
          second = now.minusSeconds(120)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.PerTurn,
            firstMoveAt = first, secondMoveAt = second
          )
          (fixture, started, _, _) = prepared
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, otherExternalId)
          results <- resultsOf(started)
        } yield !refreshed.completed && results.isEmpty
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The repair path: a move whose callback never arrived is recovered from the status call, with
  // its cost, rather than only as a corrected deadline. Without the turn the engine reports here,
  // the challenger's budget would look untouched and they would get the whole ten minutes.
  property("turns the engine reports on a status call are recorded, and are charged for") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          // Counted back from now, so that the deadline the last of these moves implies is
          // still ahead of us: this property is about what was recorded and charged, and a
          // fixture that had already run out of time would be answered by the forfeit instead.
          now <- IO.realTimeInstant
          fourth = now.minusSeconds(10)
          third = fourth.minusSeconds(10)
          second = third.minusSeconds(300)
          first = second.minusSeconds(180)
          start = first.minusSeconds(240)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.Total,
            firstMoveAt = first, secondMoveAt = second
          )
          (fixture, started, mine, theirs) = prepared
          // A third and fourth move happened that matchmaker never heard about: the challenger
          // spent five more minutes, and the opponent replied at once.
          _ <- IO {
            engine.status = GameStatusResponse(
              completed = false,
              participants = List(
                EngineParticipantStatus(mine.participantId.value, pending = true, completed = false, prevMoveAt = Some(fourth)),
                EngineParticipantStatus(theirs.participantId.value, pending = false, completed = false, prevMoveAt = None)
              ),
              turns = List(
                EngineTurn(mine.participantId.value, third, Some(second)),
                EngineTurn(theirs.participantId.value, fourth, Some(third))
              )
            )
          }
          refreshed <- services.engine.refresh(fixture.game.gameId, started.matchId, otherExternalId)
          turns <- turnsOf(started)
          after <- participantsOf(started)
          challenger = after.find(_.participantId == mine.participantId).get
        } yield !refreshed.completed &&
          turns.map(_.takenAt) == List(first, second, third, fourth) &&
          // Four minutes and then five: one minute of the ten is left.
          challenger.due.contains(fourth.plus(Duration.ofMinutes(1)))
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The "Current Matches" list is read by somebody who cannot move in the match: what it owes
  // them is who it is waiting for and how long that player has, neither of which is derivable
  // from their own seat's row.
  property("an active match names whose turn it is, and when that turn runs out") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, gameExternalId, otherExternalId) =>
        val engine = StubEngine()
        val services = TestServices.servicesWith(engine)
        val result = for {
          now <- IO.realTimeInstant
          second = now.minusSeconds(60)
          first = second.minusSeconds(180)
          start = first.minusSeconds(240)
          prepared <- playedTwice(
            services, engine, nickname, externalId, gameExternalId, otherExternalId,
            matchStart = start, timeLimit = Duration.ofMinutes(10), kind = TimeLimitKind.Total,
            firstMoveAt = first, secondMoveAt = second
          )
          (fixture, started, mine, _) = prepared
          // Asked by the opponent, who has just moved and is waiting: it is the challenger's
          // turn, and their deadline is the six minutes left of their budget from `second`.
          theirs <- services.matches.active(otherExternalId)
          row = theirs.find(_.matchId == started.matchId).get
          // And by the challenger, whose own row says the same thing about the same turn — the
          // seat it is read from differs, the answer does not.
          ours <- services.matches.active(externalId)
          mineRow = ours.find(_.matchId == started.matchId).get
          participants <- participantsOf(started)
          challengerDue = participants.find(_.participantId == mine.participantId).get.due
        } yield row.whoseTurn == Seq(nickname) &&
          row.turnDue == challengerDue &&
          row.turnDue.contains(second.plus(Duration.ofMinutes(6))) &&
          // Not this caller's turn, so their own deadline is empty while the match's is not.
          row.due.isEmpty && !row.pending &&
          mineRow.whoseTurn == row.whoseTurn && mineRow.turnDue == row.turnDue
        result.timeout(15.seconds).unsafeRunSync()
    }
  }

  property("start records the first turn, so the player who moves first is told straight away") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val engine = new FirstTurnEngine
      val services = TestServices.servicesWith(engine)
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
        // The list the main page's "waiting on you" is drawn from, asked for with no refresh in
        // between — this is the state a player finds when the page loads after a start.
        due <- services.matches.due(externalId)
      } yield participants.exists(_.pending) &&
        due.map(_.matchId) == List(started.matchId)
      result.timeout(15.seconds).unsafeRunSync()
    }
  }

  // The start has already succeeded by the time the engine is asked, and the match exists either
  // way, so an engine that cannot answer must not turn a completed start into a failure. It
  // leaves precisely the state every start used to leave, which `refresh` still corrects.
  property("a start still succeeds when the engine will not say whose turn it is") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val engine = new GameEngineClient {
        def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
          IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))
        def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] =
          IO.raiseError(GameEngineError("status is down"))
      }
      val services = TestServices.servicesWith(engine)
      val result = for {
        fixture <- makeFixture(nickname, externalId, gameExternalId)
        challenge <- services.challenges.create(challengeFor(fixture), externalId)
        started <- services.engine.start(fixture.game.gameId, challenge.challengeId, externalId)
        participants <- participantsOf(started)
      } yield started.playUrl.contains("https://engine/play/1") && participants.forall(!_.pending)
      result.timeout(15.seconds).unsafeRunSync()
    }
  }
}
