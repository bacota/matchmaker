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
import com.vivi.matchmaker.persistence.{GameRepo, MatchRepo, ParticipantRepo, ResultRepo, TestSession}

/** Playing a tournament's rounds (Phase 4), with a stub engine and a queue whose messages the test delivers itself — so
  * that what each step left behind is there when it is checked.
  */
class TournamentPlaySpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    /* A ceiling on a case that has hung; see CLAUDE.md. */
    private val caseTimeout = 90.seconds

    /** Makes every game it is asked to, unless told to fail; says nobody is pending. */
    private class Engine extends GameEngineClient {
        @volatile var requests: List[CreateGameRequest] = Nil
        @volatile var failing: Boolean = false
        @volatile var statuses: Int = 0

        def createGame(url: String, key: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            if (failing) IO.raiseError(GameEngineError("the engine is down"))
            else
                IO { requests = requests :+ request }.as(
                  CreateGameResponse(
                    s"https://engine/status/${request.matchId}",
                    s"https://engine/play/${request.matchId}",
                    None
                  )
                )

        def status(url: String, key: Option[String], since: Option[Instant]): IO[GameStatusResponse] =
            IO { statuses += 1 }.as(GameStatusResponse(completed = false, participants = Nil))
    }

    /** Records what is queued, for the test to deliver. Endings are settled at once, as the listener would. */
    private class Queue extends MatchEndings {
        @volatile var dues: List[MatchDue] = Nil
        @volatile var checks: List[MatchId] = Nil
        @volatile var ended: List[MatchId] = Nil
        def ended(gameId: GameId, matchId: MatchId): IO[Unit] = IO { ended = ended :+ matchId }
        def ratingsChanged(gameId: GameId): IO[Unit] = IO.unit
        def due(message: MatchDue): IO[Unit] = IO { dues = dues :+ message }
        def check(gameId: GameId, matchId: MatchId): IO[Unit] = IO { checks = checks :+ matchId }
        def take(): List[MatchDue] = { val d = dues; dues = Nil; d }
    }

    private case class World(engine: Engine, queue: Queue, services: Services[String])

    private def world(): World = {
        val engine = new Engine
        val queue = new Queue
        World(engine, queue, TestServices.servicesWith(engine, matchEndings = Some(queue)))
    }

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def run[A](io: IO[A]): A = io.timeout(caseTimeout).unsafeRunSync()

    private def game(choosesRoles: Boolean = false, preferred: Boolean = false): IO[Game] =
        TestSession.resource.use(session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "duel",
                "Duel",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "white", optional = false, "White", preferred = preferred),
                  GameRole(GameRoleId(0), GameId.unassigned, "black", optional = false, "Black")
                ),
                Seq.empty,
                unique("duel"),
                choosesRoles = choosesRoles
              )
            )
        )

    /** A started single-elimination tournament of `n` players, with its owner and its entrants best seed first. */
    private def started(
        w: World,
        n: Int,
        g: Game,
        tiebreaker: Tiebreaker = Tiebreaker.Score,
        live: Boolean = false,
        kind: TournamentType = TournamentType.SingleElim
    ): IO[(Player, Tournament, List[Player])] =
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
                elimination = Some(EliminationSettings(kind, 2, tiebreaker = tiebreaker)),
                live = live
              ),
              owner.externalId
            )
            _ <- players.traverse(p => w.services.tournaments.enter(g.gameId, t.tournamentId, None, p.externalId))
            detail <- w.services.tournaments.start(g.gameId, t.tournamentId, owner.externalId)
            bySeed = detail.entrants.map(e => players.find(_.playerId == e.player.playerId).get)
        } yield (owner, detail.tournament, bySeed)

    /** Every match of the tournament with its seats' players, by round, pool and number. */
    private def matchesOf(g: Game, t: Tournament): IO[List[(Match, List[(Participant, String)])]] =
        TestSession.resource.use { session =>
            val repo = new MatchRepo(session)
            repo.listForGame(g.gameId, 100)
                .flatMap(_.traverse(gm => repo.read(g.gameId, gm.matchId).map(_.get)))
                .flatMap { ms =>
                    ms.filter(_.fixture.exists(_.tournamentId == t.tournamentId))
                        .traverse(m =>
                            new ParticipantRepo(session)
                                .listForMatch(g.gameId, m.matchId)
                                .map(ps => m -> ps.map((p, ext, _) => p -> ext))
                        )
                }
        }

    /** The engine reporting `winner` as first in match `m`. */
    private def win(w: World, g: Game, m: Match, seats: List[(Participant, String)], winner: Player): IO[Unit] =
        w.services.engine.recordResults(
          g.gameId,
          m.matchId,
          seats.map((p, _) =>
              ReportedResult(
                p.participantId,
                if (p.playerId == winner.playerId) 1 else 2,
                Map.empty,
                p.playerId == winner.playerId
              )
          ),
          g.externalId
        ) *> w.services.ending.settle(g.gameId, m.matchId).void

    private def deliver(w: World): IO[List[Settlement]] = w.queue.take().traverse(w.services.tournamentPlay.createMatch)

    test(
      "starting round 1 queues one match per pool; each is made once, from its slots, by the owner, on a chess clock"
    ) {
        val w = world()
        val (g, t, owner, seeds, made, again) = run(for {
            g <- game()
            s <- started(w, 4, g)
            (owner, t, seeds) = s
            work <- w.services.tournamentPlay
                .startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            dues = w.queue.dues
            _ <- deliver(w)
            made <- matchesOf(g, t)
            // A second delivery of the same messages makes nothing more.
            again <- dues.traverse(w.services.tournamentPlay.createMatch)
        } yield (g, t, owner, seeds, made, again))
        assertEquals(made.size, 2)
        assert(again.forall(_ == Settlement.Settled))
        assertEquals(w.engine.requests.size, 2)
        made.foreach { (m, seats) =>
            assertEquals(m.creator, owner.playerId)
            assertEquals(m.challengeId, None)
            assertEquals(m.timeLimit, Some(Duration.ofHours(1)))
            assertEquals(m.timeLimitKind, TimeLimitKind.Total)
            assert(!m.live)
            assert(seats.forall(_._1.gameRoleId.isDefined))
        }
        // Seeds 1 and 4 meet, and 2 and 3; the better seed takes the first role.
        val pairs = made.map(_._2.map(s => seeds.indexWhere(_.externalId == s._2) + 1).toSet).toSet
        assertEquals(pairs, Set(Set(1, 4), Set(2, 3)))
    }

    test(
      "a round completes once its matches are over: seeds swap on an upset, and the next round is filled by the winners"
    ) {
        val w = world()
        val (round1, seeds, round2Players) = run(for {
            g <- game()
            s <- started(w, 4, g)
            (owner, t, seeds) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            refused <- w.services.tournamentPlay
                .startRound(g.gameId, t.tournamentId, 2, RoundOverrides(), owner.externalId)
                .attempt
            _ <- IO(assert(refused.left.exists(_.isInstanceOf[ConflictError]), refused))
            _ <- deliver(w)
            made <- matchesOf(g, t)
            // Seed 4 beats seed 1; seed 2 beats seed 3.
            _ <- made.traverse_ { (m, seats) =>
                val players = seats.map(s => seeds.find(_.externalId == s._2).get)
                val winner = if (players.contains(seeds(3))) seeds(3) else seeds(1)
                win(w, g, m, seats, winner)
            }
            detail <- w.services.tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 2, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
            all <- matchesOf(g, t)
            finalMatch = all.filter(_._1.fixture.exists(f => !made.exists(_._1.fixture.contains(f))))
        } yield (detail, seeds, finalMatch.flatMap(_._2.map(_._2))))
        assert(round1.rounds.find(_.round == 1).exists(_.completed))
        // The upset swapped seeds 1 and 4 within their pool.
        val seedOf = round1.entrants.map(e => e.player.playerId -> e.participant.get.seed).toMap
        assertEquals(seedOf(seeds(3).playerId), 1)
        assertEquals(seedOf(seeds(0).playerId), 4)
        // The final (and the consolation pool) are made from the winners and losers.
        assert(Set(seeds(3).externalId, seeds(1).externalId).subsetOf(round2Players.toSet), round2Players)
    }

    test("a match the engine will not make is undone and owed, and made on the next delivery") {
        val w = world()
        val (first, made, second) = run(for {
            g <- game()
            s <- started(w, 2, g)
            (owner, t, _) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            dues = w.queue.take()
            _ = w.engine.failing = true
            first <- dues.traverse(w.services.tournamentPlay.createMatch)
            made <- matchesOf(g, t)
            _ = w.engine.failing = false
            second <- dues.traverse(w.services.tournamentPlay.createMatch)
        } yield (first, made, second))
        assert(first.forall(_.isInstanceOf[Settlement.Owed]), first)
        assertEquals(made, Nil)
        assertEquals(second, List(Settlement.Settled))
    }

    test(
      "only the owner starts a round; Resume queues only what has not been made; Check round refuses a second press"
    ) {
        val w = world()
        val (stranger, resumed, checked, again) = run(for {
            g <- game()
            s <- started(w, 4, g)
            (owner, t, seeds) = s
            stranger <- w.services.tournamentPlay
                .startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), seeds.head.externalId)
                .attempt
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            dues = w.queue.take()
            _ <- w.services.tournamentPlay.createMatch(dues.head)
            resumed <- w.services.tournamentPlay.resume(g.gameId, t.tournamentId, 1, owner.externalId)
            checked <- w.services.tournamentPlay.checkRound(g.gameId, t.tournamentId, 1, owner.externalId)
            again <- w.services.tournamentPlay.checkRound(g.gameId, t.tournamentId, 1, owner.externalId).attempt
        } yield (stranger, resumed, checked, again))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assertEquals(resumed.queued, 1)
        // Nothing is overdue, and nothing is live: nothing to check.
        assertEquals(checked.queued, 0)
        assert(again.left.exists(_.isInstanceOf[ConflictError]), again)
    }

    test("a live tournament's round is live, and its matches' clocks start when each turn does") {
        val w = world()
        val (round, made) = run(for {
            g <- game()
            s <- started(w, 2, g, live = true)
            (owner, t, _) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
            detail <- w.services.tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
            made <- matchesOf(g, t)
        } yield (detail.rounds.head, made))
        assert(round.live)
        assert(made.forall(_._1.live))
        assertEquals(w.engine.requests.flatMap(_.live), List(LiveTerms(3600L, "TOTAL", Some(false))))
    }

    test("in a game whose players choose roles, the better seed takes the preferred role and nobody else need choose") {
        val w = world()
        run(for {
            g <- game(choosesRoles = true, preferred = true)
            s <- started(w, 2, g)
            (owner, t, _) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
        } yield ())
        val request = w.engine.requests.head
        assertEquals(request.roleChoice, None)
        assertEquals(request.players.flatMap(_.role).toSet, Set("white", "black"))
    }

    test("in a game whose players choose roles and none is preferred, the seats choose in seed order") {
        val w = world()
        val (made, seeds) = run(for {
            g <- game(choosesRoles = true)
            s <- started(w, 2, g)
            (owner, t, seeds) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
            made <- matchesOf(g, t)
        } yield (made, seeds))
        val request = w.engine.requests.head
        val firstSeat = made.head._2.find(_._2 == seeds.head.externalId).get._1.participantId.value
        assertEquals(request.roleChoice.map(_.order.head), Some(firstSeat))
        assertEquals(request.roleChoice.map(_.roles), Some(List("white", "black")))
        assert(made.head._2.forall(_._1.gameRoleId.isEmpty))
    }

    test(
      "a final level on points under REMATCH queues a tie-break with no tie allowed, and is not complete until it is played"
    ) {
        val w = world()
        val (afterDraw, rematch, afterRematch) = run(for {
            g <- game()
            s <- started(w, 2, g, tiebreaker = Tiebreaker.Rematch)
            (owner, t, seeds) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
            made <- matchesOf(g, t)
            (m, seats) = made.head
            _ <- w.services.engine.recordResults(
              g.gameId,
              m.matchId,
              seats.map((p, _) => ReportedResult(p.participantId, 1, Map.empty, false)),
              g.externalId
            )
            _ <- w.services.ending.settle(g.gameId, m.matchId)
            afterDraw <- w.services.tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
            _ <- deliver(w)
            rematches <- matchesOf(g, t).map(_.filter(_._1.fixture.exists(_.matchNo == 2)))
            (r, rseats) = rematches.head
            _ <- win(w, g, r, rseats, seeds(1))
            afterRematch <- w.services.tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
        } yield (afterDraw, r, afterRematch))
        assert(!afterDraw.rounds.head.completed)
        assert(rematch.noTie)
        assert(afterRematch.rounds.head.completed)
        // The rematch's winner, the second seed, takes the better seed.
        assertEquals(afterRematch.entrants.head.participant.map(_.seed), Some(1))
    }

    test("the owner cancels a tournament match and ranks it; it then scores as reported ranks would") {
        val w = world()
        val (results, completed, notCancelled, seeds) = run(for {
            g <- game()
            s <- started(w, 2, g)
            (owner, t, seeds) = s
            _ <- w.services.tournamentPlay.startRound(g.gameId, t.tournamentId, 1, RoundOverrides(), owner.externalId)
            _ <- deliver(w)
            made <- matchesOf(g, t)
            (m, seats) = made.head
            notCancelled <- w.services.matches
                .setRanks(g.gameId, m.matchId, seats.map(_._1.participantId -> 1).toMap, owner.externalId)
                .attempt
            loser = seats.find(_._2 == seeds.head.externalId).get._1.participantId
            ranks = seats.map((p, _) => p.participantId -> (if (p.participantId == loser) 2 else 1)).toMap
            _ <- w.services.matches.cancel(g.gameId, m.matchId, owner.externalId, Some(ranks))
            results <- TestSession.resource.use(session =>
                seats.traverse((p, _) => new ResultRepo(session).read(g.gameId, p.participantId))
            )
            _ <- w.services.ending.settle(g.gameId, m.matchId)
            detail <- w.services.tournaments.detail(g.gameId, t.tournamentId, owner.externalId)
            late <- w.services.matches.setRanks(g.gameId, m.matchId, ranks, owner.externalId).attempt
            _ <- IO(assert(late.left.exists(_.isInstanceOf[ConflictError]), late))
        } yield (results.flatten, detail, notCancelled, seeds))
        assert(notCancelled.left.exists(_.isInstanceOf[ConflictError]), notCancelled)
        assertEquals(
          results.map(r => (r.rank, r.isWinner, r.eloDelta)).sortBy(_._1),
          List((1, true, None), (2, false, None))
        )
        assert(completed.rounds.head.completed)
        // The player ranked first by hand takes the top seed from the one ranked second.
        assertEquals(completed.entrants.head.player.playerId, seeds(1).playerId)
        assertEquals(completed.entrants.head.participant.map(_.seed), Some(1))
    }
}
