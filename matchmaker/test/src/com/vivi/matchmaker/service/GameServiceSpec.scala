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

  test("createOrUpdate renames a game, renames and re-flags its roles, and keeps their ids") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGame().sample.get)
      created <- gameService.createOrUpdate(admin.externalId, base)
      edited = created.copy(
        name = "renamed",
        description = "redescribed",
        roles = created.roles.map(role => role.copy(name = s"${role.name}-renamed", optional = !role.optional))
      )
      updated <- gameService.createOrUpdate(admin.externalId, edited)
    } yield
      updated.name == "renamed" &&
        updated.description == "redescribed" &&
        // The ids are the whole point: acceptances and participants point at them, so a rename
        // that reissued them would leave every existing seat naming a role that no longer exists.
        updated.roles.map(_.gameRoleId).toSet == created.roles.map(_.gameRoleId).toSet &&
        updated.roles.map(_.name).toSet == created.roles.map(_.name + "-renamed").toSet &&
        updated.roles.forall(role => created.roles.exists(c => c.gameRoleId == role.gameRoleId && c.optional != role.optional))

    assert(result.unsafeRunSync())
  }

  test("createOrUpdate adds a role to an existing game, leaving the others alone") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      created <- gameService.createOrUpdate(admin.externalId, base)
      // Unassigned marks it as new; every other role carries the id it already has.
      edited = created.copy(roles = created.roles :+ GameRole(GameRoleId.unassigned, created.gameId, "added", optional = true))
      updated <- gameService.createOrUpdate(admin.externalId, edited)
    } yield
      updated.roles.sizeIs == created.roles.size + 1 &&
        updated.roles.exists(r => r.name == "added" && r.optional && r.gameRoleId != GameRoleId.unassigned) &&
        created.roles.map(_.gameRoleId).forall(id => updated.roles.exists(_.gameRoleId == id))

    assert(result.unsafeRunSync())
  }

  // Deleting a role would leave acceptances and played matches naming a role that is gone, so it
  // is refused by omission as well as outright — an update simply says what the roles are.
  test("createOrUpdate refuses to delete a role by leaving it out of the update") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGame().sample.get)
      created <- gameService.createOrUpdate(admin.externalId, base)
      edited = created.copy(roles = created.roles.take(1))
      attempt <- gameService.createOrUpdate(admin.externalId, edited).attempt
      // And the game still has both, rather than having been half-written before the refusal.
      after <- gameService.list(admin.externalId).map(_.find(_.gameId == created.gameId))
    } yield attempt.left.exists {
      case e: ValidationError => e.getMessage.contains("cannot be deleted")
      case _                  => false
    } && after.exists(_.roles.sizeIs == created.roles.size)

    assert(result.unsafeRunSync())
  }

  test("createOrUpdate refuses a role id that belongs to no role of the game") {
    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      created <- gameService.createOrUpdate(admin.externalId, base)
      edited = created.copy(roles = created.roles.map(_.copy(gameRoleId = GameRoleId(999999))))
      attempt <- gameService.createOrUpdate(admin.externalId, edited).attempt
    } yield attempt.left.exists(_.isInstanceOf[ValidationError])

    assert(result.unsafeRunSync())
  }

  test("createOrUpdate adds, changes and deletes parameters") {
    def parameter(name: String, values: Seq[String], default: Option[String]) =
      GameParameter[String](
        GameId.unassigned,
        GameParameterId.unassigned,
        name,
        default,
        values.map(v => GameParameterValue(GameId.unassigned, GameParameterId.unassigned, v))
      )

    val result = for {
      admin <- makeAdmin()
      base <- IO(Generators.genGameWithRole.sample.get)
      created <- gameService.createOrUpdate(
        admin.externalId,
        base.copy(parameters = Seq(parameter("kept", Seq("a", "b"), Some("a")), parameter("dropped", Seq("x"), None)))
      )
      // 'dropped' is gone, 'kept' is renamed and given different values, and 'added' is new.
      edited = created.copy(parameters =
        Seq(parameter("kept-renamed", Seq("b", "c"), Some("c")), parameter("added", Seq("z"), None))
      )
      updated <- gameService.createOrUpdate(admin.externalId, edited)
      listed <- gameService.list(admin.externalId).map(_.find(_.gameId == created.gameId))
    } yield {
      val parameters = listed.get.parameters.map(_.asInstanceOf[GameParameter[String]])
      parameters.map(_.name).toSet == Set("kept-renamed", "added") &&
      parameters.find(_.name == "kept-renamed").exists { p =>
        p.values.map(_.value).toSet == Set("b", "c") && p.defaultValue.contains("c")
      } &&
      updated.parameters.map(_.name).toSet == Set("kept-renamed", "added")
    }

    assert(result.unsafeRunSync())
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
