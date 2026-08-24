package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.{Acceptance, CharacterAcceptance}

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
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            createdChallenge <- openChallengeRepo.create(challenge)
            acceptance = CharacterAcceptance(createdChallenge.challengeId, createdAcceptor.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles(1).gameRoleId)
            created <- acceptanceRepo.create(acceptance)
            found <- acceptanceRepo.read(createdGame.gameId, created.challengeId, created.playerId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }

  // Regression test for a bug where create() inserted into `acceptance` both directly and (for a
  // CharacterAcceptance) again inside insertCharacterAcceptance's own CTE — a second insert of the
  // same primary key, which failed every character-game acceptance with a Postgres error the
  // caller saw as a 500 (an API Gateway "integrationError"). `create then read` above already
  // calls create() with a CharacterAcceptance and would have failed outright under that bug, but
  // this asserts the specific invariant that regressed: exactly one row in `acceptance`.
  property("create writes exactly one acceptance row for a CharacterAcceptance") {
    forAll(Generators.genPlayer, Generators.genPlayer) { (challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            createdChallenge <- openChallengeRepo.create(challenge)
            acceptance = CharacterAcceptance(createdChallenge.challengeId, createdAcceptor.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles(1).gameRoleId)
            _ <- acceptanceRepo.create(acceptance)
            count <- acceptanceRepo.countForChallenge(createdGame.gameId, createdChallenge.challengeId)
          } yield count == 1L
        }
        .unsafeRunSync()
    }
  }

  property("readWithChallengeAndPlayers returns the challenge, acceptor, and challenger") {
    forAll(Generators.genPlayer, Generators.genPlayer) { (challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            createdChallenge <- openChallengeRepo.create(challenge)
            // The challenger's own acceptance, written with the challenge by the service and
            // joined back in by every read of one: it is where the challenge's role lives.
            _ <- acceptanceRepo.create(
              CharacterAcceptance(
                createdChallenge.challengeId, createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId,
                createdGame.roles.head.gameRoleId
              )
            )
            acceptance = CharacterAcceptance(createdChallenge.challengeId, createdAcceptor.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles(1).gameRoleId)
            created <- acceptanceRepo.create(acceptance)
            found <- acceptanceRepo.readWithChallengeAndPlayers(createdGame.gameId, created.challengeId, created.playerId)
          } yield found.contains((createdChallenge, createdAcceptor, createdChallenger))
        }
        .unsafeRunSync()
    }
  }

  property("readWithChallengeAndPlayers returns None when there is no matching acceptance") {
    forAll(Generators.genPlayer, Generators.genPlayer) { (challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)
            challenge <- IO.pure(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            createdChallenge <- openChallengeRepo.create(challenge)
            found <- acceptanceRepo.readWithChallengeAndPlayers(createdGame.gameId, createdChallenge.challengeId, createdAcceptor.playerId)
          } yield found.isEmpty
        }
        .unsafeRunSync()
    }
  }

  property("listForPlayer returns every acceptance this player has, and nobody else's") {
    forAll(Generators.genPlayer, Generators.genPlayer) { (challenger, acceptor) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          val openChallengeRepo = new OpenChallengeRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGame().sample.get)
            createdChallenger <- playerRepo.create(challenger)
            createdAcceptor <- playerRepo.create(acceptor)
            createdCharacter <- characterRepo.create(Generators.genCharacter(createdGame.gameId, None).sample.get)

            // Two challenges accepted by one player, so the list is exercised as a list rather
            // than as a single row that happens to come back.
            first <- openChallengeRepo.create(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            second <- openChallengeRepo.create(
              Generators.genOpenChallenge(createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId).sample.get
            )
            _ <- acceptanceRepo.create(CharacterAcceptance(first.challengeId, createdAcceptor.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles(1).gameRoleId))
            _ <- acceptanceRepo.create(CharacterAcceptance(second.challengeId, createdAcceptor.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles(1).gameRoleId))

            // The challenger accepts one of them too: its row must not appear in the acceptor's
            // list, which is the whole point of scoping the query by player.
            _ <- acceptanceRepo.create(CharacterAcceptance(first.challengeId, createdChallenger.playerId, createdGame.gameId, createdCharacter.characterId, createdGame.roles.head.gameRoleId))

            mine <- acceptanceRepo.listForPlayer(createdAcceptor.playerId)
          } yield mine.map(_.challengeId).toSet == Set(first.challengeId, second.challengeId) &&
            mine.forall(_.playerId == createdAcceptor.playerId)
        }
        .unsafeRunSync()
    }
  }

  property("listForPlayer returns nothing for a player who has accepted nothing") {
    forAll(Generators.genPlayer) { player =>
      TestSession.resource
        .use { session =>
          val playerRepo = new PlayerRepo(session)
          val acceptanceRepo = new AcceptanceRepo(session)
          for {
            created <- playerRepo.create(player)
            mine <- acceptanceRepo.listForPlayer(created.playerId)
          } yield mine.isEmpty
        }
        .unsafeRunSync()
    }
  }
}
