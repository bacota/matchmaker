package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import skunk.Session
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StandardDesigns, Tournament as Progress, Env, TournamentGame}

/** The tournament tables (V52) through their repositories, against the local database. */
class TournamentRepoSpec extends FunSuite with QuietTests {

    private case class Fixture(game: Game, owner: Player, other: Player)

    private def fixture(session: Session[IO], gameType: GameType = GameType.Plain): IO[Fixture] =
        for {
            game <- new GameRepo[String](session).create(Generators.genGame(gameType).sample.get)
            owner <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            other <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
        } yield Fixture(game, owner, other)

    private def tournamentIn(f: Fixture): Tournament =
        Tournament(
          f.game.gameId,
          TournamentId.unassigned,
          "Spring open",
          "description",
          f.owner.playerId,
          StandardDesigns.build("single-elimination", 2, None).toOption.get,
          Some("single-elimination"),
          TournamentSettings(roundDuration = Some(Duration.ofHours(48)), minRating = Some(1200), maxTurns = Some(5))
        )

    private def run[A](body: Session[IO] => IO[A]): A = TestSession.resource.use(body).unsafeRunSync()

    test("a tournament's settings and design survive a save, an update and a read") {
        val (created, read, updated) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                f <- fixture(session)
                created <- repo.create(tournamentIn(f))
                read <- repo.read(f.game.gameId, created.tournamentId)
                changed = created.copy(
                  name = "Autumn open",
                  status = TournamentStatus.Running,
                  settings = created.settings.copy(autoRounds = true, roundDuration = None, maxRating = Some(2000)),
                  startedAt = Some(Instant.parse("2026-10-10T10:00:00Z")),
                  roundDeadline = Some(Instant.parse("2026-10-12T10:00:00Z"))
                )
                _ <- repo.update(changed)
                updated <- repo.readForUpdate(f.game.gameId, created.tournamentId)
            } yield (created, read, updated)
        }
        assertEquals(read, Some(created))
        assertEquals(updated.map(_.name), Some("Autumn open"))
        assertEquals(updated.map(_.status), Some(TournamentStatus.Running))
        assertEquals(updated.map(_.settings.roundDuration), Some(None))
        assertEquals(updated.flatMap(_.roundDeadline), Some(Instant.parse("2026-10-12T10:00:00Z")))
        assertEquals(updated.map(_.design), Some(created.design))
    }

    test("a tournament's state is absent before it starts, and is read back as it was written") {
        val (before, after, written) = run { session =>
            val repo = new TournamentRepo(session)
            for {
                f <- fixture(session)
                created <- repo.create(tournamentIn(f))
                before <- repo.readState(f.game.gameId, created.tournamentId)
                env = Env(created.design, TournamentGame.of(f.game), Map(1L -> 1600, 2L -> 1500, 3L -> 1400))
                state = Progress.start(List(1L, 2L, 3L), env)
                _ <- repo.writeState(f.game.gameId, created.tournamentId, state)
                after <- repo.readState(f.game.gameId, created.tournamentId)
            } yield (before, after, state)
        }
        assertEquals(before, None)
        assertEquals(after, Some(written))
    }

    test("entrants are listed in the order they entered, with characters, and can withdraw and come back") {
        val (listed, counted, withdrawn, back, players) = run { session =>
            val repo = new EntrantRepo(session)
            for {
                f <- fixture(session, GameType.Character)
                t <- new TournamentRepo(session).create(tournamentIn(f))
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
                t <- new TournamentRepo(session).create(tournamentIn(f))
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
                t <- new TournamentRepo(session).create(tournamentIn(f))
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

    test("a round's matches and seats are written, claimed, chosen for, and found again by the match they became") {
        val (round, claimed, chosen, found, choosing) = run { session =>
            val matches = new TournamentMatchRepo(session)
            for {
                f <- fixture(session)
                t <- new TournamentRepo(session).create(tournamentIn(f))
                entrants = new EntrantRepo(session)
                a <- entrants.create(f.game.gameId, GameType.Plain, t.tournamentId, f.owner.playerId, None, 1500)
                b <- entrants.create(f.game.gameId, GameType.Plain, t.tournamentId, f.other.playerId, None, 1500)
                at = Instant.parse("2026-10-11T00:00:00Z")
                _ <- matches.insert(
                  List(
                    TournamentMatch(
                      f.game.gameId,
                      t.tournamentId,
                      1,
                      0,
                      1,
                      TournamentMatchStatus.Choosing,
                      tiesAllowed = false,
                      None,
                      List(
                        TournamentSeat(a.entrantId, None, Some(0), Some(at)),
                        TournamentSeat(b.entrantId, None, Some(1))
                      )
                    )
                  )
                )
                choosing <- matches.choosingFor(f.owner.playerId)
                round <- matches.listRound(f.game.gameId, t.tournamentId, 1)
                role = f.game.roles.head.gameRoleId
                _ <- matches.updateSeat(
                  f.game.gameId,
                  t.tournamentId,
                  1,
                  0,
                  round.head.seats.head.copy(gameRoleId = Some(role), chosenAt = Some(at))
                )
                chosen <- matches.readForUpdate(f.game.gameId, t.tournamentId, 1, 0)
                matchId = MatchId(java.util.UUID.randomUUID().toString)
                _ <- new MatchRepo(session).createForTournament(
                  Generators
                      .genMatch(f.game.gameId, matchId, None, f.owner.playerId)
                      .sample
                      .get,
                  t.tournamentId
                )
                _ <- matches.setStatus(
                  f.game.gameId,
                  t.tournamentId,
                  1,
                  0,
                  TournamentMatchStatus.Started,
                  Some(matchId)
                )
                claimed <- matches.read(f.game.gameId, t.tournamentId, 1, 0)
                found <- matches.findByMatch(f.game.gameId, matchId)
            } yield (
              round,
              claimed,
              chosen,
              found.map(x => (x._1 == t.tournamentId, x._2, x._3)),
              choosing.map(c => (c._3, c._4))
            )
        }
        assertEquals(round.size, 1)
        assertEquals(round.head.seats.map(_.chooseOrder), List(Some(0), Some(1)))
        assertEquals(choosing, List((1, 0)))
        assert(chosen.exists(_.seats.head.gameRoleId.isDefined))
        assertEquals(claimed.map(_.status), Some(TournamentMatchStatus.Started))
        assertEquals(found, Some((true, 1, 0)))
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
