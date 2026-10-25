package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import munit.FunSuite
import skunk.Session
import skunk.implicits._
import skunk.codec.all.{int4, int8, text}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{Tournament as Progress, *}
import com.vivi.matchmaker.service.TournamentFixtures

/** A tournament's progress through its rows (`design/new-tournament-plan.md` Phase 5): whole tournaments of the
  * standard formats played through the database, every state saved, read back and compared with what the rules made,
  * and every result written as a played match's would be.
  */
class TournamentStateRepoSpec extends FunSuite with QuietTests {

    private def run[A](body: Session[IO] => IO[A]): A =
        TestSession.resource
            .use(session => session.transaction.use(_ => body(session)))
            .timeout(60.seconds)
            .unsafeRunSync()

    // ---- what is compared: a state without the numbers the database and the rules give things differently ----

    private case class NMatch(
        seats: List[Long],
        isRubber: Boolean,
        canTie: Boolean,
        round: Option[Int],
        outcome: Option[List[Placing]]
    )
    private case class NPool(
        stratum: Int,
        members: List[PoolMember],
        matches: List[NMatch],
        sub: Option[NNode],
        isCompleted: Boolean
    )
    private case class NRun(
        stage: Int,
        repetition: Int,
        pools: List[NPool],
        sittingOut: List[Long],
        isCompleted: Boolean
    )
    private case class NNode(
        path: List[Int],
        members: List[Member],
        stage: Int,
        repetition: Int,
        runs: List[NRun],
        ranking: Option[Map[Long, Int]]
    )

    private def normal(n: Node): NNode =
        NNode(
          path = n.path,
          members = n.members.sortBy(_.entrant),
          stage = if (n.isFinished) -1 else n.stage,
          repetition = if (n.isFinished) -1 else n.repetition,
          runs = n.runs.map(r =>
              NRun(
                stage = r.stage,
                repetition = r.repetition,
                pools = r.pools.map(p =>
                    NPool(
                      stratum = p.stratum,
                      members = p.members.sortBy(_.entrant),
                      matches = p.matches
                          .map(m =>
                              NMatch(
                                seats = m.planned.seats.map(_.entrant).sorted,
                                isRubber = m.planned.isRubber,
                                canTie = m.canTie,
                                round = m.round,
                                outcome = m.outcome.map(_.sortBy(_.entrant))
                              )
                          )
                          .sortBy(m => (m.round.getOrElse(Int.MaxValue), m.seats.mkString(","), m.isRubber)),
                      sub = p.sub.map(normal),
                      isCompleted = p.isCompleted
                    )
                ),
                sittingOut = r.sittingOut.sorted,
                isCompleted = r.isCompleted
              )
          ),
          ranking = n.ranking
        )

    // ---- playing through the database ----

    private case class Setup(game: Game, t: Tournament, env: Env, entrants: List[Long])

    // Outside a transaction of its own: the tournament is made in one by TournamentFixtures, and the rest are single
    // statements.
    private def setup(key: String, n: Int, poolSize: Option[Int]): Setup = TestSession.resource
        .use { session =>
            for {
                game <- new GameRepo[String](session).create(Generators.genGame(GameType.Plain).sample.get)
                owner <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
                made <- TournamentFixtures.tournament(session = session, game = game, owner = owner.playerId, key = key)
                t = poolSize.fold(made)(p => made.copy(defaultPoolSize = Some(p)))
                _ <- new TournamentRepo(session).update(
                  t.copy(status = TournamentStatus.Running, startedAt = Some(java.time.Instant.now()))
                )
                entrants <- (1 to n).toList.traverse { _ =>
                    new PlayerRepo(session)
                        .create(Generators.genPlayer.sample.get)
                        .flatMap(p =>
                            new EntrantRepo(session)
                                .create(
                                  game = game.gameId,
                                  gt = GameType.Plain,
                                  tournament = t.tournamentId,
                                  player = p.playerId,
                                  character = None,
                                  rating = 1500
                                )
                        )
                }
                design <- new StageRepo(session).design(game.gameId, t.tournamentId).map(_.get)
            } yield Setup(
              game = game,
              t = t,
              env = Env(
                design = design,
                game = TournamentGame.of(game),
                ratings = entrants.map(_.entrantId.value -> 1500).toMap,
                defaultPoolSize = t.defaultPoolSize
              ),
              entrants = entrants.map(_.entrantId.value)
            )
        }
        .timeout(60.seconds)
        .unsafeRunSync()

