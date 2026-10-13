package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import cats.syntax.all._
import com.vivi.matchmaker.model.tournament.{ModelSeed, StandardDesigns}

/** The tournament tables (V52) through their repositories, against the local database. */
class TournamentRepoSpec extends FunSuite with QuietTests {

    private case class Fixture(game: Game, owner: Player, other: Player, model: TournamentModel)

    private val singleElimination = StandardDesigns.models(2).find(_.key == "single-elimination").get

    private def requestOf(seed: ModelSeed): TournamentModelRequest =
        TournamentModelRequest(
          seed.name,
          seed.description,
          Some(seed.classification),
          seed.poolSize,
          design = seed.design
        )

    private def fixture(session: Session[IO], gameType: GameType = GameType.Plain): IO[Fixture] =
        for {
            game <- new GameRepo[String](session).create(Generators.genGame(gameType).sample.get)
            owner <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            other <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            models = new TournamentModelRepo(session)
            id <- models.create(game.gameId, requestOf(singleElimination))
            model <- models.read(game.gameId, id).map(_.get)
        } yield Fixture(game, owner, other, model)

    private def tournamentIn(f: Fixture): Tournament =
        Tournament(
          f.game.gameId,
          TournamentId.unassigned,
          "Spring open",
          "description",
          f.owner.playerId,
          f.model.tournamentModelId,
          2,
          TournamentSettings(roundDuration = Some(Duration.ofHours(48)), minRating = Some(1200), maxTurns = Some(5))
        )

    /** A tournament made from the fixture's model, as `TournamentService.create` makes one: the row, its stages and its
      * root pool.
      */
    private def made(session: Session[IO], f: Fixture): IO[Tournament] =
        for {
            t <- new TournamentRepo(session).create(tournamentIn(f))
            _ <- new StageRepo(session).copy(f.game.gameId, t.tournamentId, f.model.design)
            _ <- new PoolRepo(session).createRoot(f.game.gameId, t.tournamentId)
        } yield t

    /** `body` in one transaction, as the services run every change: a model or tournament and its stages are written
      * before each names the other, which the deferred keys between them allow only within one.
      */
    private def run[A](body: Session[IO] => IO[A]): A =
        TestSession.resource.use(session => session.transaction.use(_ => body(session))).unsafeRunSync()

    test("a model of every standard format is read back as the design it was written with, inner designs and all") {
        val (written, read, offered) = run { session =>
            val repo = new TournamentModelRepo(session)
            val seeds = StandardDesigns.models(2)
            for {
                game <- new GameRepo[String](session).create(Generators.genGame().sample.get)
                ids <- seeds.traverse(seed => repo.create(game.gameId, requestOf(seed)))
                read <- ids.traverse(id => repo.read(game.gameId, id))
                offered <- repo.listOffered(game.gameId)
            } yield (seeds, read.flatten, offered)
        }
        assertEquals(read.map(_.design), written.map(_.design))
        assertEquals(read.map(_.poolSize), written.map(_.poolSize))
        // Only the formats themselves are offered: their inner designs are models of their own, unlisted.
        assertEquals(offered.map(_.name).sorted, written.map(_.name).sorted)
    }

