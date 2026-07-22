package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen

class OpenChallengeRepoSpec extends ScalaCheckSuite {
  property("create then read returns the open challenge just created") {
    forAll(Gen.oneOf(true, false), Generators.genPlayer) { (isPlayerVariant, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame(isPlayerVariant).sample.get)
            createdPlayer <- playerRepo.create(player)
            challenge <-
              if (isPlayerVariant)
                cats.effect.IO.pure(Generators.genPlayerOpenChallenge(createdPlayer.playerId, createdGame.gameId).sample.get)
              else
                characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get).map { createdCharacter =>
                  Generators
                    .genCharacterOpenChallenge(createdPlayer.playerId, createdGame.gameId, createdCharacter.characterId)
                    .sample
                    .get
                }
            created <- openChallengeRepo.create(challenge)
            found <- openChallengeRepo.read(created.challengeId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
