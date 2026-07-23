package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._

class OpenChallengeRepoSpec extends PropertySuite {
  property("create then read returns the open challenge just created") {
    forAll(Generators.genPlayer) { player =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame.sample.get)
            createdPlayer <- playerRepo.create(player)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdPlayer.playerId, createdGame.gameId, createdCharacter.characterId).sample.get
            )
            created <- openChallengeRepo.create(challenge)
            found <- openChallengeRepo.read(created.challengeId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
