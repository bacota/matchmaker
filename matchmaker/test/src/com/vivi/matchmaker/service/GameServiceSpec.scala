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

  test("createOrUpdate stores a game's parameters, their possible values and the default among them") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      game = base.copy(parameters =
        Seq(
          GameParameter[String](
            GameId.unassigned,
            GameParameterId(0),
            "board size",
            defaultValue = Some("3x3"),
            values = Seq("3x3", "4x4", "5x5").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
          )
        )
      )
      created <- gameService.createOrUpdate(admin.externalId, game)
      // Read back through list rather than trusting what create returned: the ids are assigned by
      // the inserts, and the values are what a client actually sees.
      listed <- gameService.list(admin.externalId).map(_.find(_.gameId == created.gameId))
    } yield {
      val parameter = listed.get.parameters.head.asInstanceOf[GameParameter[String]]
      parameter.name == "board size" &&
      parameter.defaultValue.contains("3x3") &&
      parameter.values.map(_.value).toSet == Set("3x3", "4x4", "5x5")
    }

    assert(result.unsafeRunSync())
  }

  // The default is trimmed to the same thing one of the values is trimmed to, so this only gets
  // past validation -- let alone past default_value's foreign key to game_parameter_value -- if
  // both were stripped before they were compared.
  test("createOrUpdate stores parameter values and defaults with the whitespace stripped") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      game = base.copy(parameters =
        Seq(
          GameParameter[String](
            GameId.unassigned,
            GameParameterId(0),
            "board size",
            defaultValue = Some(" 3x3"),
            values = Seq(" 3x3 ", "4x4\n").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
          )
        )
      )
      created <- gameService.createOrUpdate(admin.externalId, game)
      listed <- gameService.list(admin.externalId).map(_.find(_.gameId == created.gameId))
    } yield {
      val parameter = listed.get.parameters.head.asInstanceOf[GameParameter[String]]
      parameter.values.map(_.value).toSet == Set("3x3", "4x4") && parameter.defaultValue.contains("3x3")
    }

    assert(result.unsafeRunSync())
  }

  test("createOrUpdate stores role and parameter names with the whitespace stripped") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      game = base.copy(
        roles = Seq(GameRole(GameRoleId(0), GameId.unassigned, "  attacker  ", optional = false)),
        parameters = Seq(
          GameParameter[String](GameId.unassigned, GameParameterId(0), "\tboard size\n", defaultValue = None, values = Seq.empty)
        )
      )
      created <- gameService.createOrUpdate(admin.externalId, game)
      listed <- gameService.list(admin.externalId).map(_.find(_.gameId == created.gameId))
    } yield listed.exists(g => g.roles.map(_.name) == Seq("attacker") && g.parameters.map(_.name) == Seq("board size"))

    assert(result.unsafeRunSync())
  }

  // Two names that differ only in their spacing are one name, and the duplicate check has to see
  // them that way -- which is why the trimming happens before it rather than after.
  test("createOrUpdate refuses two roles whose names differ only in whitespace") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      game = base.copy(roles =
        Seq(
          GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false),
          GameRole(GameRoleId(0), GameId.unassigned, "  attacker", optional = false)
        )
      )
      attempt <- gameService.createOrUpdate(admin.externalId, game).attempt
    } yield attempt.left.exists(_.isInstanceOf[ValidationError])

    assert(result.unsafeRunSync())
  }

  test("createOrUpdate refuses a game that defines no roles") {
    val result = for {
      admin <- makeAdmin()
      game <- IO(Generators.genGame().sample.get)
      attempt <- gameService.createOrUpdate(admin.externalId, game.copy(roles = Seq.empty)).attempt
    } yield attempt.left.exists(_.isInstanceOf[ValidationError])

    assert(result.unsafeRunSync())
  }

  // The schema would refuse this too — default_value is a foreign key to game_parameter_value —
  // but as a constraint violation, which reaches the caller as a 500 rather than an explanation.
  test("createOrUpdate refuses a parameter whose default is not one of its values") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      game = base.copy(parameters =
        Seq(
          GameParameter[String](
            GameId.unassigned,
            GameParameterId(0),
            "board size",
            defaultValue = Some("9x9"),
            values = Seq("3x3").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
          )
        )
      )
      attempt <- gameService.createOrUpdate(admin.externalId, game).attempt
    } yield attempt.left.exists {
      case e: ValidationError => e.getMessage.contains("9x9")
      case _                  => false
    }

    assert(result.unsafeRunSync())
  }

  property("createOrUpdate updates an existing game") {
    forAll(Generators.genString) { newName =>
      val result = for {
        admin <- makeAdmin()
        game <- IO(Generators.genGame().sample.get)
        created <- gameService.createOrUpdate(admin.externalId, game)
        updated <- gameService.createOrUpdate(admin.externalId, created.copy(name = newName))
      } yield updated.gameId == created.gameId && updated.name == newName

      result.unsafeRunSync()
    }
  }

  property("createOrUpdate rejects an unknown user") {
    forAll(genUniqueString) { unknownExternalId =>
      val result = for {
        game <- IO(Generators.genGame().sample.get)
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
        game <- IO(Generators.genGame().sample.get)
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
      game <- IO(Generators.genGame().sample.get)
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
      inactive <- IO(Generators.genGame().sample.get.copy(active = false))
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
