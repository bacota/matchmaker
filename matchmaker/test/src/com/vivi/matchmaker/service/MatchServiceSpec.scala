package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import java.time.Instant
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, MatchRepo, ParticipantRepo, TestSession}

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
        player <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(GameId.unassigned, GameType.Character, "game", "description", "url", active = true, Seq.empty, Seq.empty, genUniqueString.sample.get, 2, 4)
        )
        character <- new CharacterRepo[String](session).create(
          Character(CharacterId(0), game.gameId, "character", "description", "", Some(player.playerId))
        )
        matchId = MatchId(matchIdStr)
        _ <- new MatchRepo(session).create(
          Match(game.gameId, matchId, "description", completed, Instant.ofEpochSecond(1000), None, "{}")
        )
        _ <- new ParticipantRepo(session).create(
          CharacterParticipant(ParticipantId(0), game.gameId, matchId, player.playerId, pending, completed, Some(Instant.ofEpochSecond(2000)), character.characterId)
        )
      } yield (player, game, matchId)
    }

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
}
