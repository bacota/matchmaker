package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.model.CharacterGame

class CharacterRepoSpec extends PropertySuite {
  property("create then read returns the character just created") {
    forAll(Generators.genGame(false), Gen.oneOf(true, false), Generators.genPlayer) { (game, withPlayer, player) =>
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
    forAll(Generators.genGame(false), Generators.genPlayer) { (game, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          for {
            createdGame <- gameRepo.create(game).map(_.asInstanceOf[CharacterGame])
            createdPlayer <- playerRepo.create(player)
            character <- IO.pure(Generators.genCharacter(createdGame.gameId, Some(createdPlayer.playerId)).sample.get)
            created <- characterRepo.create(character)
            found <- characterRepo.readWithOwnerAndGame(created.characterId)
          } yield found == Some((created, createdPlayer, createdGame))
        }
        .unsafeRunSync()
    }
  }

  property("readWithOwnerAndGame returns None for a character with no owning player") {
    forAll(Generators.genGame(false)) { game =>
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
}
