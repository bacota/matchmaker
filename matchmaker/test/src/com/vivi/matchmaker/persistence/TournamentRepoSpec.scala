package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.Duration
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._

/** What the tournament tables (V53) need to exist in a test: a game, an owner, and a tournament. */
object Tournaments {

    def player(session: Session[IO]): IO[Player] = new PlayerRepo(session).create(Generators.genPlayer.sample.get)

    def game(session: Session[IO], gameType: GameType = GameType.Plain): IO[Game] =
        new GameRepo[String](session).create(Generators.genGame(gameType).sample.get.copy(active = true))

    def elimination(game: Game, owner: PlayerId): Tournament =
        Tournament(
          gameId = game.gameId,
          tournamentId = TournamentId.unassigned,
          tournamentClass = TournamentClass.Elimination,
          name = "The Open",
          owner = owner,
          invitational = false,
          roundDuration = Duration.ofDays(2),
          elimination = Some(EliminationSettings(TournamentType.SingleElim, poolSize = 2))
        )

    /** An elimination tournament, with its game and its owner. */
    def made(session: Session[IO], gameType: GameType = GameType.Plain): IO[(Game, Player, Tournament)] =
        for {
            g <- game(session, gameType)
            owner <- player(session)
            t <- new TournamentRepo(session).create(elimination(g, owner.playerId))
        } yield (g, owner, t)
}

class TournamentRepoSpec extends FunSuite {
    TestMigration.ensure()

    private def run[A](f: Session[IO] => IO[A]): A = TestSession.resource.use(f).unsafeRunSync()

    test("a tournament reads back as it was created, its elimination settings with it") {
        val (created, read) = run { session =>
            for {
                g <- Tournaments.game(session)
                owner <- Tournaments.player(session)
                t = Tournaments
                    .elimination(g, owner.playerId)
                    .copy(
                      minRating = Some(1200),
                      maxRating = Some(1800),
                      live = true,
                      isPublic = true,
                      rotations = 1,
                      elimination = Some(EliminationSettings(TournamentType.RoundRobin, 4, 2, 0, Tiebreaker.Rematch))
                    )
                created <- new TournamentRepo(session).create(t)
                read <- new TournamentRepo(session).read(g.gameId, created.tournamentId)
            } yield (created, read)
        }
        assertNotEquals(created.tournamentId, TournamentId.unassigned)
        assertEquals(read, Some(created))
    }

    test("a ladder has no elimination settings") {
        val read = run { session =>
            for {
                g <- Tournaments.game(session)
                owner <- Tournaments.player(session)
                t <- new TournamentRepo(session).create(
                  Tournaments
                      .elimination(g, owner.playerId)
                      .copy(tournamentClass = TournamentClass.Ladder, elimination = None)
                )
                read <- new TournamentRepo(session).readForUpdate(g.gameId, t.tournamentId)
            } yield read
        }
        assertEquals(read.map(_.elimination), Some(None))
    }

    test("an edit rewrites the settings, a start and an end are stamped, and the owner can be handed on") {
        val (edited, stamped, newOwner) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                made <- Tournaments.made(session)
                (g, _, t) = made
                other <- Tournaments.player(session)
                _ <- repo.update(
                  t.copy(
                    name = "Renamed",
                    live = true,
                    roundDuration = Duration.ofHours(6),
                    elimination = t.elimination.map(_.copy(poolSize = 4, minPoolAdvance = 2))
                  )
                )
                edited <- repo.read(g.gameId, t.tournamentId)
                _ <- repo.start(g.gameId, t.tournamentId)
                _ <- repo.end(g.gameId, t.tournamentId)
                _ <- repo.setOwner(g.gameId, t.tournamentId, other.playerId)
                stamped <- repo.read(g.gameId, t.tournamentId)
            } yield (edited.get, stamped.get, other.playerId)
        }
        assertEquals(edited.name, "Renamed")
        assertEquals(edited.roundDuration, Duration.ofHours(6))
        assertEquals(edited.elimination.map(e => (e.poolSize, e.minPoolAdvance)), Some((4, 2)))
        assert(edited.live)
        assert(stamped.started && stamped.ended)
        assertEquals(stamped.owner, newOwner)
    }

    test("the database refuses an inverted rating range") {
        val outcomes = run { session =>
            for {
                made <- Tournaments.made(session)
                (g, owner, _) = made
                inverted <- new TournamentRepo(session)
                    .create(
                      Tournaments.elimination(g, owner.playerId).copy(minRating = Some(1800), maxRating = Some(1200))
                    )
                    .attempt
            } yield inverted
        }
        assert(outcomes.isLeft)
    }

    test("invitations by name and by character, and whom they let in") {
        val (players, characters, invitedSelf, invitedOwner, stranger, withdrawn) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                g <- Tournaments.game(session, GameType.Character)
                owner <- Tournaments.player(session)
                t <- repo.create(Tournaments.elimination(g, owner.playerId))
                invitee <- Tournaments.player(session)
                characterOwner <- Tournaments.player(session)
                nobody <- Tournaments.player(session)
                character <- new CharacterRepo[String](session).create(
                  Generators.genCharacter(g.gameId, Some(characterOwner.playerId)).sample.get
                )
                _ <- repo.invite(g.gameId, t.tournamentId, invitee.playerId)
                _ <- repo.invite(g.gameId, t.tournamentId, invitee.playerId)
                _ <- repo.inviteCharacter(g.gameId, t.tournamentId, character.characterId)
                players <- repo.invitations(g.gameId, t.tournamentId)
                characters <- repo.characterInvitations(g.gameId, t.tournamentId)
                invitedSelf <- repo.isInvitedForShare(g.gameId, t.tournamentId, invitee.playerId)
                invitedOwner <- repo.isInvitedForShare(g.gameId, t.tournamentId, characterOwner.playerId)
                stranger <- repo.isInvitedForShare(g.gameId, t.tournamentId, nobody.playerId)
                withdrawn <- repo.uninvite(g.gameId, t.tournamentId, invitee.playerId)
            } yield (players.size, characters.size, invitedSelf, invitedOwner, stranger, withdrawn)
        }
        assertEquals((players, characters), (1, 1))
        assert(invitedSelf && invitedOwner && !stranger && withdrawn)
    }

    test("a player's tournaments are those they own, entered, or were invited to") {
        val (mine, theirs) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                made <- Tournaments.made(session)
                (g, owner, owned) = made
                entrant <- Tournaments.player(session)
                entered <- repo.create(Tournaments.elimination(g, owner.playerId).copy(name = "entered"))
                _ <- new EntryRepo(session).create(
                  TournamentEntry(g.gameId, entered.tournamentId, EntryId(0), entrant.playerId)
                )
                invited <- repo.create(Tournaments.elimination(g, owner.playerId).copy(name = "invited"))
                _ <- repo.invite(g.gameId, invited.tournamentId, entrant.playerId)
                mine <- repo.listForPlayer(owner.playerId)
                theirs <- repo.listForPlayer(entrant.playerId)
            } yield (mine.map(_.tournamentId).toSet, theirs.map(_.name).toSet)
        }
        assertEquals(mine.size, 3)
        assertEquals(theirs, Set("entered", "invited"))
    }
}