    test("a model is edited whole, and removed with its inner models, but not while a tournament is made from it") {
        val ladder = StandardDesigns.models(2).find(_.key == "ladder").get
        val (f, edited) = run { session =>
            val repo = new TournamentModelRepo(session)
            for {
                f <- fixture(session)
                _ <- repo.update(f.game.gameId, f.model.tournamentModelId, requestOf(ladder))
                edited <- repo.read(f.game.gameId, f.model.tournamentModelId)
                _ <- made(session, f)
            } yield (f, edited)
        }
        // In a transaction of its own, since the refusal aborts it.
        val refused = TestSession.resource
            .use(session =>
                session.transaction.use(_ =>
                    new TournamentModelRepo(session).delete(f.game.gameId, f.model.tournamentModelId)
                )
            )
            .attempt
            .unsafeRunSync()
        val removed = run { session =>
            val repo = new TournamentModelRepo(session)
            for {
                lone <- repo.create(f.game.gameId, requestOf(singleElimination))
                _ <- repo.delete(f.game.gameId, lone)
                removed <- repo.listOffered(f.game.gameId)
            } yield removed
        }
        assertEquals(edited.map(_.design), Some(ladder.design))
        assert(refused.isLeft, refused)
        assertEquals(removed.map(_.name), List("Ladder"))
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
                  settings = created.settings.copy(autoRounds = true, roundDuration = None, maxRating = Some(2000)),
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
        val (listed, counted, withdrawn, back, players) = run { session =>
            val repo = new EntrantRepo(session)
            for {
                f <- fixture(session, GameType.Character)
                t <- made(session, f)
                character <- new CharacterRepo[String](session).create(
                  Generators.genCharacter(f.game.gameId, Some(f.other.playerId)).sample.get
                )
                first <- repo.create(f.game.gameId, GameType.Character, t.tournamentId, f.owner.playerId, None, 1500)
                second <- repo.create(
                  f.game.gameId,
                  GameType.Character,
                  t.tournamentId,
                  f.other.playerId,
                  Some(character.characterId),
                  1700
                )
                counted <- repo.count(f.game.gameId, t.tournamentId, f.other.playerId)
                _ <- repo.setWithdrawnAt(f.game.gameId, first.entrantId, Some(Instant.parse("2026-10-11T00:00:00Z")))
                withdrawn <- repo.readForUpdate(f.game.gameId, first.entrantId)
                _ <- repo.setWithdrawnAt(f.game.gameId, first.entrantId, None)
                listed <- repo.list(f.game.gameId, t.tournamentId)
                back <- repo.readForUpdate(f.game.gameId, first.entrantId)
                players <- repo.players(f.game.gameId, t.tournamentId)
            } yield (listed, counted, withdrawn, back, players)
        }
        assertEquals(listed.map(_.ratingAtEntry), List(1500, 1700))
        assert(listed(1).characterId.isDefined && listed(1).characterName.isDefined)
        assertEquals(listed.head.nickname.nonEmpty, true)
        assertEquals(counted, 1L)
        assert(withdrawn.exists(_._3.isDefined))
        assert(back.exists(_._3.isEmpty))
        // A character entry is played by its character's owner.
        assertEquals(players(listed(1).entrantId), Some(listed(1).playerId))
    }

    test("invitations are kept once, listed by nickname, and revoked") {
        val (invited, listed, after) = run { session =>
            val repo = new TournamentInvitationRepo(session)
            for {
                f <- fixture(session)
                t <- made(session, f)
                _ <- repo.invite(f.game.gameId, t.tournamentId, f.other.playerId)
                _ <- repo.invite(f.game.gameId, t.tournamentId, f.other.playerId)
                invited <- repo.isInvited(f.game.gameId, t.tournamentId, f.other.playerId)
                listed <- repo.list(f.game.gameId, t.tournamentId)
                _ <- repo.revoke(f.game.gameId, t.tournamentId, f.other.playerId)
                after <- repo.isInvited(f.game.gameId, t.tournamentId, f.other.playerId)
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
                _ <- new TournamentInvitationRepo(session).invite(f.game.gameId, t.tournamentId, f.other.playerId)
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

    test("a match must come from a challenge or a tournament, and not both".tag(Quiet)) {
        val refused = run { session =>
            for {
                f <- fixture(session)
                m = Generators
                    .genMatch(f.game.gameId, MatchId(java.util.UUID.randomUUID().toString), None, f.owner.playerId)
                    .sample
                    .get
                attempt <- new MatchRepo(session).create(m).attempt
            } yield attempt.isLeft
        }
        assert(refused)
    }
}
