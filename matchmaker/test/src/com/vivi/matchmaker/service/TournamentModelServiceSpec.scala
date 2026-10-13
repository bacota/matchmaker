package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import munit.FunSuite
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, StandardDesigns, TournamentDesign}
import skunk.implicits._
import skunk.codec.all.int8
import com.vivi.matchmaker.persistence.{
    EntrantRepo,
    GameAdminRepo,
    GameRepo,
    Generators,
    PlayerRepo,
    TestSession,
    TournamentInvitationRepo,
    TournamentRepo
}

/** A game's tournament models (V52), made, edited, removed and deactivated by its admins, against the local database.
  */
class TournamentModelServiceSpec extends FunSuite with QuietTests {

    private val services = TestServices.services
    private val models = services.tournamentModels

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    /** A two-player game with no models, an admin of it, and a player who is not one. */
    private def fixture: IO[(Game, Player, Player)] = fixtureOf(GameType.Character)

    private def fixtureOf(gameType: GameType): IO[(Game, Player, Player)] =
        for {
            admin <- services.registration.register(unique("admin"), unique("sub"))
            player <- services.registration.register(unique("player"), unique("sub"))
            game <- TestSession.resource.use { session =>
                for {
                    game <- new GameRepo[String](session).create(Generators.genGame(gameType).sample.get)
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
            _ <- models.remove(game.gameId, added.tournamentModelId, admin.externalId)
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
            delete <- models.remove(game.gameId, made.tournamentModelId, player.externalId).attempt
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

    // ---- removing a model that tournaments were made from ----

    private def tournamentFrom(game: Game, model: TournamentModel, owner: Player): IO[Tournament] =
        services.tournaments.create(
          game.gameId,
          TournamentRequest(unique("t"), model.tournamentModelId),
          owner.externalId
        )

    /** Enters `player` in `t`: what makes removing its model only deactivate it. */
    private def withEntrant(game: Game, t: Tournament, player: Player): IO[Unit] =
        TestSession.resource.use(
          new EntrantRepo(_).create(game.gameId, GameType.Plain, t.tournamentId, player.playerId, None, 1500).void
        )

    private def count(table: String, t: Tournament): IO[Long] =
        TestSession.resource.use(
          _.unique(
            sql"SELECT count(*) FROM #$table WHERE game_id = #${t.gameId.value.toString} AND tournament_id = #${t.tournamentId.value.toString}"
                .query(int8)
          )
        )

    test("removing a model nobody has entered a tournament of deletes those tournaments, and then it") {
        val (removal, left, listed) = run(for {
            f <- fixtureOf(GameType.Plain)
            (game, admin, player) = f
            model <- models.create(game.gameId, roundRobin, admin.externalId)
            draft <- tournamentFrom(game, model, admin)
            other <- tournamentFrom(game, model, player)
            // Invited, but not entered.
            _ <- TestSession.resource.use(
              new TournamentInvitationRepo(_).invite(game.gameId, draft.tournamentId, player.playerId)
            )
            removal <- models.remove(game.gameId, model.tournamentModelId, admin.externalId)
            left <- List(draft, other).traverse(t =>
                TestSession.resource.use(new TournamentRepo(_).read(game.gameId, t.tournamentId))
            )
            invited <- count("tournament_invitation", draft)
            stages <- count("stage", draft)
            pools <- count("pool", draft)
            _ = assertEquals((invited, stages, pools), (0L, 0L, 0L))
            listed <- models.list(game.gameId, player.externalId)
        } yield (removal, left, listed))
        assertEquals(removal, TournamentModelRemoval(deleted = true, tournamentsDeleted = 2))
        assertEquals(left, List(None, None))
        assertEquals(listed, Nil)
    }

    test("removing a model a tournament of which has an entrant only deactivates it, and its tournaments carry on") {
        val (removal, listed, kept, fresh, edited, editModel) = run(for {
            f <- fixtureOf(GameType.Plain)
            (game, admin, player) = f
            model <- models.create(game.gameId, roundRobin, admin.externalId)
            entered <- tournamentFrom(game, model, admin)
            draft <- tournamentFrom(game, model, player)
            _ <- withEntrant(game, entered, player)
            removal <- models.remove(game.gameId, model.tournamentModelId, admin.externalId)
            listed <- models.list(game.gameId, player.externalId)
            kept <- List(entered, draft).traverse(t =>
                TestSession.resource.use(new TournamentRepo(_).read(game.gameId, t.tournamentId))
            )
            fresh <- tournamentFrom(game, model, player).attempt
            // A draft made from it before keeps it through an edit.
            edited <- services.tournaments.update(
              game.gameId,
              draft.tournamentId,
              TournamentRequest("renamed", model.tournamentModelId),
              player.externalId
            )
            editModel <- models.update(game.gameId, model.tournamentModelId, roundRobin, admin.externalId).attempt
        } yield (removal, listed, kept, fresh, edited, editModel))
        assertEquals(removal, TournamentModelRemoval(deleted = false, tournamentsDeleted = 0))
        assertEquals(listed, Nil)
        assert(kept.forall(_.isDefined), kept)
        assert(fresh.left.exists(_.isInstanceOf[NotFoundError]), fresh)
        assertEquals(edited.name, "renamed")
        assert(editModel.left.exists(_.isInstanceOf[ConflictError]), editModel)
    }

    test("a standard format an admin deactivated is not added back") {
        val again = run(for {
            f <- fixtureOf(GameType.Plain)
            (game, admin, _) = f
            added <- models.addStandard(game.gameId, admin.externalId)
            model = added.head
            t <- tournamentFrom(game, model, admin)
            _ <- withEntrant(game, t, admin)
            _ <- models.remove(game.gameId, model.tournamentModelId, admin.externalId)
            again <- models.addStandard(game.gameId, admin.externalId)
        } yield again)
        assertEquals(again, Nil)
    }
}
