package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import cats.syntax.all._
import com.vivi.matchmaker.model.tournament.standard.{
    Ladder,
    SingleElimination,
    StandardModel,
    StandardModels,
    WorldCup
}
import com.vivi.matchmaker.service.TournamentFixtures

/** The tournament tables through their repositories, against the local database. */
class TournamentRepoSpec extends FunSuite with QuietTests {

    private case class Fixture(game: Game, owner: Player, other: Player, model: TournamentModel)

    /** A standard model under an id of its own, with no classification: every game's models are shared by the specs
      * running at once, and a sync removes any classified model the code does not define.
      */
    private def copyOf(m: StandardModel): TournamentModel =
        m.model.copy(
          tournamentModelId = TournamentModelId(s"test-${m.id}-${java.util.UUID.randomUUID()}"),
          classification = None
        )

    private def fixture(session: Session[IO], gameType: GameType = GameType.Plain): IO[Fixture] =
        for {
            game <- new GameRepo[String](session).create(Generators.genGame(gameType).sample.get)
            owner <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            other <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            models = new TournamentModelRepo(session)
            written = copyOf(SingleElimination)
            _ <- models.create(written)
            model <- models.read(written.tournamentModelId).map(_.get)
        } yield Fixture(game = game, owner = owner, other = other, model = model)

    private def tournamentIn(f: Fixture): Tournament =
        Tournament(
          gameId = f.game.gameId,
          tournamentId = TournamentId.unassigned,
          name = "Spring open",
          description = "description",
          owner = f.owner.playerId,
          tournamentModelId = f.model.tournamentModelId,
          defaultPoolSize = 2,
          settings =
              TournamentSettings(roundDuration = Some(Duration.ofHours(48)), minRating = Some(1200), maxTurns = Some(5))
        )

    /** A tournament made from the fixture's model, as `TournamentService.create` makes one: the row, its stages and its
      * root pool.
      */
    private def made(session: Session[IO], f: Fixture): IO[Tournament] =
        for {
            t <- new TournamentRepo(session).create(tournamentIn(f))
            _ <- new StageRepo(session).copy(game = f.game.gameId, tournament = t.tournamentId, design = f.model.design)
            _ <- new PoolRepo(session).createRoot(f.game.gameId, t.tournamentId)
        } yield t

    /** `body` in one transaction, as the services run every change: a model or tournament and its stages are written
      * before each names the other, which the deferred keys between them allow only within one.
      */
    private def run[A](body: Session[IO] => IO[A]): A =
        TestSession.resource.use(session => session.transaction.use(_ => body(session))).unsafeRunSync()

    test("a model of every standard format is read back as the design it was written with, inner designs and all") {
        val (written, read) = run { session =>
            val repo = new TournamentModelRepo(session)
            val written = StandardModels.all.map(copyOf)
            written
                .traverse_(repo.create) *> written.traverse(m => repo.read(m.tournamentModelId)).map(r => (written, r))
        }
        assertEquals(read.map(_.map(_.design)), written.map(m => Some(m.design)))
        assertEquals(read.map(_.map(_.poolSize)), written.map(m => Some(m.poolSize)))
        assertEquals(read.map(_.map(_.version)), written.map(_ => Some(1)))
    }

    test("a game offers the models its admins chose, never an inner design, and stops when one is withdrawn") {
        val (offered, withdrawn, inner) = TestSession.resource
            .use { session =>
                for {
                    game <- new GameRepo[String](session).create(Generators.genGame(GameType.Plain).sample.get)
                    _ <- TournamentFixtures.model(session = session, game = game, key = "world-cup")
                    _ <- TournamentFixtures.model(session = session, game = game, key = "ladder")
                    repo = new TournamentModelRepo(session)
                    offered <- repo.listOffered(game.gameId)
                    _ <- repo.withdraw(game.gameId, TournamentModelId("ladder"))
                    withdrawn <- repo.listOffered(game.gameId)
                    // The knockout World Cup's groups lead to is an inner model of its own, named after its stage.
                    inner <- repo.read(TournamentModelId("world-cup/stage-2"))
                } yield (offered, withdrawn, inner)
            }
            .unsafeRunSync()
        assertEquals(offered.map(_.tournamentModelId.value), List("ladder", "world-cup"))
        assertEquals(withdrawn.map(_.tournamentModelId.value), List("world-cup"))
        assertEquals(inner.map(_.classification), Some(None))
    }

    test("a model is rewritten whole, and removed with its inner models, but not while a tournament is made from it") {
        val (f, edited) = run { session =>
            val repo = new TournamentModelRepo(session)
            for {
                f <- fixture(session)
                _ <- repo.replace(
                  Ladder.model.copy(tournamentModelId = f.model.tournamentModelId, version = 2)
                )
                edited <- repo.read(f.model.tournamentModelId)
                _ <- made(session, f)
            } yield (f, edited)
        }
        // In a transaction of its own, since the refusal aborts it.
        val refused = TestSession.resource
            .use(session =>
                session.transaction.use(_ => new TournamentModelRepo(session).delete(f.model.tournamentModelId))
            )
            .attempt
            .unsafeRunSync()
        val lone = copyOf(WorldCup)
        val removed = run { session =>
            val repo = new TournamentModelRepo(session)
            for {
                _ <- repo.create(lone)
                _ <- repo.delete(lone.tournamentModelId)
                removed <- repo.read(lone.tournamentModelId)
                inner <- repo.read(TournamentModelId(s"${lone.tournamentModelId.value}/stage-2"))
            } yield (removed, inner)
        }
        assertEquals(edited.map(m => (m.design, m.version)), Some((Ladder.design, 2)))
        assert(refused.isLeft, refused)
        assertEquals(removed, (None, None))
    }

