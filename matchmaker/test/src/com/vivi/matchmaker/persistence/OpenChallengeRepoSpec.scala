package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.model.CharacterAcceptance
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
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdPlayer <- playerRepo.create(player)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdPlayer.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            created <- openChallengeRepo.create(challenge)
            // The challenger's own acceptance, which the service always writes alongside the
            // challenge and which is where the challenge's role is stored -- reading a challenge
            // joins it back in, so a challenge without one is not a state that ever exists.
            _ <- acceptanceRepo.create(
              CharacterAcceptance(
                created.challengeId, createdPlayer.playerId, createdGame.gameId, createdCharacter.characterId, challenge.gameRoleId
              )
            )
            found <- openChallengeRepo.read(createdGame.gameId, created.challengeId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
