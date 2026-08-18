package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen

class CharacterRepoSpec extends PropertySuite {
  property("create then read returns the character just created") {
    forAll(Generators.genGame(), Gen.oneOf(true, false), Generators.genPlayer) { (game, withPlayer, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          for {
            createdGame <- gameRepo.create(game)
            createdPlayer <- if (withPlayer) playerRepo.create(player).map(p => Some(p.playerId)) else IO.pure(None)
            character <- IO.pure(Generators.genCharacter(createdGame.gameId, createdPlayer).sample.get)
            created <- characterRepo.create(character)
            found <- characterRepo.read(created.characterId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }

  property("readWithOwnerAndGame returns the character joined with its owner and game") {
    forAll(Generators.genGame(), Generators.genPlayer) { (game, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          for {
            createdGame <- gameRepo.create(game)
            createdPlayer <- playerRepo.create(player)
            character <- IO.pure(Generators.genCharacter(createdGame.gameId, Some(createdPlayer.playerId)).sample.get)
            created <- characterRepo.create(character)
            found <- characterRepo.readWithOwnerAndGame(created.characterId)
          } yield found == Some(CharacterWithOwnerAndGame(created, createdPlayer, createdGame))
        }
        .unsafeRunSync()
    }
  }

  property("readWithOwnerAndGame returns None for a character with no owning player") {
    forAll(Generators.genGame()) { game =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val characterRepo = new CharacterRepo[String](session)
          for {
            createdGame <- gameRepo.create(game)
            character <- IO.pure(Generators.genCharacter(createdGame.gameId, None).sample.get)
            created <- characterRepo.create(character)
            found <- characterRepo.readWithOwnerAndGame(created.characterId)
          } yield found.isEmpty
        }
        .unsafeRunSync()
    }
  }

  property("listForPlayerAndGame returns only this player's characters in this game") {
    forAll(Generators.genPlayer) { player =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)

          for {
            game <- gameRepo.create(Generators.genGame().sample.get)
            otherGame <- gameRepo.create(Generators.genGame().sample.get)
            owner <- playerRepo.create(player)

            mine <- characterRepo.create(
              Generators.genCharacter(game.gameId, Some(owner.playerId)).sample.get
            )
            // Same player, different game: must not appear.
            _ <- characterRepo.create(
              Generators.genCharacter(otherGame.gameId, Some(owner.playerId)).sample.get
            )
            // Same game, no owner: must not appear either, since the query is by player.
            _ <- characterRepo.create(Generators.genCharacter(game.gameId, None).sample.get)

            found <- characterRepo.listForPlayerAndGame(owner.playerId, game.gameId)
          } yield found.map(_.characterId) == List(mine.characterId) &&
            found.forall(c => c.gameId == game.gameId && c.playerId == Some(owner.playerId))
        }
        .unsafeRunSync()
    }
  }

  property("listForPlayerAndGame returns the character's name, description and state") {
    // The columns are selected in an unusual order to work around a skunk twiddle-list wrinkle,
    // so this checks the fields actually land where they belong rather than being transposed.
    forAll(Generators.genPlayer) { player =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)

          for {
            game <- gameRepo.create(Generators.genGame().sample.get)
            owner <- playerRepo.create(player)
            created <- characterRepo.create(
              Generators.genCharacter(game.gameId, Some(owner.playerId)).sample.get
            )
            found <- characterRepo.listForPlayerAndGame(owner.playerId, game.gameId)
          } yield found == List(created)
        }
        .unsafeRunSync()
    }
  }
}
