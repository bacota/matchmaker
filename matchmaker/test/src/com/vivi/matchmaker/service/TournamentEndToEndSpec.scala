package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import munit.FunSuite
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.ending.{MatchDue, MatchEndings}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, MatchRepo, ParticipantRepo, TestSession}

/** Whole tournaments, from creation to final ranks (Phase 5), against a fake engine and a queue the test delivers. */
class TournamentEndToEndSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    /* A ceiling on a case that has hung; see CLAUDE.md. A whole tournament is many matches. */
    private val caseTimeout = 180.seconds

    /** Makes every game; answers a status call with `pendingSince`'s first seat waiting, when set. */
    private class Engine extends GameEngineClient {
        @volatile var pendingSince: Option[Instant] = None
        @volatile var firstSeats: Map[String, Long] = Map.empty

        def createGame(url: String, key: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO { firstSeats = firstSeats + (request.matchId -> request.players.head.participantId) }
                .as(CreateGameResponse(s"https://engine/status/${request.matchId}", "https://engine/play", None))

        def status(url: String, key: Option[String], since: Option[Instant]): IO[GameStatusResponse] =
            IO {
                val matchId = url.stripPrefix("https://engine/status/")
                GameStatusResponse(
                  completed = false,
                  participants = pendingSince.toList.flatMap(at =>
                      firstSeats.get(matchId).toList.map(EngineParticipantStatus(_, true, false, Some(at)))
                  )
                )
            }
    }

    private class Queue extends MatchEndings {
        @volatile var dues: List[MatchDue] = Nil
        @volatile var checks: List[(GameId, MatchId)] = Nil
        def ended(gameId: GameId, matchId: MatchId): IO[Unit] = IO.unit
        def ratingsChanged(gameId: GameId): IO[Unit] = IO.unit
        def due(message: MatchDue): IO[Unit] = IO { dues = dues :+ message }
        def check(gameId: GameId, matchId: MatchId): IO[Unit] = IO { checks = checks :+ (gameId -> matchId) }
    }

    private case class World(engine: Engine, queue: Queue, services: Services[String])

    private def world(): World = {
        val engine = new Engine
        val queue = new Queue
        World(engine, queue, TestServices.servicesWith(engine, matchEndings = Some(queue)))
    }

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def run[A](io: IO[A]): A = io.timeout(caseTimeout).unsafeRunSync()

    private def game(roles: Int = 2): IO[Game] =
        TestSession.resource.use(session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "tictactoe",
                "Tic-tac-toe",
                "description",
                "https://engine.example.com/games",
                active = true,
                (1 to roles).map(i => GameRole(GameRoleId(0), GameId.unassigned, s"r$i", optional = false, s"R$i")),
                Seq.empty,
                unique("ttt")
              )
            )
        )

    private case class Field(owner: Player, t: Tournament, bySeed: List[Player])

    private def started(
        w: World,
        g: Game,
        n: Int,
        kind: TournamentType = TournamentType.SingleElim,
        poolSize: Int = 2
    ): IO[Field] =
        for {
            owner <- w.services.registration.register(unique("owner"), unique("owner-sub"))
            players <- List.fill(n)(w.services.registration.register(unique("p"), unique("p-sub"))).sequence
            t <- w.services.tournaments.create(
              Tournament(
                g.gameId,
                TournamentId.unassigned,
                TournamentClass.Elimination,
                "The Open",
                PlayerId.unassigned,
                invitational = false,
                roundDuration = Duration.ofHours(2),
                elimination = Some(EliminationSettings(kind, poolSize))
              ),
              owner.externalId
            )
            _ <- players.traverse(p => w.services.tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            detail <- w.services.tournaments.start(g.gameId, t.tournamentId, owner.externalId)
        } yield Field(
          owner,
          detail.tournament,
          detail.entrants.map(e => players.find(_.playerId == e.player.playerId).get)
        )

    /** A tournament match's seats, each with whose seat it is. */
    private case class Played(m: Match, seats: List[(ParticipantId, PlayerId)])

    private def unfinished(g: Game, t: Tournament): IO[List[Played]] =
        TestSession.resource.use { session =>
            val repo = new MatchRepo(session)
            repo.listForGame(g.gameId, 200)
                .flatMap(_.traverse(gm => repo.read(g.gameId, gm.matchId).map(_.get)))
                .flatMap(
                  _.filter(m => m.fixture.exists(_.tournamentId == t.tournamentId) && !m.completed && !m.cancelled)
                      .traverse(m =>
                          new ParticipantRepo(session)
                              .listForMatch(g.gameId, m.matchId)
                              .map(ps => Played(m, ps.map((p, _, _) => p.participantId -> p.playerId)))
                      )
                )
        }

    /** Reports each seat's rank, as `decide` places it, and settles the match's ending, as the listener would. */
    private def report(w: World, g: Game, played: Played, ranks: Map[PlayerId, Int]): IO[Unit] =
        w.services.engine.recordResults(
          g.gameId,
          played.m.matchId,
          played.seats.map((id, player) =>
              ReportedResult(id, ranks(player), Map.empty, ranks(player) == ranks.values.min)
          ),
          g.externalId
        ) *> w.services.ending.settle(g.gameId, played.m.matchId).void

    /** Plays the tournament to its end: every round started, every match made and decided by `decide`, which ranks the
      * players of a match. Answers the field as it ends.
      */
    private def playOut(w: World, g: Game, f: Field)(decide: Played => Map[PlayerId, Int]): IO[TournamentDetail] = {
        def detail = w.services.tournaments.detail(g.gameId, f.t.tournamentId, f.owner.externalId)
        def loop(guard: Int): IO[TournamentDetail] =
            detail.flatMap { d =>
                if (d.tournament.ended || guard == 0) IO.pure(d)
                else
                    d.rounds.find(!_.completed) match {
                        case None => IO.pure(d)
                        case Some(r) =>
                            val start =
                                if (r.started) IO.unit
                                else
                                    w.services.tournamentPlay
                                        .startRound(
                                          g.gameId,
                                          f.t.tournamentId,
                                          r.round,
                                          RoundOverrides(),
                                          f.owner.externalId
                                        )
                                        .void
                            for {
                                _ <- start
                                dues = { val ds = w.queue.dues; w.queue.dues = Nil; ds }
                                _ <- dues.traverse(w.services.tournamentPlay.createMatch)
                                open <- unfinished(g, f.t)
                                _ <- open.traverse_(p => report(w, g, p, decide(p)))
                                next <- loop(guard - 1)
                            } yield next
                    }
            }
        loop(50)
    }

    /** The better seed wins, by the field's order. */
    private def bySeed(f: Field)(p: Played): Map[PlayerId, Int] = {
        val order = f.bySeed.map(_.playerId)
        p.seats.map(_._2).sortBy(order.indexOf).zipWithIndex.map((player, i) => player -> (i + 1)).toMap
    }

    private def finalRanks(d: TournamentDetail): Map[PlayerId, Option[Int]] =
        d.entrants.map(e => e.player.playerId -> e.participant.flatMap(_.finalRank)).toMap

    test("eight players, single elimination: the seeds win through, the final is above the consolation, and it ends") {
        val w = world()
        val (f, d) = run(for {
            g <- game()
            f <- started(w, g, 8)
            d <- playOut(w, g, f)(bySeed(f))
        } yield (f, d))
        assert(d.tournament.ended)
        val ranks = finalRanks(d)
        val seeds = f.bySeed.map(_.playerId)
        // 1 beat 2 in the final; 3 beat 4 for third; the quarter-finalists share fifth.
        assertEquals(seeds.map(ranks), List(1, 2, 3, 4, 5, 5, 5, 5).map(Some(_)))
    }

    test("five players: three byes in the first round, and everybody still finishes ranked") {
        val w = world()
        val d = run(for {
            g <- game()
            f <- started(w, g, 5)
            d <- playOut(w, g, f)(bySeed(f))
        } yield d)
        assert(d.tournament.ended)
        assert(finalRanks(d).values.forall(_.isDefined), finalRanks(d))
        assertEquals(finalRanks(d).values.flatten.min, 1)
    }

    test("an upset carries the underdog through, with the favourite's seed") {
        val w = world()
        val (f, d) = run(for {
            g <- game()
            f <- started(w, g, 4)
            // Seed 4 beats everybody.
            d <- playOut(w, g, f)(p =>
                if (p.seats.exists(_._2 == f.bySeed(3).playerId))
                    p.seats.map((_, player) => player -> (if (player == f.bySeed(3).playerId) 1 else 2)).toMap
                else bySeed(f)(p)
            )
        } yield (f, d))
        assertEquals(finalRanks(d)(f.bySeed(3).playerId), Some(1))
    }

    test("a player who withdraws mid-tournament has their next match go unplayed, and their opponent goes through") {
        val w = world()
        val (f, d) = run(for {
            g <- game()
            f <- started(w, g, 4)
            // Round 1 played out by seed; seed 2 then withdraws before the final.
            _ <- w.services.tournamentPlay
                .startRound(g.gameId, f.t.tournamentId, 1, RoundOverrides(), f.owner.externalId)
            _ <- { val ds = w.queue.dues; w.queue.dues = Nil; ds.traverse(w.services.tournamentPlay.createMatch) }
            open <- unfinished(g, f.t)
            _ <- open.traverse_(p => report(w, g, p, bySeed(f)(p)))
            entry <- w.services.tournaments
                .detail(g.gameId, f.t.tournamentId, f.owner.externalId)
                .map(_.entrants.find(_.player.playerId == f.bySeed(1).playerId).get.entryId)
            _ <- w.services.tournaments.withdraw(g.gameId, f.t.tournamentId, entry, f.bySeed(1).externalId)
            d <- playOut(w, g, f)(bySeed(f))
        } yield (f, d))
        assert(d.tournament.ended)
        assertEquals(finalRanks(d)(f.bySeed.head.playerId), Some(1))
    }

    test("a draw is a point each; in a round robin the points decide, and a cancelled match scores nothing") {
        val w = world()
        val (f, d) = run(for {
            g <- game()
            f <- started(w, g, 3, TournamentType.RoundRobin)
            // a–b drawn, a beats c, b–c cancelled with no ranks: a 4 points, b 1, c 0.
            d <- {
                val List(a, b, c) = f.bySeed.map(_.playerId): @unchecked
                w.services.tournamentPlay
                    .startRound(g.gameId, f.t.tournamentId, 1, RoundOverrides(), f.owner.externalId) *>
                    IO.defer {
                        val ds = w.queue.dues; w.queue.dues = Nil; ds.traverse(w.services.tournamentPlay.createMatch)
                    } *>
                    unfinished(g, f.t).flatMap(_.traverse_ { p =>
                        val players = p.seats.map(_._2).toSet
                        if (players == Set(a, b)) report(w, g, p, Map(a -> 1, b -> 1))
                        else if (players == Set(a, c)) report(w, g, p, Map(a -> 1, c -> 2))
                        else
                            w.services.matches.cancel(g.gameId, p.m.matchId, f.owner.externalId) *>
                                w.services.ending.settle(g.gameId, p.m.matchId).void
                    }) *>
                    w.services.tournaments.detail(g.gameId, f.t.tournamentId, f.owner.externalId)
            }
        } yield (f, d))
        assert(d.tournament.ended)
        assertEquals(f.bySeed.map(p => finalRanks(d)(p.playerId)), List(Some(1), Some(2), Some(3)))
    }

    test("a three-player match is scored by its placings: 1, 2 and 3 finish in that order") {
        val w = world()
        val (f, d) = run(for {
            g <- game(roles = 3)
            f <- started(w, g, 3, TournamentType.RoundRobin, poolSize = 3)
            // Placed against their seeds: the third seed first, the first seed last.
            d <- playOut(w, g, f)(p =>
                p.seats.map((_, player) => player -> (3 - f.bySeed.indexWhere(_.playerId == player))).toMap
            )
        } yield (f, d))
        assertEquals(f.bySeed.map(p => finalRanks(d)(p.playerId)), List(Some(3), Some(2), Some(1)))
    }

    test("a player whose turn runs out is forfeited by Check round, and the round goes on") {
        val w = world()
        val (checked, d) = run(for {
            g <- game()
            f <- started(w, g, 2)
            // Every first seat has been waiting since well before its hour on the clock.
            _ = w.engine.pendingSince = Some(Instant.now().minusSeconds(3 * 3600))
            _ <- w.services.tournamentPlay
                .startRound(g.gameId, f.t.tournamentId, 1, RoundOverrides(), f.owner.externalId)
            _ <- { val ds = w.queue.dues; w.queue.dues = Nil; ds.traverse(w.services.tournamentPlay.createMatch) }
            checked <- w.services.tournamentPlay.checkRound(g.gameId, f.t.tournamentId, 1, f.owner.externalId)
            _ <- w.queue.checks.traverse_((game, id) => w.services.tournamentPlay.check(game, id))
            open <- unfinished(g, f.t)
            ended <- TestSession.resource.use(session =>
                new MatchRepo(session).listForGame(g.gameId, 10).map(_.map(_.matchId))
            )
            _ <- ended.traverse_(w.services.ending.settle(g.gameId, _))
            d <- w.services.tournaments.detail(g.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- IO(assertEquals(open, Nil))
        } yield (checked, d))
        assertEquals(checked.queued, 1)
        assert(d.tournament.ended)
    }
}
