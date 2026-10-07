package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.Duration
import munit.FunSuite
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, EloRatingRepo, GameRepo, PlayerRepo, TestSession}

/** Tournaments from creation to the start (Phase 3): who may do what, what is checked, and what a start lays out. */
class TournamentServiceSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    /* A ceiling on a case that has hung, not a budget; see CLAUDE.md. */
    private val caseTimeout = 60.seconds

    private val services = TestServices.services
    private val tournaments = services.tournaments

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def register(): IO[Player] = services.registration.register(unique("t"), unique("t-sub"))

    private def game(gameType: GameType = GameType.Plain, roles: Int = 2): IO[Game] =
        TestSession.resource.use(session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                gameType,
                "Duel",
                "Duel",
                "description",
                "https://engine.example.com/games",
                active = true,
                (1 to roles).map(i => GameRole(GameRoleId(0), GameId.unassigned, s"r$i", optional = false, s"R$i")),
                Seq.empty,
                unique("duel")
              )
            )
        )

    private def draft(g: Game, kind: TournamentType = TournamentType.SingleElim, poolSize: Int = 2): Tournament =
        Tournament(
          g.gameId,
          TournamentId.unassigned,
          TournamentClass.Elimination,
          "The Open",
          PlayerId.unassigned,
          invitational = false,
          roundDuration = Duration.ofDays(2),
          elimination = Some(EliminationSettings(kind, poolSize))
        )

    private def run[A](io: IO[A]): A = io.timeout(caseTimeout).unsafeRunSync()

    private def refusal[A](io: IO[A]): Throwable = run(io.attempt).swap.getOrElse(fail("expected a refusal"))

    private def makeAdmin(p: Player): IO[Unit] =
        TestSession.resource.use(session => new PlayerRepo(session).update(p.copy(isAdmin = true)))

    // ---- creating and editing --------------------------------------------------------------------------

    test("a tournament is created owned by its creator, whoever the body names") {
        val (owner, created) = run(for {
            g <- game()
            owner <- register()
            created <- tournaments.create(draft(g).copy(owner = PlayerId(1)), owner.externalId)
        } yield (owner, created))
        assertEquals(created.owner, owner.playerId)
        assertNotEquals(created.tournamentId, TournamentId.unassigned)
    }

    test("what a tournament must be is checked at creation") {
        val (g, owner) = run((game(roles = 3), register()).tupled)
        def refused(t: Tournament) = refusal(tournaments.create(t, owner.externalId))
        assert(refused(draft(g).copy(name = " ")).isInstanceOf[ValidationError])
        // Three roles to fill, and pools of two.
        assert(refused(draft(g)).isInstanceOf[ValidationError])
        assert(
          refused(draft(g, poolSize = 3).copy(elimination = Some(EliminationSettings(TournamentType.SingleElim, 3, 3))))
              .isInstanceOf[ValidationError]
        )
        assert(
          refused(draft(g, poolSize = 3).copy(roundDuration = Duration.ofSeconds(1))).isInstanceOf[ValidationError]
        )
        assert(
          refused(draft(g, poolSize = 3).copy(tournamentClass = TournamentClass.Cyclic)).isInstanceOf[ValidationError]
        )
        assert(
          refused(draft(g, poolSize = 3).copy(tournamentClass = TournamentClass.Ladder)).isInstanceOf[ValidationError]
        )
        assert(
          refused(draft(g, poolSize = 3).copy(minRating = Some(1600), maxRating = Some(1400)))
              .isInstanceOf[ValidationError]
        )
    }

    test("only a game's admin may create a tournament that is not friendly") {
        val (refused, allowed) = run(for {
            g <- game()
            player <- register()
            admin <- register()
            _ <- makeAdmin(admin)
            refused <- tournaments.create(draft(g).copy(friendly = false), player.externalId).attempt
            allowed <- tournaments.create(draft(g).copy(friendly = false), admin.externalId)
        } yield (refused, allowed))
        assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
        assert(!allowed.friendly)
    }

    test("only the owner edits; whether it is live may change after the start, whether it is friendly may not") {
        val (stranger, live, friendly) = run(for {
            g <- game()
            owner <- register()
            other <- register()
            t <- tournaments.create(draft(g), owner.externalId)
            stranger <- tournaments.update(g.gameId, t.tournamentId, t.copy(name = "Mine"), other.externalId).attempt
            _ <- List
                .fill(2)(register())
                .sequence
                .flatMap(_.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId)))
            _ <- tournaments.start(g.gameId, t.tournamentId, owner.externalId)
            live <- tournaments.update(
              g.gameId,
              t.tournamentId,
              t.copy(live = true, name = "Renamed"),
              owner.externalId
            )
            friendly <- tournaments.update(g.gameId, t.tournamentId, t.copy(friendly = false), owner.externalId).attempt
        } yield (stranger, live, friendly))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assert(live.live && live.name == "Renamed" && live.started)
        assert(friendly.left.exists(_.isInstanceOf[ConflictError]), friendly)
    }

    test("a game's admin hands a tournament to a new owner; its owner cannot") {
        val (refused, detail, newOwner) = run(for {
            g <- game()
            owner <- register()
            next <- register()
            admin <- register()
            _ <- makeAdmin(admin)
            t <- tournaments.create(draft(g), owner.externalId)
            refused <- tournaments.setOwner(g.gameId, t.tournamentId, next.playerId, owner.externalId).attempt
            _ <- tournaments.setOwner(g.gameId, t.tournamentId, next.playerId, admin.externalId)
            detail <- tournaments.detail(g.gameId, t.tournamentId, next.externalId)
        } yield (refused, detail, next))
        assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
        assertEquals(detail.owner.playerId, newOwner.playerId)
    }

    // ---- invitations and entries -----------------------------------------------------------------------

    test("an invitational tournament admits only the invited, and an invitee may decline") {
        val (stranger, entered, declined, owners) = run(for {
            g <- game()
            owner <- register()
            invitee <- register()
            other <- register()
            t <- tournaments.create(draft(g).copy(invitational = true), owner.externalId)
            _ <- tournaments.invite(g.gameId, t.tournamentId, Some(invitee.playerId), None, owner.externalId)
            _ <- tournaments.invite(g.gameId, t.tournamentId, Some(other.playerId), None, owner.externalId)
            stranger <- tournaments.enter(g.gameId, t.tournamentId, None, (owner.externalId)).attempt
            entered <- tournaments.enter(g.gameId, t.tournamentId, None, invitee.externalId)
            _ <- tournaments.uninvite(g.gameId, t.tournamentId, other.playerId, other.externalId)
            declined <- tournaments.enter(g.gameId, t.tournamentId, None, other.externalId).attempt
            owners <- tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
        } yield (stranger, entered, declined, owners))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assertEquals(entered.characterId, None)
        assert(declined.left.exists(_.isInstanceOf[UnauthorizedError]), declined)
        assertEquals(owners.invitedPlayers.size, 1)
        assertEquals(owners.entrants.map(_.entryId), List(entered.entryId))
    }

    test("an open tournament's rating bounds are held against the entrant's rating, the starting one if none") {
        val (low, high, unrated) = run(for {
            g <- game()
            owner <- register()
            strong <- register()
            fresh <- register()
            _ <- TestSession.resource.use(session =>
                new EloRatingRepo(session)
                    .ensureRatedIn(g.gameId, List(EloRatingRepo.RatingKey(None, strong.playerId))) *>
                    new EloRatingRepo(session).set(g.gameId, strong.playerId, 1700, owner.playerId)
            )
            t <- tournaments.create(draft(g).copy(maxRating = Some(1600)), owner.externalId)
            high <- tournaments.enter(g.gameId, t.tournamentId, None, strong.externalId).attempt
            unrated <- tournaments.enter(g.gameId, t.tournamentId, None, fresh.externalId).attempt
            floor <- tournaments.create(draft(g).copy(minRating = Some(1550)), owner.externalId)
            low <- tournaments.enter(g.gameId, floor.tournamentId, None, fresh.externalId).attempt
        } yield (low, high, unrated))
        assert(high.left.exists(_.isInstanceOf[ValidationError]), high)
        assert(unrated.isRight, unrated)
        assert(low.left.exists(_.isInstanceOf[ValidationError]), low)
    }

    test("a character game is entered as a character the caller owns, and once") {
        val (none, notMine, mine, twice) = run(for {
            g <- game(GameType.Character)
            owner <- register()
            entrant <- register()
            other <- register()
            character <- TestSession.resource.use(session =>
                new CharacterRepo[String](session).create(
                  Character(CharacterId(0), g.gameId, "Iron Mike", "d", "", Some(entrant.playerId))
                )
            )
            t <- tournaments.create(draft(g), owner.externalId)
            none <- tournaments.enter(g.gameId, t.tournamentId, None, entrant.externalId).attempt
            notMine <- tournaments
                .enter(g.gameId, t.tournamentId, Some(character.characterId), other.externalId)
                .attempt
            mine <- tournaments.enter(g.gameId, t.tournamentId, Some(character.characterId), entrant.externalId)
            twice <- tournaments
                .enter(g.gameId, t.tournamentId, Some(character.characterId), entrant.externalId)
                .attempt
        } yield (none, notMine, mine, twice))
        assert(none.left.exists(_.isInstanceOf[ValidationError]), none)
        assert(notMine.left.exists(_.isInstanceOf[UnauthorizedError]), notMine)
        assert(mine.characterId.isDefined)
        assert(twice.left.exists(_.isInstanceOf[ConflictError]), twice)
    }

    test("withdrawing before the start removes the entry; after it, marks the participant withdrawn") {
        val (before, after) = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(3)(register()).sequence
            t <- tournaments.create(draft(g), owner.externalId)
            entries <- players.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            _ <- tournaments.withdraw(g.gameId, t.tournamentId, entries.head.entryId, players.head.externalId)
            before <- tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
            _ <- tournaments.start(g.gameId, t.tournamentId, owner.externalId)
            _ <- tournaments.withdraw(g.gameId, t.tournamentId, entries(1).entryId, owner.externalId)
            after <- tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
        } yield (before, after))
        assertEquals(before.entrants.size, 2)
        assertEquals(after.entrants.size, 2)
        assertEquals(after.entrants.count(_.participant.exists(_.withdrawn)), 1)
    }

    // ---- starting -----------------------------------------------------------------------------------

    test("a start seeds the field by rating and lays out every round's pools; round 1 is left to the owner") {
        val (detail, strongest) = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(5)(register()).sequence
            _ <- TestSession.resource.use { session =>
                val repo = new EloRatingRepo(session)
                repo.ensureRatedIn(g.gameId, List(EloRatingRepo.RatingKey(None, players(3).playerId))) *>
                    repo.set(g.gameId, players(3).playerId, 1800, owner.playerId)
            }
            t <- tournaments.create(draft(g), owner.externalId)
            _ <- players.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            detail <- tournaments.start(g.gameId, t.tournamentId, owner.externalId)
        } yield (detail, players(3)))
        assert(detail.tournament.started)
        // The highest rated is seed 1, and every seed from 1 to 5 is held once.
        assertEquals(detail.entrants.head.player.playerId, strongest.playerId)
        assertEquals(detail.entrants.flatMap(_.participant.map(_.seed)), (1 to 5).toList)
        // Five entrants in pools of two: four first-round pools (three byes), two semi-finals, and a final with its
        // consolation pool.
        assertEquals(detail.rounds.map(_.round), List(1, 2, 3))
        assert(detail.rounds.forall(!_.started))
        assertEquals(detail.pools.groupBy(_.fixture.round).view.mapValues(_.size).toMap, Map(1 -> 4, 2 -> 2, 3 -> 2))
        assertEquals(detail.pools.filter(_.fixture.round == 1).flatMap(_.slots).count(_.source == SlotSource.Bye), 3)
    }

    test("a round robin is one round of one pool of everybody") {
        val detail = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(4)(register()).sequence
            t <- tournaments.create(draft(g, TournamentType.RoundRobin), owner.externalId)
            _ <- players.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            detail <- tournaments.start(g.gameId, t.tournamentId, owner.externalId)
        } yield detail)
        assertEquals(detail.pools.map(_.slots.size), List(4))
    }

    test("a start needs two entrants, is the owner's, and a started tournament takes no more entries") {
        val (alone, stranger, late) = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(3)(register()).sequence
            t <- tournaments.create(draft(g), owner.externalId)
            _ <- tournaments.enter(g.gameId, t.tournamentId, None, players.head.externalId)
            alone <- tournaments.start(g.gameId, t.tournamentId, owner.externalId).attempt
            _ <- tournaments.enter(g.gameId, t.tournamentId, None, players(1).externalId)
            stranger <- tournaments.start(g.gameId, t.tournamentId, players.head.externalId).attempt
            _ <- tournaments.start(g.gameId, t.tournamentId, owner.externalId)
            late <- tournaments.enter(g.gameId, t.tournamentId, None, players(2).externalId).attempt
        } yield (alone, stranger, late))
        assert(alone.left.exists(_.isInstanceOf[ValidationError]), alone)
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assert(late.left.exists(_.isInstanceOf[ConflictError]), late)
    }

    test("two starts at once: one starts the tournament, the other is refused, and it is laid out once") {
        val (outcomes, detail) = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(4)(register()).sequence
            t <- tournaments.create(draft(g), owner.externalId)
            _ <- players.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            outcomes <- List.fill(2)(tournaments.start(g.gameId, t.tournamentId, owner.externalId).attempt).parSequence
            detail <- tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
        } yield (outcomes, detail))
        assertEquals(outcomes.count(_.isRight), 1)
        assert(outcomes.exists(_.left.exists(_.isInstanceOf[ConflictError])), outcomes)
        assertEquals(detail.entrants.flatMap(_.participant).size, 4)
        assertEquals(detail.rounds.size, 2)
    }

    test("an entry racing a start either makes the field or is refused, never seeded half way") {
        val (entered, detail) = run(for {
            g <- game()
            owner <- register()
            players <- List.fill(3)(register()).sequence
            late <- register()
            t <- tournaments.create(draft(g), owner.externalId)
            _ <- players.traverse(p => tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            raced <- (
              tournaments.start(g.gameId, t.tournamentId, owner.externalId).attempt,
              tournaments.enter(g.gameId, t.tournamentId, None, late.externalId).attempt
            ).parTupled
            detail <- tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
        } yield (raced._2, detail))
        val seeded = detail.entrants.flatMap(_.participant).size
        if (entered.isRight) assertEquals((detail.entrants.size, seeded), (4, 4))
        else {
            assert(entered.left.exists(_.isInstanceOf[ConflictError]), entered)
            assertEquals((detail.entrants.size, seeded), (3, 3))
        }
    }

    test("a player's tournaments say which they own, entered, and were invited to") {
        val mine = run(for {
            g <- game()
            owner <- register()
            player <- register()
            owned <- tournaments.create(draft(g).copy(name = "owned"), player.externalId)
            entered <- tournaments.create(draft(g).copy(name = "entered"), owner.externalId)
            _ <- tournaments.enter(g.gameId, entered.tournamentId, None, player.externalId)
            invited <- tournaments.create(draft(g).copy(name = "invited", invitational = true), owner.externalId)
            _ <- tournaments.invite(g.gameId, invited.tournamentId, Some(player.playerId), None, owner.externalId)
            mine <- tournaments.mine(player.externalId)
        } yield mine)
        assertEquals(
          mine.map(s => s.tournament.name -> (s.owned, s.entered, s.invited)).toMap,
          Map("owned" -> (true, false, false), "entered" -> (false, true, false), "invited" -> (false, false, true))
        )
    }
}
