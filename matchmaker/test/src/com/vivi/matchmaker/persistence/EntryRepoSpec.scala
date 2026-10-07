package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._

class EntryRepoSpec extends FunSuite {
    TestMigration.ensure()

    private def run[A](f: Session[IO] => IO[A]): A = TestSession.resource.use(f).unsafeRunSync()

    test("entries read back in the order they were made, a character entry with its character") {
        val (made, listed, mine) = run { session =>
            val repo = new EntryRepo(session)
            for {
                g <- Tournaments.game(session, GameType.Character)
                owner <- Tournaments.player(session)
                t <- new TournamentRepo(session).create(Tournaments.elimination(g, owner.playerId))
                first <- Tournaments.player(session)
                second <- Tournaments.player(session)
                character <- new CharacterRepo[String](session).create(
                  Generators.genCharacter(g.gameId, Some(second.playerId)).sample.get
                )
                a <- repo.create(TournamentEntry(g.gameId, t.tournamentId, EntryId(0), first.playerId))
                b <- repo.create(
                  TournamentEntry(g.gameId, t.tournamentId, EntryId(0), second.playerId, Some(character.characterId))
                )
                listed <- repo.listForTournament(g.gameId, t.tournamentId)
                mine <- repo.listForPlayer(g.gameId, t.tournamentId, second.playerId)
            } yield (List(a, b), listed, mine)
        }
        assertEquals(listed, made)
        assertEquals(mine, made.drop(1))
    }

    test("a player enters once, and a removed entry is gone") {
        val (second, afterDelete) = run { session =>
            val repo = new EntryRepo(session)
            for {
                made <- Tournaments.made(session)
                (g, owner, t) = made
                entrant <- Tournaments.player(session)
                entry <- repo.create(TournamentEntry(g.gameId, t.tournamentId, EntryId(0), entrant.playerId))
                second <- session.transaction.use(_ =>
                    repo.create(TournamentEntry(g.gameId, t.tournamentId, EntryId(0), entrant.playerId)).attempt
                )
                _ <- repo.delete(g.gameId, t.tournamentId, entry.entryId)
                afterDelete <- repo.read(g.gameId, t.tournamentId, entry.entryId)
            } yield (second, afterDelete)
        }
        assert(second.isLeft)
        assertEquals(afterDelete, None)
    }
}