    /** Saves `next` and reads it back. */
    private def saved(s: Setup, next: TournamentState): TournamentStateRepo.Loaded = run { session =>
        val repo = new TournamentStateRepo(session)
        repo.write(game = s.game.gameId, tournament = s.t.tournamentId, after = next) *> repo
            .load(s.game.gameId, s.t.tournamentId)
            .map(_.get)
    }

    /** Saves the rules' next state as the service does, and reads it back whole: worked out from the state without its
      * completed runs, or again from the whole state where that is needed. Whether the open load, with runs left out,
      * is what it was saved against.
      */
    private def savedOpen(
        s: Setup,
        next: TournamentState => TournamentState
    ): (TournamentStateRepo.Loaded, Boolean) = run { session =>
        val repo = new TournamentStateRepo(session)
        for {
            open <- repo.loadOpen(s.game.gameId, s.t.tournamentId).map(_.get)
            isWhole = TournamentStateRepo.isWholeLoadNeeded(open, next(open.state))
            before <- if (isWhole) repo.load(s.game.gameId, s.t.tournamentId).map(_.get) else IO.pure(open)
            _ <- repo.save(
              game = s.game.gameId,
              tournament = s.t.tournamentId,
              before = before,
              after = next(before.state)
            )
            read <- repo.load(s.game.gameId, s.t.tournamentId).map(_.get)
        } yield (read, !isWhole && open.omittedRunsIn.nonEmpty)
    }

    /** Plays a match of the round being played as an engine reporting it would leave it: over, each seat with a result,
      * the strongest seed -- the lowest entrant -- first.
      */
    private def play(s: Setup, matchId: MatchId): Unit = run { session =>
        for {
            seats <- session.execute(
              sql"SELECT participant_id, entrant_id FROM participant WHERE game_id = $int4 AND match_id = $int8 ORDER BY entrant_id"
                  .query(int8 *: int8)
            )((s.game.gameId.value, matchId.value))
            _ <- seats.zipWithIndex.traverse_ { case ((participant, _), i) =>
                session.execute(
                  sql"""INSERT INTO result (game_id, participant_id, rank, scores, is_winner, is_forfeit)
                        VALUES ($int4, $int8, $int4, '{}', ${skunk.codec.all.bool}, false)""".command
                )((s.game.gameId.value, participant, i + 1, i == 0))
            }
            _ <- session.execute(
              sql"""UPDATE match SET status = 'F', completed_at = now(), start = now(),
                       status_url = 'https://engine.test/s', play_url = 'https://engine.test/p'
                    WHERE game_id = $int4 AND match_id = $int8""".command
            )((s.game.gameId.value, matchId.value))
        } yield ()
    }

