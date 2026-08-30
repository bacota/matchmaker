package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import java.time.Instant
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, MatchRepo, OpenChallengeRepo, ParticipantRepo, ResultRepo, TestSession}

class MatchServiceSpec extends PropertySuite {
  TestMigration.ensure()

  private val matchService = TestServices.services.matches
  private val registrationService = TestServices.services.registration

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  /** Registers a player and puts them in one match, with control over the two flags the lists
    * discriminate on: whether the match is finished, and whether it is this player's turn.
    */
  private def makeMatch(
      nickname: String,
      externalId: String,
      matchIdStr: String,
      completed: Boolean,
      pending: Boolean
  ): IO[(Player, Game, MatchId)] =
    TestSession.resource.use { session =>
      for {
        prepared <- setup(session, nickname, externalId)
        (player, game, character) = prepared
        matchId <- addMatch(session, player, game, character, matchIdStr,
          Option.when(completed)(Instant.ofEpochSecond(3000)), pending)
      } yield (player, game, matchId)
    }

  /** A registered player with a game to play and a character to play it with — everything a
    * match needs except the match.
    */
  private def setup(
      session: skunk.Session[IO],
      nickname: String,
      externalId: String
  ): IO[(Player, Game, Character[String])] =
    for {
      player <- registrationService.register(nickname, externalId)
      game <- new GameRepo[String](session).create(
        Game(
          GameId.unassigned, GameType.Character, "game", "description", "url", active = true,
          // One role, because every participant names one.
          Seq(GameRole(GameRoleId(0), GameId.unassigned, "only", optional = false)),
          Seq.empty, genUniqueString.sample.get
        )
      )
      character <- new CharacterRepo[String](session).create(
        Character(CharacterId(0), game.gameId, "character", "description", "", Some(player.playerId))
      )
    } yield (player, game, character)

  /** One more match for a player who already has a game and a character, so that a test about
    * the order of a list can put two of them in it. Every match needs a challenge of its own —
    * it is the match's creator, by reference — so one is made here rather than shared.
    */
  private def addMatch(
      session: skunk.Session[IO],
      player: Player,
      game: Game,
      character: Character[String],
      matchIdStr: String,
      completedAt: Option[Instant],
      pending: Boolean
  ): IO[MatchId] =
    for {
      // The match's creator is its challenge's challenger, and a match cannot exist without a
      // challenge to point at — so the whole chain is built here even though most of these
      // tests only care about the lists.
      challenge <- new OpenChallengeRepo(session).create(
        CharacterOpenChallenge(
          ChallengeId(0), player.playerId, "challenge", None, None, "{}", game.gameId,
          character.characterId, isPublic = false, game.roles.head.gameRoleId
        )
      )
      matchId = MatchId(matchIdStr)
      _ <- new MatchRepo(session).create(
        Match(
          game.gameId, matchId, challenge.challengeId, "description", completedAt,
          Instant.ofEpochSecond(1000), None, "{}"
        )
      )
      _ <- new ParticipantRepo(session).create(
        CharacterParticipant(
          ParticipantId(0), game.gameId, matchId, player.playerId, pending, completedAt.isDefined,
          Some(Instant.ofEpochSecond(2000)), character.characterId, game.roles.head.gameRoleId
        )
      )
    } yield matchId

