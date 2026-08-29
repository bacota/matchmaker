package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import org.scalacheck.{Gen, Shrink}
import skunk.implicits._
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, GameRepo, OpenChallengeRepo, ParticipantRepo, ResultRepo, TestSession}

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

    def status(statusUrl: String): IO[GameStatusResponse] = IO.pure(status)
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
      message: String = "message"
  ): OpenChallenge =
    CharacterOpenChallenge(
      ChallengeId(0),
      fixture.owner.playerId,
      message,
      start = None,
      timeLimit = timeLimit,
      settings = "{}",
      gameId = fixture.game.gameId,
      characterId = fixture.character.characterId,
      isPublic = isPublic,
      gameRoleId = fixture.game.roles.head.gameRoleId
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
  private def refusingMatchUpdates: Resource[IO, Unit] = {
    def exec(statement: skunk.Command[skunk.Void]): IO[Unit] =
      TestSession.resource.use(_.execute(statement).void)

    Resource.make(
      exec(
        sql"""CREATE OR REPLACE FUNCTION fail_match_update() RETURNS trigger LANGUAGE plpgsql
              AS 'BEGIN RAISE EXCEPTION ''match update refused by test''; END'""".command
      ) *> exec(
        sql"""CREATE TRIGGER fail_match_update_trigger BEFORE UPDATE ON match
              FOR EACH ROW WHEN (NEW.description = 'explode') EXECUTE FUNCTION fail_match_update()""".command
      )
    )(_ => (exec(sql"DROP TRIGGER IF EXISTS fail_match_update_trigger ON match".command) *> exec(
      sql"DROP FUNCTION IF EXISTS fail_match_update()".command
    )).handleError(_ => ()))
  }

  // The engine's game exists by then, so the start cannot be undone, and the claim stays: a
  // challenge whose game exists must never be startable again, and the claim is now the permanent
  // mark of that rather than something to be cleaned up. What is left is the documented
  // recoverable state — a match with no urls, which `refresh` can fill in.
  property("a database failure after the engine call leaves the challenge spent, not startable again") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
      val services = TestServices.servicesWith(StubEngine())
      val result = refusingMatchUpdates.use { _ =>
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
          acceptances.forall(_.challengeId != challenge.challengeId) &&
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
