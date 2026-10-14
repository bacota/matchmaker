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
        isTiesAllowed: Boolean,
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
          n.path,
          n.members.sortBy(_.entrant),
          if (n.finished) -1 else n.stage,
          if (n.finished) -1 else n.repetition,
          n.runs.map(r =>
              NRun(
                r.stage,
                r.repetition,
                r.pools.map(p =>
                    NPool(
                      p.stratum,
                      p.members.sortBy(_.entrant),
                      p.matches
                          .map(m =>
                              NMatch(
                                m.planned.seats.map(_.entrant).sorted,
                                m.planned.isRubber,
                                m.isTiesAllowed,
                                m.round,
                                m.outcome.map(_.sortBy(_.entrant))
                              )
                          )
                          .sortBy(m => (m.round.getOrElse(Int.MaxValue), m.seats.mkString(","), m.isRubber)),
                      p.sub.map(normal),
                      p.isCompleted
                    )
                ),
                r.sittingOut.sorted,
                r.isCompleted
              )
          ),
          n.ranking
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
                made <- TournamentFixtures.tournament(session, game, owner.playerId, key)
                t = poolSize.fold(made)(p => made.copy(defaultPoolSize = p))
                _ <- new TournamentRepo(session).update(
                  t.copy(status = TournamentStatus.Running, startedAt = Some(java.time.Instant.now()))
                )
                entrants <- (1 to n).toList.traverse { _ =>
                    new PlayerRepo(session)
                        .create(Generators.genPlayer.sample.get)
                        .flatMap(p =>
                            new EntrantRepo(session)
                                .create(game.gameId, GameType.Plain, t.tournamentId, p.playerId, None, 1500)
                        )
                }
                design <- new StageRepo(session).design(game.gameId, t.tournamentId).map(_.get)
            } yield Setup(
              game,
              t,
              Env(
                design,
                TournamentGame.of(game),
                entrants.map(_.entrantId.value -> 1500).toMap,
                Some(t.defaultPoolSize)
              ),
              entrants.map(_.entrantId.value)
            )
        }
        .timeout(60.seconds)
        .unsafeRunSync()

    /** Saves `next` and reads it back. */
    private def saved(s: Setup, next: TournamentState): TournamentStateRepo.Loaded = run { session =>
        val repo = new TournamentStateRepo(session)
        repo.write(s.game.gameId, s.t.tournamentId, next) *> repo.load(s.game.gameId, s.t.tournamentId).map(_.get)
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

    /** Plays a whole tournament, checking every state the database holds against the one the rules made. */
    private def playThrough(key: String, n: Int, poolSize: Option[Int] = None): TournamentState = {
        val s = setup(key, n, poolSize)
        def check(made: TournamentState, read: TournamentStateRepo.Loaded): TournamentState = {
            assertEquals(normal(read.state.root), normal(made.root), s"$key, round ${made.round}")
            assertEquals(read.state.round, made.round)
            read.state
        }
        var state = {
            val started = Progress.start(s.entrants, s.env, TournamentStateRepo.NewPoolIds)
            check(started, saved(s, started))
        }
        var steps = 0
        while (!state.finished && steps < 200) {
            steps += 1
            if (Progress.roundComplete(state)) {
                val (next, _) = Progress.nextRound(state, s.env)
                state = check(next, saved(s, next))
            } else {
                val loaded =
                    run(session => new TournamentStateRepo(session).load(s.game.gameId, s.t.tournamentId).map(_.get))
                Progress
                    .roundMatches(state)
                    .filterNot(m => Progress.isRecorded(state, m.poolId, m.planned.no))
                    .foreach { m =>
                        play(s, loaded.matchIds((m.poolId, m.planned.no)))
                    }
                // Read back with its results, which is how a result is recorded now; then advanced past them.
                val played =
                    run(session => new TournamentStateRepo(session).load(s.game.gameId, s.t.tournamentId).map(_.get))
                val next = Progress.advance(played.state, s.env)
                state = check(next, saved(s, next))
            }
        }
        assert(state.finished, s"$key did not finish in 200 steps")
        state
    }

    test("a single elimination is played through its rows, every state read back as the rules made it") {
        val end = playThrough("single-elimination", 8)
        assertEquals(end.root.ranking.map(_.values.min), Some(1))
    }

    test("a single elimination with byes, and one with a consolation match, are read back as they were made") {
        playThrough("single-elimination", 5)
        playThrough("single-elimination-consolation", 6)
    }

    test("double elimination, whose losers drop a stratum, is read back as it was made") {
        playThrough("double-elimination", 6)
    }

    test("group round robins, then a knockout, are read back as they were made") {
        playThrough("world-cup", 8)
    }

    test("heats take the tournament's pool size, and are read back as they were made") {
        playThrough("heats", 9, poolSize = Some(3))
    }

    test("a finished tournament's ranking is read back from its entrants' final ranks") {
        // `playThrough` compares the ranking read back, which is entrant.final_rank, with the rules' own.
        val end = playThrough("single-elimination", 4)
        assertEquals(end.root.ranking.map(_.size), Some(4))
    }
}
