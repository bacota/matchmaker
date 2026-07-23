package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{Generators, TestSession}

class GameServiceSpec extends ScalaCheckSuite {
  TestMigration.ensure()

  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val gameService = new GameService[String](config)
  private val registrationService = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private def makeAdmin(): IO[Player] =
    for {
      nickname <- IO(genUniqueString.sample.get)
      externalId <- IO(genUniqueString.sample.get)
      player <- registrationService.register(nickname, externalId)
      _ <- TestSession.resource.use { session =>
        new com.vivi.matchmaker.persistence.PlayerRepo(session).update(player.copy(isAdmin = true))
      }
    } yield player.copy(isAdmin = true)

  property("createOrUpdate creates a new game with roles and parameters for an admin") {
    forAll(Gen.oneOf(true, false)) { playerGame =>
      val result = for {
        admin <- makeAdmin()
        game <- IO(Generators.genGameWithRole(playerGame).sample.get)
        created <- gameService.createOrUpdate(admin.externalId, game)
      } yield created.name == game.name && created.roles.size == 1

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate updates an existing game") {
    forAll(Gen.oneOf(true, false), Generators.genString) { (playerGame, newName) =>
      val result = for {
        admin <- makeAdmin()
        game <- IO(Generators.genGame(playerGame).sample.get)
        created <- gameService.createOrUpdate(admin.externalId, game)
        updated <- gameService.createOrUpdate(admin.externalId, created match {
          case g: PlayerGame    => g.copy(name = newName)
          case g: CharacterGame => g.copy(name = newName)
        })
      } yield updated.gameId == created.gameId && updated.name == newName

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects an unknown user") {
    forAll(genUniqueString, Gen.oneOf(true, false)) { (unknownExternalId, playerGame) =>
      val result = for {
        game <- IO(Generators.genGame(playerGame).sample.get)
        attempt <- gameService.createOrUpdate(unknownExternalId, game).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects a non-admin user") {
    forAll(genUniqueString, genUniqueString, Gen.oneOf(true, false)) { (nickname, externalId, playerGame) =>
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        game <- IO(Generators.genGame(playerGame).sample.get)
        attempt <- gameService.createOrUpdate(externalId, game).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects an update for a nonexistent game id") {
    forAll(Gen.oneOf(true, false)) { playerGame =>
      val result = for {
        admin <- makeAdmin()
        game <- IO(Generators.genGame(playerGame).sample.get)
        nonexistent = game match {
          case g: PlayerGame    => g.copy(gameId = GameId(Int.MaxValue))
          case g: CharacterGame => g.copy(gameId = GameId(Int.MaxValue))
        }
        attempt <- gameService.createOrUpdate(admin.externalId, nonexistent).attempt
      } yield attempt match {
        case Left(_: NotFoundError) => true
        case _                      => false
      }

      result.unsafeRunSync()
    }
  }
}
