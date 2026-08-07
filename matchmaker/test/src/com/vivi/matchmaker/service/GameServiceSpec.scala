package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{Generators, TestSession}

class GameServiceSpec extends PropertySuite {
  TestMigration.ensure()

  private val gameService = TestServices.services.games
  private val registrationService = TestServices.services.registration

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

  test("createOrUpdate creates a new game with roles and parameters for an admin") {
    val result = for {
      admin <- makeAdmin()
      game <- IO(Generators.genGameWithRole.sample.get)
      created <- gameService.createOrUpdate(admin.externalId, game)
    } yield created.name == game.name && created.roles.size == 1

    assert(result.unsafeRunSync())
  }

  property("createOrUpdate updates an existing game") {
    forAll(Generators.genString) { newName =>
      val result = for {
        admin <- makeAdmin()
        game <- IO(Generators.genGame.sample.get)
        created <- gameService.createOrUpdate(admin.externalId, game)
        updated <- gameService.createOrUpdate(admin.externalId, created.copy(name = newName))
      } yield updated.gameId == created.gameId && updated.name == newName

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects an unknown user") {
    forAll(genUniqueString) { unknownExternalId =>
      val result = for {
        game <- IO(Generators.genGame.sample.get)
        attempt <- gameService.createOrUpdate(unknownExternalId, game).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects a non-admin user") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        game <- IO(Generators.genGame.sample.get)
        attempt <- gameService.createOrUpdate(externalId, game).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.unsafeRunSync()
    }
  }

  test("createOrUpdate rejects an update for a nonexistent game id") {
    val result = for {
      admin <- makeAdmin()
      game <- IO(Generators.genGame.sample.get)
      nonexistent = game.copy(gameId = GameId(Int.MaxValue))
      attempt <- gameService.createOrUpdate(admin.externalId, nonexistent).attempt
    } yield attempt match {
      case Left(_: NotFoundError) => true
      case _                      => false
    }

    assert(result.unsafeRunSync())
  }

  test("list returns games with their roles, for a non-admin caller") {
    val result = for {
      admin <- makeAdmin()
      nickname <- IO(genUniqueString.sample.get)
      externalId <- IO(genUniqueString.sample.get)
      _ <- registrationService.register(nickname, externalId)
      game <- IO(Generators.genGameWithRole.sample.get.copy(active = true))
      created <- gameService.createOrUpdate(admin.externalId, game)
      games <- gameService.list(externalId)
    } yield games.find(_.gameId == created.gameId).exists(g => g.name == game.name && g.roles.size == 1)

    assert(result.unsafeRunSync())
  }

  test("list with activeOnly hides inactive games") {
    val result = for {
      admin <- makeAdmin()
      inactive <- IO(Generators.genGame.sample.get.copy(active = false))
      created <- gameService.createOrUpdate(admin.externalId, inactive)
      all <- gameService.list(admin.externalId)
      activeOnly <- gameService.list(admin.externalId, activeOnly = true)
    } yield all.exists(_.gameId == created.gameId) && !activeOnly.exists(_.gameId == created.gameId)

    assert(result.unsafeRunSync())
  }

  test("list rejects an unregistered caller") {
    val result = for {
      externalId <- IO(genUniqueString.sample.get)
      attempt <- gameService.list(externalId).attempt
    } yield attempt match {
      case Left(_: UnauthorizedError) => true
      case _                          => false
    }

    assert(result.unsafeRunSync())
  }
}