  property("due returns matches where it is the caller's turn") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, game, matchId) = made
        due <- matchService.due(externalId)
      } yield due.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) &&
        due.forall(_.gameName == "game")
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("due excludes matches where it is not the caller's turn") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        _ <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = false)
        due <- matchService.due(externalId)
      } yield due.isEmpty
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("active returns unfinished matches and completed returns finished ones") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, game, matchId) = made
        active <- matchService.active(externalId)
        completed <- matchService.completed(externalId)
      } yield active.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) && completed.isEmpty
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  /* The completed list is a history, so it is read from the most recent end: the order is
   * `completed` descending. A cancelled match has no completion time and sorts after the ones
   * that were played out, rather than ahead of them where a NULLS FIRST default would put it. */
  property("completed matches come back most recently finished first, cancelled ones last") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, olderId, newerId, cancelledId) =>
        val result = TestSession.resource.use { session =>
          for {
            prepared <- setup(session, nickname, externalId)
            (player, game, character) = prepared
            _ <- addMatch(session, player, game, character, olderId, Some(Instant.ofEpochSecond(5000)), pending = false)
            _ <- addMatch(session, player, game, character, newerId, Some(Instant.ofEpochSecond(9000)), pending = false)
            cancelled <- addMatch(session, player, game, character, cancelledId, None, pending = false)
            _ <- new MatchRepo(session).read(game.gameId, cancelled).flatMap {
              case Some(m) => new MatchRepo(session).update(m.copy(cancelled = true))
              case None    => IO.raiseError(new IllegalStateException("the match just written is not there"))
            }
            over <- matchService.completed(externalId)
          } yield over.map(_.matchId.value) == List(newerId, olderId, cancelledId)
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("completed returns finished matches and active excludes them") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = true, pending = false)
        (_, game, matchId) = made
        active <- matchService.active(externalId)
        completed <- matchService.completed(externalId)
      } yield completed.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) && active.isEmpty
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("lists are scoped to the caller, so another player sees nothing") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, matchIdStr, otherNickname, otherExternalId) =>
        val result = for {
          _ <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
          _ <- registrationService.register(otherNickname, otherExternalId)
          due <- matchService.due(otherExternalId)
          active <- matchService.active(otherExternalId)
        } yield due.isEmpty && active.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("an unregistered caller is unauthorized") {
    forAll(genUniqueString) { externalId =>
      matchService.due(externalId).attempt.timeout(10.seconds).unsafeRunSync() match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Cancelling
  // ---------------------------------------------------------------------------
  //
  // `makeMatch` makes the registered player the challenger of the challenge the match is started
  // from, so that player is the match's creator.

  property("the creator can cancel their own match") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, game, matchId) = made
        cancelled <- matchService.cancel(game.gameId, matchId, externalId)
        due <- matchService.due(externalId)
        active <- matchService.active(externalId)
        over <- matchService.completed(externalId)
      } yield cancelled.cancelled &&
        // Gone from the lists of things still to play, and present among the ones that are over:
        // a cancelled match is finished, not erased.
        due.isEmpty && active.isEmpty &&
        over.map(s => (s.matchId, s.cancelled)) == List((matchId, true))
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("a player who did not create the match may not cancel it") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, matchIdStr, otherNickname, otherExternalId) =>
        val result = for {
          made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
          (_, game, matchId) = made
          _ <- registrationService.register(otherNickname, otherExternalId)
          outcome <- matchService.cancel(game.gameId, matchId, otherExternalId).attempt
          // And the refusal is a refusal, not a silent no-op.
          active <- matchService.active(externalId)
        } yield (outcome match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }) && active.map(_.matchId) == List(matchId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("a completed match can no longer be cancelled") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = true, pending = false)
        (_, game, matchId) = made
        outcome <- matchService.cancel(game.gameId, matchId, externalId).attempt
      } yield outcome match {
        case Left(_: ConflictError) => true
        case _                      => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("cancelling twice is a conflict, not a second cancel") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, game, matchId) = made
        _ <- matchService.cancel(game.gameId, matchId, externalId)
        outcome <- matchService.cancel(game.gameId, matchId, externalId).attempt
      } yield outcome match {
        case Left(_: ConflictError) => true
        case _                      => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("a match that does not exist is not found") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, game, _) = made
        outcome <- matchService.cancel(game.gameId, MatchId("no-such-match"), externalId).attempt
      } yield outcome match {
        case Left(_: NotFoundError) => true
        case _                      => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // ---------------------------------------------------------------------------
  // Results
  // ---------------------------------------------------------------------------

  /** The one participant `makeMatch` creates, which is what a result has to be written against. */
  private def onlyParticipant(gameId: GameId, matchId: MatchId): IO[ParticipantId] =
    TestSession.resource.use(session =>
      new ParticipantRepo(session).listForMatch(gameId, matchId).map(_.head._1.participantId)
    )

  property("results name the player, their role, and whatever the engine scored them on") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = true, pending = false)
        (_, game, matchId) = made
        participantId <- onlyParticipant(game.gameId, matchId)
        _ <- TestSession.resource.use(session =>
          new ResultRepo(session).create(
            com.vivi.matchmaker.model.Result(game.gameId, participantId, rank = 1, scores = Map("moves" -> 5.0), isWinner = true)
          )
        )
        results <- matchService.results(externalId)
        mine = results.filter(_.matchId == matchId)
      } yield mine.map(r => (r.nickname, r.roleName, r.rank, r.isWinner)) ==
        List((nickname, "only", Some(1), true)) &&
        mine.head.scores == Map("moves" -> 5.0)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("a seat the engine reported no result for is still listed, with no rank") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = true, pending = false)
        (_, _, matchId) = made
        results <- matchService.results(externalId)
        mine = results.filter(_.matchId == matchId)
      } yield mine.map(r => (r.nickname, r.rank, r.isWinner)) == List((nickname, None, false)) &&
        mine.head.scores.isEmpty
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("results exclude matches still being played") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        (_, _, matchId) = made
        results <- matchService.results(externalId)
      } yield results.forall(_.matchId != matchId)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("results are scoped to the caller, so another player sees nothing of them") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, matchIdStr, otherNickname, otherExternalId) =>
        val result = for {
          _ <- makeMatch(nickname, externalId, matchIdStr, completed = true, pending = false)
          _ <- registrationService.register(otherNickname, otherExternalId)
          results <- matchService.results(otherExternalId)
        } yield results.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("the creator is told apart from a mere participant on every summary") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, matchIdStr) =>
      val result = for {
        made <- makeMatch(nickname, externalId, matchIdStr, completed = false, pending = true)
        active <- matchService.active(externalId)
      } yield active.forall(_.isCreator)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