    test("a tournament's settings survive a save, an update and a read, and its stages are its model's design") {
        val (created, read, updated, design, model) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                f <- fixture(session)
                created <- made(session, f)
                read <- repo.read(f.game.gameId, created.tournamentId)
                changed = created.copy(
                  name = "Autumn open",
                  status = TournamentStatus.Running,
                  defaultPoolSize = 4,
                  settings =
                      created.settings.copy(hasAutomaticRounds = true, roundDuration = None, maxRating = Some(2000)),
                  startedAt = Some(Instant.parse("2026-10-10T10:00:00Z"))
                )
                _ <- repo.update(changed)
                updated <- repo.readForUpdate(f.game.gameId, created.tournamentId)
                design <- new StageRepo(session).design(f.game.gameId, created.tournamentId)
            } yield (created, read, updated, design, f.model.design)
        }
        assertEquals(read, Some(created))
        assertEquals(updated.map(_.name), Some("Autumn open"))
        assertEquals(updated.map(_.status), Some(TournamentStatus.Running))
        assertEquals(updated.map(_.defaultPoolSize), Some(4))
        assertEquals(updated.map(_.settings.roundDuration), Some(None))
        assertEquals(design, Some(model))
    }

    test("entrants are listed in the order they entered, with characters, and can withdraw and come back") {
        val (listed, withdrawn, back, players, handedOn) = run { session =>
            val repo = new EntrantRepo(session)
            val characters = new CharacterRepo[String](session)
            for {
                f <- fixture(session, GameType.Character)
                t <- made(session, f)
                own <- characters.create(Generators.genCharacter(f.game.gameId, Some(f.owner.playerId)).sample.get)
                character <- characters.create(
                  Generators.genCharacter(f.game.gameId, Some(f.other.playerId)).sample.get
                )
                first <- repo.create(
                  game = f.game.gameId,
                  gt = GameType.Character,
                  tournament = t.tournamentId,
                  player = f.owner.playerId,
                  character = Some(own.characterId),
                  rating = 1500
                )
                second <- repo.create(
                  game = f.game.gameId,
                  gt = GameType.Character,
                  tournament = t.tournamentId,
                  player = f.other.playerId,
                  character = Some(character.characterId),
                  rating = 1700
                )
                _ <- repo.setWithdrawnAt(
                  game = f.game.gameId,
                  id = first.entrantId,
                  at = Some(Instant.parse("2026-10-11T00:00:00Z"))
                )
                withdrawn <- repo.readForUpdate(f.game.gameId, first.entrantId)
                _ <- repo.setWithdrawnAt(game = f.game.gameId, id = first.entrantId, at = None)
                listed <- repo.list(f.game.gameId, t.tournamentId)
                back <- repo.readForUpdate(f.game.gameId, first.entrantId)
                players <- repo.players(f.game.gameId, t.tournamentId)
                // The character changes hands: its entry is played by whoever owns it now.
                _ <- characters.update(character.copy(playerId = Some(f.owner.playerId)))
                handedOn <- repo.players(f.game.gameId, t.tournamentId)
            } yield (listed, withdrawn, back, players, handedOn(second.entrantId))
        }
        assertEquals(listed.map(_.ratingAtEntry), List(1500, 1700))
        assert(listed.forall(e => e.characterId.isDefined && e.characterName.isDefined))
        assertEquals(listed.head.nickname.nonEmpty, true)
        assert(withdrawn.exists(_._3.isDefined))
        assert(back.exists(_._3.isEmpty))
        // A character entry is played by its character's owner.
        assertEquals(players(listed(1).entrantId), listed(1).playerId)
        assertEquals(handedOn, listed.head.playerId)
    }

    test("invitations are kept once, listed by nickname, and revoked") {
        val (invited, listed, after) = run { session =>
            val repo = new InvitationRepo(session)
            for {
                f <- fixture(session)
                t <- made(session, f)
                _ <- repo.invite(game = f.game.gameId, tournament = t.tournamentId, player = f.other.playerId)
                _ <- repo.invite(game = f.game.gameId, tournament = t.tournamentId, player = f.other.playerId)
                invited <- repo.isInvited(game = f.game.gameId, tournament = t.tournamentId, player = f.other.playerId)
                listed <- repo.list(f.game.gameId, t.tournamentId)
                _ <- repo.revoke(game = f.game.gameId, tournament = t.tournamentId, player = f.other.playerId)
                after <- repo.isInvited(game = f.game.gameId, tournament = t.tournamentId, player = f.other.playerId)
            } yield (invited, listed, after)
        }
        assert(invited)
        assertEquals(listed.size, 1)
        assert(!after)
    }

    test("a player's tournaments are those they own, entered or were invited to") {
        val (mine, theirs, stranger) = run { session =>
            for {
                f <- fixture(session)
                t <- made(session, f)
                _ <- new InvitationRepo(session).invite(
                  game = f.game.gameId,
                  tournament = t.tournamentId,
                  player = f.other.playerId
                )
                nobody <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
                mine <- new TournamentRepo(session).listForPlayer(f.owner.playerId)
                theirs <- new TournamentRepo(session).listForPlayer(f.other.playerId)
                stranger <- new TournamentRepo(session).listForPlayer(nobody.playerId)
                game <- new TournamentRepo(session).listForGame(f.game.gameId)
            } yield (mine.map(_.tournamentId), theirs.map(_.tournamentId), stranger.size + 0 * game.size)
        }
        assertEquals(mine, theirs)
        assertEquals(mine.size, 1)
        assertEquals(stranger, 0)
    }
}
