package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.CharacterAcceptance

class AcceptanceRepoSpec extends PropertySuite {
  property("create then read returns the acceptance just created") {
    forAll(Generators.genPlayer, Generators.genPlayer) { (challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame.sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId).sample.get
            )
            createdChallenge <- openChallengeRepo.create(challenge)
            acceptance = CharacterAcceptance(createdChallenge.challengeId, createdAcceptor.playerId, createdChallenge.characterId)
            created <- acceptanceRepo.create(acceptance)
            found <- acceptanceRepo.read(created.challengeId, created.playerId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
