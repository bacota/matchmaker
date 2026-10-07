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
        val base = Generators.genGame().sample.get.copy(active = true)
        val values = Seq("a", "b", "c").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
        base.copy(
          roles = Seq(
            GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false, displayName = "first"),
            GameRole(GameRoleId(0), GameId.unassigned, "second", optional = true, displayName = "second")
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

    test("whether players choose roles, and which roles are preferred, are kept, read and listed in role order") {
        val (read, listed, updated) = TestSession.resource
            .use { session =>
                val repo = new GameRepo[String](session)
                val game = gameWithFanOut.copy(
                  choosesRoles = true,
                  roles = gameWithFanOut.roles.map(r => r.copy(preferred = r.name == "first"))
                )
                for {
                    created <- repo.create(game)
                    read <- repo.read(created.gameId)
                    listed <- repo.list(activeOnly = false)
                    _ <- repo.update(
                      created
                          .copy(choosesRoles = false, roles = created.roles.map(r => r.copy(preferred = !r.preferred)))
                    )
                    updated <- repo.read(created.gameId)
                } yield (read.get, listed.find(_.gameId == created.gameId).get, updated.get)
            }
            .unsafeRunSync()
        assert(read.choosesRoles && listed.choosesRoles)
        assertEquals(read.roles.map(r => r.name -> r.preferred), Seq("first" -> true, "second" -> false))
        assertEquals(listed.roles.map(r => r.name -> r.preferred), Seq("first" -> true, "second" -> false))
        assert(!updated.choosesRoles)
        assertEquals(updated.roles.map(r => r.name -> r.preferred), Seq("first" -> false, "second" -> true))
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
                      Generators.genGame().sample.get.copy(active = true, roles = Seq.empty, parameters = Seq.empty)
                    )
                    listed <- repo.list(activeOnly = false)
                } yield listed.find(_.gameId == created.gameId)
            }
            .unsafeRunSync()

        val game = found.getOrElse(fail("a game with no roles or parameters was dropped by the outer join"))
        assertEquals(game.roles, Seq.empty)
        assertEquals(game.parameters, Seq.empty)
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
