package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.model.MatchId

class ParticipantRepoSpec extends PropertySuite {
  property("create then read returns the participant just created") {
    forAll(Gen.oneOf(true, false), Generators.genString, Generators.genPlayer) { (isPlayerVariant, matchIdStr, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val matchRepo = new MatchRepo(session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val participantRepo = new ParticipantRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGameWithRole(isPlayerVariant).sample.get)
            matchId = MatchId(matchIdStr)
            m <- IO.pure(Generators.genMatch(createdGame.gameId, matchId, isPlayerVariant).sample.get)
            _ <- matchRepo.create(m)
            createdPlayer <- playerRepo.create(player)
            participant <-
              if (isPlayerVariant)
                IO.pure(
                  Generators
                    .genPlayerParticipant(createdGame.gameId, matchId, createdPlayer.playerId, createdGame.roles.head.gameRoleId)
                    .sample
                    .get
                )
              else
                characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get).map { createdCharacter =>
                  Generators
                    .genCharacterParticipant(createdGame.gameId, matchId, createdPlayer.playerId, createdCharacter.characterId)
                    .sample
                    .get
                }
            created <- participantRepo.create(participant)
            found <- participantRepo.read(created.participantId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