    /** Plays a whole tournament, checking every state the database holds against the one the rules made: each worked
      * out from the state without its completed runs, as a round's end and start are. One that runs until stopped is
      * stopped after `stopAfter` steps.
      */
    private def playThrough(
        key: String,
        n: Int,
        poolSize: Option[Int] = None,
        stopAfter: Option[Int] = None
    ): TournamentState = {
        val s = setup(key = key, n = n, poolSize = poolSize)
        def check(made: TournamentState, read: TournamentStateRepo.Loaded): TournamentState = {
            assertEquals(normal(read.state.root), normal(made.root), s"$key, round ${made.round}")
            assertEquals(read.state.round, made.round)
            agrees()
            read.state
        }
        // Whether the round is over, asked of its rows, is what the rules say of the state the rows hold.
        def agrees(): Unit = run { session =>
            val repo = new TournamentStateRepo(session)
            (
              repo.load(s.game.gameId, s.t.tournamentId).map(_.get),
              repo.isRoundComplete(s.game.gameId, s.t.tournamentId)
            )
                .mapN((loaded, isComplete) =>
                    assertEquals(
                      isComplete,
                      Progress.isRoundComplete(loaded.state),
                      s"$key, round ${loaded.state.round}"
                    )
                )
        }
        var state = {
            val started =
                Progress.start(entrants = s.entrants, env = s.env, firstPoolId = TournamentStateRepo.NewPoolIds)
            check(started, saved(s, started))
        }
        // How many steps were saved against a state with runs left out: none would leave the open load untested.
        var leftOut = 0
        def step(made: TournamentState, next: TournamentState => TournamentState): TournamentState = {
            val (read, isOpen) = savedOpen(s, next)
            if (isOpen) leftOut += 1
            check(made, read)
        }
        var steps = 0
        while (!state.isFinished && steps < 200) {
            steps += 1
            if (stopAfter.contains(steps)) {
                val stopped = Progress.requestStop(state)
                state = check(stopped, saved(s, stopped))
            } else if (Progress.isRoundComplete(state)) {
                val made = state
                state = step(Progress.nextRound(made, s.env)._1, Progress.nextRound(_, s.env)._1)
            } else {
                val loaded =
                    run(session => new TournamentStateRepo(session).load(s.game.gameId, s.t.tournamentId).map(_.get))
                Progress
                    .roundMatches(state)
                    .filterNot(m => Progress.isRecorded(state = state, poolId = m.poolId, matchNo = m.planned.no))
                    .foreach { m =>
                        agrees()
                        play(s, loaded.matchIds((m.poolId, m.planned.no)))
                    }
                agrees()
                // Read back with its results, which is how a result is recorded now; then advanced past them.
                val played =
                    run(session => new TournamentStateRepo(session).load(s.game.gameId, s.t.tournamentId).map(_.get))
                state = step(Progress.advance(played.state, s.env), Progress.advance(_, s.env))
            }
        }
        assert(state.isFinished, s"$key did not finish in 200 steps")
        assert(leftOut > 0, s"$key never saved a state loaded without its completed runs")
        state
    }

    test("a single elimination is played through its rows, every state read back as the rules made it") {
        val end = playThrough(key = "single-elimination", n = 8)
        assertEquals(end.root.ranking.map(_.values.min), Some(1))
    }

    test("a single elimination with byes, and one with a consolation match, are read back as they were made") {
        playThrough(key = "single-elimination", n = 5)
        playThrough(key = "single-elimination-consolation", n = 6)
    }

    test("double elimination, whose losers drop a stratum, is read back as it was made") {
        playThrough(key = "double-elimination", n = 6)
    }

    test("group round robins, then a knockout, are read back as they were made") {
        playThrough(key = "world-cup", n = 8)
    }

    test("heats take the tournament's pool size, and are read back as they were made") {
        playThrough(key = "heats", n = 9, poolSize = Some(3))
    }

    test("a ladder, whose runs pile up until it is stopped, is read back as it was made, and ends when stopped") {
        val end = playThrough(key = "ladder", n = 6, stopAfter = Some(12))
        assertEquals(end.root.ranking.map(_.size), Some(6))
    }

    test("a finished tournament's ranking is read back from its entrants' final ranks") {
        // `playThrough` compares the ranking read back, which is entrant.final_rank, with the rules' own.
        val end = playThrough(key = "single-elimination", n = 4)
        assertEquals(end.root.ranking.map(_.size), Some(4))
    }
}
