package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.model._
import org.scalacheck.Prop._

class GameRepoSpec extends PropertySuite {

    /** A game whose roles and parameter values both fan out, so that the listing join returns their cross product (2
      * roles x 3 values = 6 rows) rather than one row per entity. Anything that reads this game back has to collapse
      * that back down.
      */
    private def gameWithFanOut: Game = {
        val base = Generators.genGame().sample.get.copy(isActive = true)
        val values = Seq("a", "b", "c").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
        base.copy(
          roles = Seq(
            GameRole(GameRoleId(0), GameId.unassigned, "first", roleType = RoleType.Required, displayName = "first"),
            GameRole(GameRoleId(0), GameId.unassigned, "second", roleType = RoleType.Optional, displayName = "second")
          ),
          parameters = Seq(
            GameParameter(GameId.unassigned, GameParameterId(0), "parameter", Some("a"), values, "Parameter")
          )
        )
    }

    test("list returns each role and parameter value once, despite the join's cross product") {
        val found = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                for {
                    created <- repo.create(gameWithFanOut)
                    listed <- repo.list(activeOnly = false)
                } yield listed.find(_.gameId == created.gameId)
            }
            .unsafeRunSync()

        val game = found.getOrElse(fail("the created game was not listed"))
        assertEquals(game.roles.size, 2)
        assertEquals(game.roles.map(_.name).toSet, Set("first", "second"))
        assertEquals(game.parameters.size, 1)

        val parameter = game.parameters.head.asInstanceOf[GameParameter[String]]
        assertEquals(parameter.name, "parameter")
        assertEquals(parameter.displayName, "Parameter")
        assertEquals(parameter.defaultValue, Some("a"))
        assertEquals(parameter.values.map(_.value).toSet, Set("a", "b", "c"))
        assertEquals(parameter.values.size, 3)
    }

    test("list agrees with read for the same game") {
        val (fromRead, fromList) = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                for {
                    created <- repo.create(gameWithFanOut)
                    read <- repo.read(created.gameId)
                    listed <- repo.list(activeOnly = false)
                } yield (read, listed.find(_.gameId == created.gameId))
            }
            .unsafeRunSync()

        val read = fromRead.getOrElse(fail("the created game could not be read"))
        val listed = fromList.getOrElse(fail("the created game was not listed"))

        assertEquals(listed.name, read.name)
        assertEquals(listed.roles.toSet, read.roles.toSet)
        assertEquals(listed.parameters.map(_.displayName), read.parameters.map(_.displayName))
        assertEquals(
          listed.parameters.map(_.asInstanceOf[GameParameter[String]].values.map(_.value).toSet).toSet,
          read.parameters.map(_.asInstanceOf[GameParameter[String]].values.map(_.value).toSet).toSet
        )
    }

    test("list returns games sorted by display name, with game id breaking ties") {
        // Names are suffixed with a shared unique token so this run's games can be picked out of a
        // database these tests never clean up, while still sorting among themselves.
        val token = java.util.UUID.randomUUID().toString
        val names = List("charlie", "alpha", "bravo", "alpha")

        val (created, listed) = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                for {
                    // Each game's name sorts the opposite way to its display name, so the order that comes
                    // back says which of the two it was sorted by.
                    created <- names.zipWithIndex.traverse((n, i) =>
                        repo.create(
                          Generators.genGame().sample.get.copy(name = s"${9 - i}-$token", displayName = s"$n-$token")
                        )
                    )
                    listed <- repo.list(activeOnly = false)
                } yield (created, listed)
            }
            .unsafeRunSync()

        val ours = listed.filter(_.displayName.endsWith(token))
        assertEquals(ours.size, 4)

        // Sorted by display name...
        assertEquals(ours.map(_.displayName), ours.map(_.displayName).sorted)

        // ...and the two games named "alpha" are ordered by id, not left to chance.
        val alphas = ours.filter(_.displayName.startsWith("alpha")).map(_.gameId.value)
        assertEquals(alphas, alphas.sorted)
        assertEquals(alphas.toSet, created.filter(_.displayName.startsWith("alpha")).map(_.gameId.value).toSet)
    }

    test("list keeps a game that has neither roles nor parameters") {
        val found = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                for {
                    created <- repo.create(
                      Generators.genGame().sample.get.copy(isActive = true, roles = Seq.empty, parameters = Seq.empty)
                    )
                    listed <- repo.list(activeOnly = false)
                } yield listed.find(_.gameId == created.gameId)
            }
            .unsafeRunSync()

        val game = found.getOrElse(fail("a game with no roles or parameters was dropped by the outer join"))
        assertEquals(game.roles, Seq.empty)
        assertEquals(game.parameters, Seq.empty)
    }

    test("a role's type and preference level, and a game's move type and turns, survive a save and every read") {
        val (fromRead, fromList, afterUpdate) = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                val game = gameWithFanOut.copy(
                  moveType = MoveType.Simultaneous,
                  maxTurns = Some(12),
                  roles = Seq(
                    GameRole(GameRoleId(0), GameId.unassigned, "first", RoleType.Required, "first", Some(2)),
                    GameRole(GameRoleId(0), GameId.unassigned, "second", RoleType.Recommended, "second", None),
                    GameRole(GameRoleId(0), GameId.unassigned, "third", RoleType.Optional, "third", Some(1))
                  )
                )
                for {
                    created <- repo.create(game)
                    read <- repo.read(created.gameId)
                    listed <- repo.list(activeOnly = false)
                    _ <- repo.update(
                      created.copy(
                        moveType = MoveType.Sequential,
                        maxTurns = None,
                        roles = created.roles.map(r => r.copy(roleType = RoleType.Required, preferenceLevel = None))
                      )
                    )
                    updated <- repo.read(created.gameId)
                } yield (read, listed.find(_.gameId == created.gameId), updated)
            }
            .unsafeRunSync()

        for (game <- List(fromRead, fromList).map(_.getOrElse(fail("the created game was not found")))) {
            assertEquals(game.moveType, MoveType.Simultaneous)
            assertEquals(game.maxTurns, Some(12))
            assertEquals(
              game.roles.map(r => (r.name, r.roleType, r.preferenceLevel)).toSet,
              Set(
                ("first", RoleType.Required, Some(2)),
                ("second", RoleType.Recommended, None),
                ("third", RoleType.Optional, Some(1))
              )
            )
            assertEquals(game.poolSeats, 2)
            assertEquals(game.requiredSeats, 1)
        }
        val updated = afterUpdate.getOrElse(fail("the updated game was not found"))
        assertEquals(updated.moveType, MoveType.Sequential)
        assertEquals(updated.maxTurns, None)
        assert(updated.roles.forall(r => r.roleType == RoleType.Required && r.preferenceLevel.isEmpty))
    }

    property("create then read returns the game just created") {
        forAll(Generators.genGameWithRole) { game =>
            TestSession.resource
                .use { session =>
                    val repo = new GameRepo[String](session)
                    for {
                        created <- repo.create(game)
                        found <- repo.read(created.gameId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }
}
