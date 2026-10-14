package com.vivi.matchmaker.service

import cats.effect.{Deferred, IO}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import munit.FunSuite
import skunk.implicits._
import skunk.codec.all.int8
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign, TournamentGame, Validation}
import com.vivi.matchmaker.model.tournament.standard.StandardModels
import com.vivi.matchmaker.persistence.{
    EntrantRepo,
    GameAdminRepo,
    GameRepo,
    Generators,
    PlayerRepo,
    TestSession,
    InvitationRepo,
    TournamentModelRepo,
    TournamentRepo
}

/** The models every game's tournaments are made from: offered and withdrawn by each game's admins, and synced with the
  * code by a site admin, against the local database.
  *
  * The models are every game's, shared with the specs running at the same time, so a model this spec makes up for a
  * sync to remove has an id of its own, and nothing here changes a standard model another spec plays.
  */
class TournamentModelServiceSpec extends FunSuite with QuietTests {

    private val services = TestServices.services
    private val models = services.tournamentModels

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    private val ladder = TournamentModelId("ladder")
    private val worldCup = TournamentModelId("world-cup")

    /** A two-player game offering World Cup and not Ladder, an admin of it, and a player who is not one. */
    private def fixture: IO[(Game, Player, Player)] =
        for {
            admin <- services.registration.register(nickname = unique("admin"), externalId = unique("sub"))
            player <- services.registration.register(nickname = unique("player"), externalId = unique("sub"))
            game <- TestSession.resource.use { session =>
                for {
                    game <- new GameRepo[String](session).create(Generators.genGame(GameType.Plain).sample.get)
                    _ <- new GameAdminRepo(session).grant(
                      player = admin.playerId,
                      game = game.gameId,
                      grantedBy = admin.playerId
                    )
                    _ <- TournamentFixtures.model(session = session, game = game, key = "world-cup")
                    _ <- TournamentFixtures.model(session = session, game = game, key = "ladder")
                    _ <- new TournamentModelRepo(session).withdraw(game.gameId, ladder)
                } yield game
            }
        } yield (game, admin, player)

    private def siteAdmin: IO[Player] =
        for {
            p <- services.registration.register(nickname = unique("overall"), externalId = unique("sub"))
            _ <- TestSession.resource.use(new PlayerRepo(_).update(p.copy(isAdmin = true)))
        } yield p

    test("a game's admin offers and withdraws a model, and any player sees what the game offers") {
        val (before, offered, seen, withdrawn) = run(for {
            f <- fixture
            (game, admin, player) = f
            before <- models.list(game.gameId, player.externalId)
            offered <- models.offer(gameId = game.gameId, id = ladder, callerExternalId = admin.externalId)
            seen <- models.list(game.gameId, player.externalId)
            withdrawn <- models.withdraw(gameId = game.gameId, id = worldCup, callerExternalId = admin.externalId)
        } yield (before, offered, seen, withdrawn))
        assertEquals(before.map(_.tournamentModelId), List(worldCup))
        assertEquals(offered.map(_.tournamentModelId).toSet, Set(ladder, worldCup))
        assertEquals(seen, offered)
        assertEquals(withdrawn.map(_.tournamentModelId), List(ladder))
    }

    test("every model a game could offer is listed for its admins to choose from") {
        val all = run(fixture.flatMap((_, _, player) => models.listAll(player.externalId)))
        assert(all.exists(_.tournamentModelId == worldCup), all.map(_.tournamentModelId))
        assert(all.forall(m => m.classification.isDefined && m.isActive), all)
    }

