package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.model.{CharacterAcceptance, PlayerAcceptance}

class AcceptanceRepoSpec extends ScalaCheckSuite {
  property("create then read returns the acceptance just created") {
    forAll(Gen.oneOf(true, false), Generators.genPlayer, Generators.genPlayer) { (isPlayerVariant, challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame(isPlayerVariant).sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            challenge <-
              if (isPlayerVariant)
                IO.pure(Generators.genPlayerOpenChallenge(createdChallenger.playerId, createdGame.gameId).sample.get)
              else
                characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get).map { createdCharacter =>
                  Generators
                    .genCharacterOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId)
                    .sample
                    .get
                }
            createdChallenge <- openChallengeRepo.create(challenge)
            acceptance = createdChallenge match {
              case c: com.vivi.matchmaker.model.PlayerOpenChallenge =>
                PlayerAcceptance(c.challengeId, createdAcceptor.playerId)
              case c: com.vivi.matchmaker.model.CharacterOpenChallenge =>
                CharacterAcceptance(c.challengeId, createdAcceptor.playerId, c.characterId)
            }
            created <- acceptanceRepo.create(acceptance)
            found <- acceptanceRepo.read(created.challengeId, created.playerId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
