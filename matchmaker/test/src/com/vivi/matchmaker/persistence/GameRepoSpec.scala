package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.model._
import org.scalacheck.Prop._

class GameRepoSpec extends PropertySuite {

  /** A game whose roles and parameter values both fan out, so that the listing join returns
    * their cross product (2 roles x 3 values = 6 rows) rather than one row per entity. Anything
    * that reads this game back has to collapse that back down.
    */
  private def gameWithFanOut: Game = {
    val base = Generators.genGame.sample.get.copy(active = true)
    val values = Seq("a", "b", "c").map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
    base.copy(
      roles = Seq(
        GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false),
        GameRole(GameRoleId(0), GameId.unassigned, "second", optional = true)
      ),
      parameters = Seq(GameParameter(GameId.unassigned, GameParameterId(0), "parameter", Some("a"), values))
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
    assertEquals(
      listed.parameters.map(_.asInstanceOf[GameParameter[String]].values.map(_.value).toSet).toSet,
      read.parameters.map(_.asInstanceOf[GameParameter[String]].values.map(_.value).toSet).toSet
    )
  }

  test("list keeps a game that has neither roles nor parameters") {
    val found = TestSession.resource
      .use { session =>
        val repo = new GameRepo[String](session)
        for {
          created <- repo.create(Generators.genGame.sample.get.copy(active = true, roles = Seq.empty, parameters = Seq.empty))
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
