package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.MatchId

class ResultRepoSpec extends PropertySuite {
  property("create then read returns the result just created") {
    forAll(Generators.genString, Generators.genPlayer) { (matchIdStr, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val matchRepo = new MatchRepo(session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val participantRepo = new ParticipantRepo(session)
          val resultRepo = new ResultRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGameWithRole.sample.get)
            challengeId <- Generators.challengeIn(session, createdGame)
            matchId = MatchId(matchIdStr)
            _ <- matchRepo.create(Generators.genMatch(createdGame.gameId, matchId, challengeId).sample.get)
            createdPlayer <- playerRepo.create(player)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            createdParticipant <- participantRepo.create(
              Generators
                .genParticipant(createdGame.gameId, matchId, createdPlayer.playerId, createdCharacter.characterId, createdGame.roles.head.gameRoleId)
                .sample
                .get
            )
            result <- IO.pure(Generators.genResult(createdGame.gameId, createdParticipant.participantId).sample.get)
            created <- resultRepo.create(result)
            found <- resultRepo.read(created.gameId, created.participantId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
