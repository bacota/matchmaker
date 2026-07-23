package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen

class CharacterRepoSpec extends ScalaCheckSuite {
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
    forAll(Generators.genGame(false), Gen.oneOf(true, false), Generators.genPlayer) { (game, withPlayer, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val playerRepo = new PlayerRepo(session)
          val characterRepo = new CharacterRepo[String](session)
          for {
            createdGame <- gameRepo.create(game)
            createdPlayer <- if (withPlayer) playerRepo.create(player).map(Some(_)) else IO.pure(None)
            character <- IO.pure(Generators.genCharacter(createdGame.gameId, createdPlayer.map(_.playerId)).sample.get)
            created <- characterRepo.create(character)
            found <- characterRepo.readWithOwnerAndGame(created.characterId)
          } yield found == Some((created, createdPlayer, createdGame))
        }
        .unsafeRunSync()
    }
  }
}
