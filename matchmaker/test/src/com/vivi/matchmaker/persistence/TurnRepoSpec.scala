package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.{MatchId, Turn}

class TurnRepoSpec extends PropertySuite {

  /* A match with one seat in it, which is all any of these need: what is being checked is how
   * turns accumulate against a participant, not how the participants were seated. */
  private def seated(matchIdStr: String, player: com.vivi.matchmaker.model.Player) =
    TestSession.resource.use { session =>
      val gameRepo = new GameRepo[String](session)
      val matchRepo = new MatchRepo(session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[String](session)
      val participantRepo = new ParticipantRepo(session)

      for {
        game <- gameRepo.create(Generators.genGameWithRole.sample.get)
        challengeId <- Generators.challengeIn(session, game)
        matchId = MatchId(matchIdStr)
        _ <- matchRepo.create(Generators.genMatch(game.gameId, matchId, challengeId).sample.get)
        createdPlayer <- playerRepo.create(player)
        character <- characterRepo.create(Generators.genCharacter(game.gameId, None).sample.get)
        participant <- participantRepo.create(
          Generators
            .genParticipant(game.gameId, matchId, createdPlayer.playerId, character.characterId, game.roles.head.gameRoleId)
            .sample
            .get
        )
      } yield (session, game.gameId, matchId, participant.participantId)
    }

  property("create then list returns the turns just recorded, oldest first") {
    forAll(Generators.genString, Generators.genPlayer) { (matchIdStr, player) =>
      seated(matchIdStr, player)
        .flatMap { case (session, gameId, matchId, participantId) =>
          val repo = new TurnRepo(session)
          val start = Instant.parse("2026-01-01T00:00:00Z")
          // Written newest first, to show the ordering is the query's and not the insert order's.
          val second = Turn(gameId, matchId, participantId, start.plusSeconds(120), start.plusSeconds(60))
          val first = Turn(gameId, matchId, participantId, start.plusSeconds(60), start)
          for {
            _ <- repo.create(second)
            _ <- repo.create(first)
            found <- repo.listForMatch(gameId, matchId)
          } yield found == List(first, second)
        }
        .unsafeRunSync()
    }
  }

  // The same turn is normally reported twice — once by the move callback and again by the next
  // status call — so recording one has to be something that can happen more than once.
  property("recording the same turn twice records it once") {
    forAll(Generators.genString, Generators.genPlayer) { (matchIdStr, player) =>
      seated(matchIdStr, player)
        .flatMap { case (session, gameId, matchId, participantId) =>
          val repo = new TurnRepo(session)
          val at = Instant.parse("2026-01-01T00:01:00Z")
          val turn = Turn(gameId, matchId, participantId, at, at.minusSeconds(60))
          for {
            _ <- repo.create(turn)
            _ <- repo.create(turn)
            found <- repo.listForMatch(gameId, matchId)
          } yield found == List(turn)
        }
        .unsafeRunSync()
    }
  }

  property("timeUsed adds up what a seat has spent, and latestTakenAt is its last move") {
    forAll(Generators.genString, Generators.genPlayer) { (matchIdStr, player) =>
      seated(matchIdStr, player)
        .flatMap { case (session, gameId, matchId, participantId) =>
          val repo = new TurnRepo(session)
          val start = Instant.parse("2026-01-01T00:00:00Z")
          val last = start.plusSeconds(200)
          for {
            empty <- repo.latestTakenAt(gameId, matchId)
            // 30 seconds, then 45.
            _ <- repo.create(Turn(gameId, matchId, participantId, start.plusSeconds(30), start))
            _ <- repo.create(Turn(gameId, matchId, participantId, last, last.minusSeconds(45)))
            used <- repo.timeUsed(gameId, matchId)
            latest <- repo.latestTakenAt(gameId, matchId)
          } yield empty.isEmpty &&
            used.get(participantId).contains(Duration.ofSeconds(75)) &&
            latest.contains(last)
        }
        .unsafeRunSync()
    }
  }
}