    test("a player who does not administer the game may not offer or withdraw a model, nor a game's admin sync them") {
        val outcome = run(for {
            f <- fixture
            (game, admin, player) = f
            offer <- models.offer(gameId = game.gameId, id = ladder, callerExternalId = player.externalId).attempt
            withdraw <- models
                .withdraw(gameId = game.gameId, id = worldCup, callerExternalId = player.externalId)
                .attempt
            // Administering a game is not enough to change every game's models.
            sync <- models.sync(admin.externalId).attempt
        } yield List(offer, withdraw, sync))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[UnauthorizedError])), outcome.toString)
    }

    test("a model that is not there, or is an inner design, is not offered") {
        val outcome = run(for {
            f <- fixture
            (game, admin, _) = f
            unknown <- models
                .offer(
                  gameId = game.gameId,
                  id = TournamentModelId("no-such-model"),
                  callerExternalId = admin.externalId
                )
                .attempt
            inner <- models
                .offer(
                  gameId = game.gameId,
                  id = TournamentModelId("world-cup/stage-2"),
                  callerExternalId = admin.externalId
                )
                .attempt
        } yield List(unknown, inner))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[NotFoundError])), outcome.toString)
    }

    test("a sync stores every model the code defines, at its version, and brings back one that was deactivated") {
        val repechage = TournamentModelId("brazilian-repechage")
        val (synced, stored) = run(for {
            admin <- siteAdmin
            _ <- models.sync(admin.externalId)
            // Brazilian repechage, which no other spec plays: deactivated as though the code had once dropped it.
            _ <- TestSession.resource.use(session =>
                session.transaction.use(_ => new TournamentModelRepo(session).deactivate(repechage))
            )
            synced <- models.sync(admin.externalId)
            stored <- TestSession.resource.use(new TournamentModelRepo(_).listActive)
        } yield (synced, stored))
        assertEquals((synced.added, synced.updated), (Nil, List(repechage)))
        StandardModels.all.foreach { m =>
            val found = stored.find(_.tournamentModelId.value == m.id)
            assertEquals(found.map(s => (s.version, s.design)), Some((m.version, m.design)), m.id)
        }
    }

    // ---- a sync removing a model the code no longer defines ----

    /** A model the code does not define, with a classification, so that a sync takes it for one the code dropped. */
    private def dropped: TournamentModel =
        TournamentModel(
          tournamentModelId = TournamentModelId(unique("test-dropped")),
          version = 1,
          name = "Dropped",
          description = "",
          classification = Some("Round robin"),
          poolSize = None,
          hasLateEntries = false,
          TournamentDesign(List(StageDesign(hasBrackets = false, demote = None)))
        )

    /** `model` written and offered in `game`. */
    private def offering(game: Game, model: TournamentModel): IO[Unit] =
        TestSession.resource.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentModelRepo(session)
                repo.create(model) *> repo.offer(game.gameId, model.tournamentModelId)
            }
        }

    private def tournamentFrom(game: Game, model: TournamentModel, owner: Player): IO[Tournament] =
        services.tournaments.create(
          gameId = game.gameId,
          request = TournamentRequest(name = unique("t"), tournamentModelId = model.tournamentModelId),
          callerExternalId = owner.externalId
        )

    private def count(table: String, t: Tournament): IO[Long] =
        TestSession.resource.use(
          _.unique(
            sql"""SELECT count(*) FROM #$table
                  WHERE game_id = #${t.gameId.value.toString} AND tournament_id = #${t.tournamentId.value.toString}"""
                .query(int8)
          )
        )

    test("a sync deletes a model the code dropped, with its tournaments, while nobody has entered one") {
        val model = dropped
        val (synced, left, gone) = run(for {
            f <- fixture
            (game, admin, player) = f
            _ <- offering(game, model)
            draft <- tournamentFrom(game = game, model = model, owner = admin)
            // Invited, but not entered.
            _ <- TestSession.resource.use(
              new InvitationRepo(_)
                  .invite(game = game.gameId, tournament = draft.tournamentId, player = player.playerId)
            )
            synced <- siteAdmin.flatMap(a => models.sync(a.externalId))
            left <- TestSession.resource.use(new TournamentRepo(_).read(game.gameId, draft.tournamentId))
            invited <- count("invitation", draft)
            stages <- count("stage", draft)
            pools <- count("pool", draft)
            _ = assertEquals((invited, stages, pools), (0L, 0L, 0L))
            gone <- TestSession.resource.use(new TournamentModelRepo(_).read(model.tournamentModelId))
        } yield (synced, left, gone))
        assert(synced.deleted.contains(model.tournamentModelId), synced)
        assertEquals(left, None)
        assertEquals(gone, None)
    }

    test("a sync only deactivates a dropped model a tournament of which has an entrant; its tournaments carry on") {
        val model = dropped
        val (synced, offered, stored, kept, fresh, edited) = run(for {
            f <- fixture
            (game, admin, player) = f
            _ <- offering(game, model)
            entered <- tournamentFrom(game = game, model = model, owner = admin)
            draft <- tournamentFrom(game = game, model = model, owner = player)
            _ <- TestSession.resource.use(
              new EntrantRepo(_).create(
                game = game.gameId,
                gt = GameType.Plain,
                tournament = entered.tournamentId,
                player = player.playerId,
                character = None,
                rating = 1500
              )
            )
            synced <- siteAdmin.flatMap(a => models.sync(a.externalId))
            offered <- models.list(game.gameId, player.externalId)
            stored <- TestSession.resource.use(new TournamentModelRepo(_).read(model.tournamentModelId))
            kept <- List(entered, draft).traverse(t =>
                TestSession.resource.use(new TournamentRepo(_).read(game.gameId, t.tournamentId))
            )
            fresh <- tournamentFrom(game = game, model = model, owner = player).attempt
            // A draft made from it before keeps it through an edit.
            edited <- services.tournaments.update(
              gameId = game.gameId,
              tournamentId = draft.tournamentId,
              request = TournamentRequest(name = "renamed", tournamentModelId = model.tournamentModelId),
              callerExternalId = player.externalId
            )
        } yield (synced, offered, stored, kept, fresh, edited))
        assert(synced.deactivated.contains(model.tournamentModelId), synced)
        assert(!offered.exists(_.tournamentModelId == model.tournamentModelId), offered)
        assertEquals(stored.map(_.isActive), Some(false))
        assert(kept.forall(_.isDefined), kept)
        assert(fresh.left.exists(_.isInstanceOf[NotFoundError]), fresh)
        assertEquals(edited.name, "renamed")
    }

    test("a new game is made after a sync under way, never during it") {
        val (waited, made) = run(for {
            admin <- siteAdmin
            held <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            // A sync under way, as far as anything else can tell: the lock held until released.
            syncing <- TestSession.resource
                .use(session =>
                    session.transaction.use(_ =>
                        TournamentModelService.lockSync(session) *> held.complete(()) *> release.get
                    )
                )
                .start
            _ <- held.get
            creating <- services.games
                .createOrUpdate(
                  externalUserId = admin.externalId,
                  game = Generators.genGame(GameType.Plain).sample.get.copy(gameId = GameId.unassigned)
                )
                .start
            // Still waiting a second later.
            waited <- creating.join.timeout(1.second).attempt
            _ <- release.complete(())
            _ <- syncing.joinWithNever
            made <- creating.joinWithNever
        } yield (waited, made))
        assert(waited.isLeft, waited)
        assertNotEquals(made.gameId, GameId.unassigned)
    }

    test("a new game offers every standard model it can play") {
        val (offered, game) = run(for {
            admin <- siteAdmin
            _ <- models.sync(admin.externalId)
            made <- services.games.createOrUpdate(
              externalUserId = admin.externalId,
              game = Generators.genGame(GameType.Plain).sample.get.copy(gameId = GameId.unassigned)
            )
            offered <- models.list(made.gameId, admin.externalId)
        } yield (offered, made))
        val standard = offered.map(_.tournamentModelId.value).filter(id => StandardModels.find(id).isDefined)
        val expected = StandardModels.all
            .filter(m =>
                Validation
                    .designProblems(design = m.design, game = TournamentGame.of(game), defaultPoolSize = m.poolSize)
                    .forall(_.isWarning)
            )
            .map(_.id)
        assertEquals(standard.sorted, expected.sorted)
    }
}
