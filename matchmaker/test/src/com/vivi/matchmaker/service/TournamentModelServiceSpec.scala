package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import munit.FunSuite
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, StandardDesigns, TournamentDesign}
import com.vivi.matchmaker.persistence.{GameAdminRepo, GameRepo, Generators, PlayerRepo, TestSession}

/** A game's tournament models (V52), made, edited and removed by its admins, against the local database. */
class TournamentModelServiceSpec extends FunSuite with QuietTests {

    private val services = TestServices.services
    private val models = services.tournamentModels

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    /** A two-player game with no models, an admin of it, and a player who is not one. */
    private def fixture: IO[(Game, Player, Player)] =
        for {
            admin <- services.registration.register(unique("admin"), unique("sub"))
            player <- services.registration.register(unique("player"), unique("sub"))
            game <- TestSession.resource.use { session =>
                for {
                    game <- new GameRepo[String](session).create(Generators.genGame().sample.get)
                    _ <- new GameAdminRepo(session).grant(admin.playerId, game.gameId, admin.playerId)
                } yield game
            }
        } yield (game, admin, player)

    private val roundRobin =
        TournamentModelRequest(
          "Round robin",
          "Everyone plays everyone.",
          Some("Round robin"),
          Some(6),
          design = TournamentDesign(List(StageDesign(brackets = false, poolSize = None, demote = None)))
        )

    test("a game's admin adds, edits and removes a model, and any player sees it listed") {
        val (added, listed, edited, after) = run(for {
            f <- fixture
            (game, admin, player) = f
            added <- models.create(game.gameId, roundRobin, admin.externalId)
            listed <- models.list(game.gameId, player.externalId)
            edited <- models.update(
              game.gameId,
              added.tournamentModelId,
              roundRobin.copy(name = "Big round robin", poolSize = Some(8)),
              admin.externalId
            )
            _ <- models.delete(game.gameId, added.tournamentModelId, admin.externalId)
            after <- models.list(game.gameId, player.externalId)
        } yield (added, listed, edited, after))
        assertEquals(added.design, roundRobin.design)
        assertEquals(listed.map(_.name), List("Round robin"))
        assertEquals((edited.name, edited.poolSize), ("Big round robin", Some(8)))
        assertEquals(after, Nil)
    }

    test("a player who does not administer the game may not add, edit or remove a model") {
        val outcome = run(for {
            f <- fixture
            (game, admin, player) = f
            made <- models.create(game.gameId, roundRobin, admin.externalId)
            create <- models.create(game.gameId, roundRobin, player.externalId).attempt
            update <- models.update(game.gameId, made.tournamentModelId, roundRobin, player.externalId).attempt
            delete <- models.delete(game.gameId, made.tournamentModelId, player.externalId).attempt
            standard <- models.addStandard(game.gameId, player.externalId).attempt
        } yield List(create, update, delete, standard))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[UnauthorizedError])), outcome.toString)
    }

    test("a model whose design cannot be played is refused, naming what is wrong") {
        val refused = run(for {
            f <- fixture
            (game, admin, _) = f
            bad = roundRobin.copy(design = TournamentDesign(List(StageDesign(demote = Some(1)))))
            attempt <- models.create(game.gameId, bad, admin.externalId).attempt
        } yield attempt)
        assert(refused.left.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("demote")), refused)
    }

    test("the standard formats are added once, and a new game is given them as it is created") {
        val (first, again, created) = run(for {
            f <- fixture
            (game, admin, player) = f
            first <- models.addStandard(game.gameId, admin.externalId)
            again <- models.addStandard(game.gameId, admin.externalId)
            overall <- services.registration.register(unique("overall"), unique("sub"))
            _ <- TestSession.resource.use(new PlayerRepo(_).update(overall.copy(isAdmin = true)))
            made <- services.games.createOrUpdate(
              overall.externalId,
              Generators.genGame().sample.get.copy(gameId = GameId.unassigned)
            )
            created <- models.list(made.gameId, player.externalId)
        } yield (first, again, created))
        val expected = StandardDesigns.models(2).map(_.name).sorted
        assertEquals(first.map(_.name).sorted, expected)
        assertEquals(again, Nil)
        assertEquals(created.map(_.name).sorted, expected)
    }
}
