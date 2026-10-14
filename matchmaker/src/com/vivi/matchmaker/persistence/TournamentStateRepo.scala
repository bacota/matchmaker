package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import java.time.Instant
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament._

/** A tournament's progress as the rules hold it -- a [[TournamentState]] -- read from its rows and written back to them
  * (`design/new-tournament-plan.md` Phase 5).
  *
  * The rules stay pure: [[load]] assembles the state, the rules work out the next one, and [[save]] writes what changed
  * between the two. How the state maps onto the tables:
  *
  *   - a node -- the tournament, or a sub-tournament played in a pool -- is a pool that scopes seeds: the root pool, or
  *     a group (`pool_group`) of a nesting stage. Its members are the entrants with SEED rows in that pool, each as
  *     their latest row says, with their first row's seed as the seed they started it with;
  *   - a node's stage runs are its child pools, grouped by stage and by the round they began in. The node's stage and
  *     repetition are those of its open run, which every unfinished node has between calls;
  *   - a pool is a `pool` row: an `atomic_pool` playing matches, or a group playing a sub-tournament. Its members are
  *     `pool_member` rows, the seed and stratum they came in with read from SEED as of the round it began;
  *   - a match is a `match` row of an atomic pool, its seats `participant` rows in seat order. A match is in the round
  *     its row says once it has been given one, and has an outcome once it is over with results;
  *   - who has withdrawn is `entrant.withdrawn_at`; the tournament's final ranking is `entrant.final_rank`, a group's
  *     its `pool_member.final_rank`.
  *
  * A match's number within its pool is not stored: it is its place among the pool's matches, by round and seats, which
  * is the same however often the state is read, and a match is otherwise found by its id. New pools are numbered by the
  * rules from [[NewPoolIds]] down, and given their real ids as they are written.
  *
  * Opens no transaction of its own; the caller holds the tournament's lock for the whole of a load and save.
  */
class TournamentStateRepo(session: Session[IO]) {
    import TournamentStateRepo._

    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId

    // ---- reading ----------------------------------------------------------------------------

    private type ProgressRow = (Option[Int], Boolean, Boolean, Boolean, String)
    private val selectProgress: Query[(GameId, TournamentId), ProgressRow] =
        sql"""SELECT t.current_round, t.stop_requested, t.friendly, t.public, g.game_type::text
          FROM tournament t JOIN game g ON g.game_id = t.game_id
          WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId"""
            .query(int4.opt *: bool *: bool *: bool *: text)

    private val selectPools: Query[(GameId, TournamentId), PoolRow] =
        sql"""SELECT p.pool_id, p.kind::text, p.parent_pool_id, p.round, p.stratum, p.completed_at IS NOT NULL,
                 COALESCE(a.stage_id, g.stage_id)
          FROM pool p
          LEFT JOIN atomic_pool a USING (game_id, tournament_id, pool_id)
          LEFT JOIN pool_group g USING (game_id, tournament_id, pool_id)
          WHERE p.game_id = $gameId AND p.tournament_id = $tournamentId
          ORDER BY p.pool_id"""
            .query(int8 *: text *: int8.opt *: int4.opt *: int4 *: bool *: int8.opt)
            .to[PoolRow]

    private val selectMembers: Query[(GameId, TournamentId), MemberRow] =
        sql"""SELECT pool_id, entrant_id, final_rank, total_score, score_against, pool_points FROM pool_member
          WHERE game_id = $gameId AND tournament_id = $tournamentId"""
            .query(int8 *: int8 *: int4.opt *: float8.opt *: float8.opt *: float8.opt)
            .to[MemberRow]

    private val selectSeeds: Query[(GameId, TournamentId), SeedRow] =
        sql"""SELECT pool_id, entrant_id, round, seed, stratum, eliminated FROM seed
          WHERE game_id = $gameId AND tournament_id = $tournamentId
          ORDER BY round"""
            .query(int8 *: int8 *: int4 *: int4 *: int4 *: int4.opt)
            .to[SeedRow]

    private val selectMatches: Query[(GameId, TournamentId), MatchRow] =
        sql"""SELECT match_id, pool_id, round, status, ties_allowed, rubber, cancelled FROM match
          WHERE game_id = $gameId AND tournament_id = $tournamentId"""
            .query(int8 *: int8 *: int4 *: text *: bool *: bool *: bool)
            .to[MatchRow]

    private val selectSeats: Query[(GameId, TournamentId), SeatRow] =
        sql"""SELECT p.match_id, p.participant_id, p.entrant_id, p.game_role_id, p.choose_order, r.rank, r.scores::text
          FROM participant p
          LEFT JOIN result r ON r.game_id = p.game_id AND r.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.tournament_id = $tournamentId
          ORDER BY p.participant_id"""
            .query(int8 *: int8 *: int8 *: int4.opt *: int4.opt *: int4.opt *: text.opt)
            .to[SeatRow]

    // Each entry with who plays it now -- its player, or its character's owner, if anybody -- and its character.
    private val selectEntrants
        : Query[(GameId, TournamentId), (Long, Option[Long], Boolean, Option[Int], Option[Long])] =
        sql"""SELECT e.entrant_id, ep.player_id, e.withdrawn_at IS NOT NULL, e.final_rank, ce.character_id
          FROM entrant e
          LEFT JOIN entrant_player ep USING (game_id, tournament_id, entrant_id)
          LEFT JOIN character_entrant ce USING (game_id, tournament_id, entrant_id)
          WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId"""
            .query(int8 *: int8.opt *: bool *: int4.opt *: int8.opt)

    /** The tournament's state as its rows hold it, with what [[save]] needs to know about them. `None` for a tournament
      * that is not there.
      */
    def load(game: GameId, tournament: TournamentId): IO[Option[Loaded]] =
        session.option(selectProgress)((game, tournament)).flatMap {
            case None => IO.pure(None)
            case Some((currentRound, stopRequested, friendly, isPublic, gameType)) =>
                for {
                    designed <- new StageRepo(session).designWithIds(game, tournament)
                    (design, stageIds) = designed.getOrElse((TournamentDesign(Nil), Map.empty[List[Int], Long]))
                    pools <- session.execute(selectPools)((game, tournament))
                    members <- session.execute(selectMembers)((game, tournament))
                    seeds <- session.execute(selectSeeds)((game, tournament))
                    matches <- session.execute(selectMatches)((game, tournament))
                    seats <- session.execute(selectSeats)((game, tournament))
                    entrants <- session.execute(selectEntrants)((game, tournament))
                } yield {
                    val built = Assembly(design, stageIds, pools, members, seeds, matches, seats, entrants)
                    built.root.map { (root, matchIds) =>
                        val roundOf = matches.map(m => m.id -> m).toMap
                        Loaded(
                          TournamentState(
                            root,
                            currentRound.getOrElse(0),
                            NewPoolIds,
                            stopRequested,
                            entrants.collect { case (e, _, true, _, _) => e }.toSet
                          ),
                          matchIds,
                          design,
                          stageIds,
                          pools.find(_.kind == "R").map(_.id).getOrElse(0L),
                          Context(friendly, isPublic, gameType, entrants.map(e => e._1 -> (e._2, e._5)).toMap),
                          matchIds.flatMap((key, id) =>
                              roundOf.get(id.value).filter(_.status != "S").map(m => key -> m.round)
                          ),
                          seeds
                              .groupBy(r => (r.pool, r.entrant))
                              .flatMap((key, rows) => rows.last.eliminated.map(key -> _))
                        )
                    }
                }
        }

    // ---- writing ----------------------------------------------------------------------------

    private val insertRound: Command[(GameId, TournamentId, Int, Long)] =
        sql"""INSERT INTO round (game_id, tournament_id, round, stage_id) VALUES ($gameId, $tournamentId, $int4, $int8)
          ON CONFLICT (game_id, tournament_id, round) DO NOTHING""".command

    private val insertPool: Query[(GameId, TournamentId, String, Long, Int, Int), Long] =
        sql"""INSERT INTO pool (game_id, tournament_id, kind, parent_pool_id, round, stratum)
          VALUES ($gameId, $tournamentId, $text, $int8, $int4, $int4)
          RETURNING pool_id""".query(int8)

    private val insertAtomicPool: Command[(GameId, TournamentId, Long, Long)] =
        sql"""INSERT INTO atomic_pool (game_id, tournament_id, pool_id, stage_id)
          VALUES ($gameId, $tournamentId, $int8, $int8)""".command

    private val insertGroup: Command[(GameId, TournamentId, Long, Long)] =
        sql"""INSERT INTO pool_group (game_id, tournament_id, pool_id, stage_id)
          VALUES ($gameId, $tournamentId, $int8, $int8)""".command

    private val insertMember: Command[(GameId, TournamentId, Long, Long)] =
        sql"""INSERT INTO pool_member (game_id, tournament_id, pool_id, entrant_id)
          VALUES ($gameId, $tournamentId, $int8, $int8)""".command

    private val updateMember: Command[
      (Option[Int], Option[Double], Option[Double], Option[Double], GameId, TournamentId, Long, Long)
    ] =
        sql"""UPDATE pool_member SET final_rank = ${int4.opt}, total_score = ${float8.opt},
                 score_against = ${float8.opt}, pool_points = ${float8.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int8 AND entrant_id = $int8""".command

    private val completePool: Command[(GameId, TournamentId, Long)] =
        sql"""UPDATE pool SET completed_at = now()
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int8 AND completed_at IS NULL""".command

    private val insertSeed: Command[(GameId, TournamentId, Long, Long, Int, Int, Int, Option[Int])] =
        sql"""INSERT INTO seed (game_id, tournament_id, pool_id, entrant_id, round, seed, stratum, eliminated)
          VALUES ($gameId, $tournamentId, $int8, $int8, $int4, $int4, $int4, ${int4.opt})""".command

    private val insertMatch: Command[
      (GameId, Long, Boolean, Boolean, TournamentId, Long, Int, Long, Boolean, Boolean)
    ] =
        sql"""INSERT INTO match (game_id, match_id, description, settings, public, friendly, status,
                                 tournament_id, pool_id, round, stage_id, ties_allowed, rubber)
          VALUES ($gameId, $int8, '', '{}', $bool, $bool, 'S',
                  $tournamentId, $int8, $int4, $int8, $bool, $bool)""".command

    private val beginMatch: Command[(String, Int, GameId, Long)] =
        sql"""UPDATE match SET status = $text, round = $int4
          WHERE game_id = $gameId AND match_id = $int8 AND status = 'S'""".command

    // Stamped with what its player wants to hear about it -- or everything, for a seat nobody plays, whose character
    // has no owner: it forfeits before play and is told nothing.
    private val insertSeat: Query[
      (GameId, Long, String, Option[Long], Option[Int], TournamentId, Long, Option[Int], GameId, Option[Long]),
      Long
    ] =
        sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, pending, due, game_role_id,
              tournament_id, entrant_id, choose_order, notify_match_started, notify_turn_taken, notify_your_turn,
              notify_match_ended)
          SELECT $gameId, $int8, $text, ${int8.opt}, false, NULL, ${int4.opt}, $tournamentId, $int8, ${int4.opt},
                 COALESCE(pg.notify_match_started, pl.notify_match_started, TRUE),
                 COALESCE(pg.notify_turn_taken, pl.notify_turn_taken, TRUE),
                 COALESCE(pg.notify_your_turn, pl.notify_your_turn, TRUE),
                 COALESCE(pg.notify_match_ended, pl.notify_match_ended, TRUE)
          FROM (SELECT 1) one
              LEFT JOIN player pl ON pl.player_id = ${int8.opt}
              LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = $gameId
          RETURNING participant_id"""
            .query(int8)
            .contramap { case (game, matchId, gt, player, role, tournament, entrant, order, game2, player2) =>
                (game, matchId, gt, player, role, tournament, entrant, order, player2, game2)
            }

    private val insertPlayerSeat: Command[(GameId, Long, TournamentId, Long)] =
        sql"""INSERT INTO player_participant (game_id, participant_id, tournament_id, entrant_id)
          VALUES ($gameId, $int8, $tournamentId, $int8)""".command

    private val insertCharacterSeat: Command[(GameId, Long, Long, TournamentId, Long)] =
        sql"""INSERT INTO character_participant (game_id, participant_id, character_id, tournament_id, entrant_id)
          VALUES ($gameId, $int8, $int8, $tournamentId, $int8)""".command

    private val updateProgress: Command[(Option[Int], Boolean, GameId, TournamentId)] =
        sql"""UPDATE tournament SET current_round = ${int4.opt}, stop_requested = $bool
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    private val setFinalRank: Command[(Int, GameId, TournamentId, Long)] =
        sql"""UPDATE entrant SET final_rank = $int4
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND entrant_id = $int8""".command

    /** Writes what changed from `before` -- as [[load]] read it -- to `after`, the rules' next state: new pools with
      * their members and matches and seats, results and completions of pools, members' new seeds and strata, the round
      * being played, and a finished tournament's ranking. The new pools' real ids are what is returned, by the number
      * the rules gave each.
      */
    def save(game: GameId, tournament: TournamentId, before: Loaded, after: TournamentState): IO[Map[Int, Long]] = {
        val ctx = before.context
        // Whatever this call changes takes effect from the round after the one being played when it began: the
        // first matches of a pool formed now are played then, and the seeds a finished run leaves are its seeds from
        // then.
        val effective = before.state.round + 1
        val walked = Walk(after, before.design, before.rootPool)
        val beforeWalk = Walk(before.state, before.design, before.rootPool)
        val beforePools = beforeWalk.pools.map(p => p.pool.id -> p).toMap

        def stageOf(w: WalkedPool): Long =
            before.stageIds.getOrElse(w.stageKey, throw new IllegalStateException(s"no stage at ${w.stageKey}"))

        // The stage a round is played in: the atomic stage of a pool playing in it, new or still open.
        val roundStage: Option[Long] =
            walked.pools
                .filter(w => w.pool.sub.isEmpty && w.pool.matches.nonEmpty && !w.pool.completed)
                .sortBy(w => if (w.pool.id < 0) 0 else 1)
                .headOption
                .map(stageOf)

        // Failing those, the stage of the last atomic pool there is: a round with nothing to play in it, the one after
        // a tournament finishes, still has to name one.
        val lastStage: Option[Long] = walked.pools.filterNot(_.nesting).lastOption.map(stageOf)

        def ensureRound(r: Int, stage: Option[Long]): IO[Unit] =
            stage
                .orElse(roundStage)
                .orElse(lastStage)
                .traverse_(s => session.execute(insertRound)((game, tournament, r, s)))

        for {
            // ---- new pools, parents first ----
            ids <- walked.pools.filter(_.pool.id < 0).foldLeftM(Map.empty[Int, Long]) { (ids, w) =>
                val parent = if (w.scope < 0) ids(w.scope.toInt) else w.scope
                val kind = if (w.nesting) "G" else "A"
                val stage = stageOf(w)
                for {
                    _ <- ensureRound(effective, if (w.nesting) None else Some(stage))
                    id <- session.unique(insertPool)((game, tournament, kind, parent, effective, w.pool.stratum))
                    _ <- session
                        .execute(if (w.nesting) insertGroup else insertAtomicPool)((game, tournament, id, stage))
                    _ <- w.pool.members.traverse_(m => session.execute(insertMember)((game, tournament, id, m.entrant)))
                } yield ids + (w.pool.id -> id)
            }
            realId = (id: Int) => if (id < 0) ids(id) else id.toLong

            // ---- pools' results and completion ----
            _ <- walked.pools.traverse_ { w =>
                val was = beforePools.get(w.pool.id).map(_.pool)
                val id = realId(w.pool.id)
                val members = w.pool.members.filter(m => !was.exists(_.members.contains(m)))
                members.traverse_(m =>
                    session.execute(updateMember)(
                      (
                        m.rank,
                        m.totalScore,
                        m.totalScore.zip(m.scoreDiff).map(_ - _),
                        m.points,
                        game,
                        tournament,
                        id,
                        m.entrant
                      )
                    )
                ) *> IO.whenA(w.pool.completed && !was.exists(_.completed))(
                  session.execute(completePool)((game, tournament, id)).void
                )
            }

            // ---- seeds and strata, per node ----
            // From the next round, as everything else. A tournament that has just finished has no next round to play,
            // so that round's row is written for its last seeds alone, and is never begun.
            seedRound = effective
            beforeNodes = beforeWalk.nodes.map(n => n.scope -> n.node).toMap
            _ <- walked.nodes.traverse_ { n =>
                val scope = if (n.scope < 0) ids(n.scope.toInt) else n.scope
                val was = beforeNodes.get(n.scope).map(_.members.map(m => m.entrant -> m).toMap).getOrElse(Map.empty)
                val changed = n.node.members.filterNot(m => was.get(m.entrant).contains(m))
                val eliminatedAt = (m: Member) =>
                    Option.when(m.eliminated)(
                      before.eliminatedRound.getOrElse((scope, m.entrant), seedRound)
                    )
                IO.whenA(changed.nonEmpty)(
                  ensureRound(effective, None) *>
                      // Its row for this round replaced, where an earlier call in the same round wrote one.
                      changed.traverse_(m =>
                          session.execute(deleteSeedOf)((game, tournament, scope, seedRound, m.entrant)) *>
                              session.execute(insertSeed)(
                                (game, tournament, scope, m.entrant, seedRound, m.seed, m.stratum, eliminatedAt(m))
                              )
                      )
                )
            }

            // ---- matches and their seats ----
            _ <- walked.pools.filter(w => w.pool.sub.isEmpty).traverse_ { w =>
                val poolId = realId(w.pool.id)
                val stage = stageOf(w)
                val waitingSlots = w.pool.matches.filter(_.round.isEmpty).map(_.planned.slot).distinct.sorted
                val lastRound =
                    (w.pool.matches.flatMap(_.round) ++
                        before.matchRounds.collect { case ((p, _), r) if p == w.pool.id => r }).maxOption
                        .getOrElse(effective - 1)
                w.pool.matches.traverse_ { m =>
                    before.matchIds.get((w.pool.id, m.planned.no)) match {
                        case Some(existing) =>
                            m.round.filter(_ => before.matchRounds.get((w.pool.id, m.planned.no)).isEmpty) match {
                                case Some(r) =>
                                    ensureRound(r, Some(stage)) *>
                                        session.execute(beginMatch)((statusAt(m), r, game, existing.value)).void
                                case None => IO.unit
                            }
                        case None =>
                            val planned = m.round.getOrElse(lastRound + 1 + waitingSlots.indexOf(m.planned.slot))
                            for {
                                id <- new MatchRepo(session).nextId.map(_.value)
                                _ <- ensureRound(planned, Some(stage))
                                _ <- session.execute(insertMatch)(
                                  (
                                    game,
                                    id,
                                    ctx.isPublic,
                                    ctx.friendly,
                                    tournament,
                                    poolId,
                                    planned,
                                    stage,
                                    m.tiesAllowed,
                                    m.planned.rubber
                                  )
                                )
                                _ <- m.planned.seats.traverse_(seat => writeSeat(game, tournament, ctx, id, seat))
                                _ <- m.round.traverse_(r => session.execute(beginMatch)((statusAt(m), r, game, id)))
                            } yield ()
                    }
                }
            }

            // ---- the round being played, and whether a stage is to stop ----
            _ <- IO.whenA(after.round > before.state.round)(ensureRound(after.round, None))
            _ <- session.execute(updateProgress)(
              (Option.when(after.round > 0)(after.round), after.stopRequested, game, tournament)
            )

            // ---- a finished tournament's ranking ----
            _ <- (after.root.ranking, before.state.root.ranking) match {
                case (Some(ranking), None) =>
                    ranking.toList.traverse_((e, rank) => session.execute(setFinalRank)((rank, game, tournament, e)))
                case _ => IO.unit
            }
        } yield ids
    }

    private val deleteSeedOf: Command[(GameId, TournamentId, Long, Int, Long)] =
        sql"""DELETE FROM seed WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int8
             AND round = $int4 AND entrant_id = $int8""".command

    /* A seat of a new match: who plays it now (its player, or its character's current owner), the role it was given or
     * none while it is to be chosen, and its turn to choose -- with its subtype, a player's or a character's seat. */
    private def writeSeat(
        game: GameId,
        tournament: TournamentId,
        ctx: Context,
        matchId: Long,
        seat: SeatPlan
    ): IO[Unit] = {
        val (player, character) = ctx.entrants.getOrElse(seat.entrant, (None, None))
        for {
            id <- session.unique(insertSeat)(
              (game, matchId, ctx.gameType, player, seat.role, tournament, seat.entrant, seat.chooseOrder, game, player)
            )
            _ <- character match {
                case Some(c) => session.execute(insertCharacterSeat)((game, id, c, tournament, seat.entrant))
                case None    => session.execute(insertPlayerSeat)((game, id, tournament, seat.entrant))
            }
        } yield ()
    }

    private val startRound: Command[(Instant, Option[Instant], GameId, TournamentId, Int)] =
        sql"""UPDATE round SET start_date = ${SkunkCodecs.instant}, deadline = ${SkunkCodecs.instant.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4""".command

    /** Round `round` begins at `at`, every match of it to be over by `deadline`. */
    def beginRound(
        game: GameId,
        tournament: TournamentId,
        round: Int,
        at: Instant,
        deadline: Option[Instant]
    ): IO[Unit] =
        session.execute(startRound)((at, deadline, game, tournament, round)).void

    private val completeRound: Query[(GameId, TournamentId, Int), Int] =
        sql"""UPDATE round SET complete = true
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4 AND NOT complete
          RETURNING round""".query(int4)

    /** Marks round `round` complete, answering whether this is what did: so that an ending delivered twice tells the
      * owner once that the round is over.
      */
    def markRoundComplete(game: GameId, tournament: TournamentId, round: Int): IO[Boolean] =
        session.option(completeRound)((game, tournament, round)).map(_.isDefined)

    /** [[load]] then [[save]]: what writing a state used to be, for a caller that has the next state and not the one it
      * was worked out from. In the same transaction as whatever read the state, under the tournament's lock.
      */
    def write(game: GameId, tournament: TournamentId, after: TournamentState): IO[Map[Int, Long]] =
        load(game, tournament).flatMap {
            case Some(before) => save(game, tournament, before, after)
            case None         => IO.raiseError(new IllegalStateException(s"no tournament $tournament in game $game"))
        }
}

object TournamentStateRepo {

    /** Where the rules number new pools from: below every real pool id, so that [[TournamentStateRepo.save]] knows a
      * new pool by its number.
      */
    val NewPoolIds: Int = -1_000_000_000

    /** What a save needs to know of the tournament beyond its state: how its matches are played, and each entrant's
      * player and character.
      */
    case class Context(
        friendly: Boolean,
        isPublic: Boolean,
        gameType: String,
        entrants: Map[Long, (Option[Long], Option[Long])]
    )

    /** A state as [[TournamentStateRepo.load]] read it: the state, each match's id by its pool and number, the design
      * and its stages' ids, the root pool, and the context a save needs.
      */
    case class Loaded(
        state: TournamentState,
        matchIds: Map[(Int, Int), MatchId],
        design: TournamentDesign,
        stageIds: Map[List[Int], Long],
        rootPool: Long,
        context: Context,
        matchRounds: Map[(Int, Int), Int] = Map.empty,
        eliminatedRound: Map[(Long, Long), Int] = Map.empty
    )

    case class PoolRow(
        id: Long,
        kind: String,
        parent: Option[Long],
        round: Option[Int],
        stratum: Int,
        completed: Boolean,
        stage: Option[Long]
    )
    case class MemberRow(
        pool: Long,
        entrant: Long,
        rank: Option[Int],
        totalScore: Option[Double],
        scoreAgainst: Option[Double],
        points: Option[Double]
    )
    case class SeedRow(pool: Long, entrant: Long, round: Int, seed: Int, stratum: Int, eliminated: Option[Int])
    case class MatchRow(
        id: Long,
        pool: Long,
        round: Int,
        status: String,
        tiesAllowed: Boolean,
        rubber: Boolean,
        cancelled: Boolean
    )
    case class SeatRow(
        matchId: Long,
        participant: Long,
        entrant: Long,
        role: Option[Int],
        chooseOrder: Option[Int],
        rank: Option[Int],
        scores: Option[String]
    )

    /** The status a match takes as its round begins: choosing while a seat has a role still to choose, else due. */
    def statusAt(m: PoolMatch): String = if (m.planned.seats.exists(_.role.isEmpty)) "C" else "D"

    /** A result's score: its `score`, where the engine reported one. */
    def scoreOf(scores: Option[String]): Option[Double] =
        scores.flatMap(s =>
            scala.util.Try(ujson.read(s).obj.get("score").collect { case ujson.Num(n) => n }).toOption.flatten
        )

    /** A pool of a state, with the node it is in: the pool that scopes that node's seeds (negative while new), the
      * stage it belongs to, keyed as the rules name it, and whether that stage is a nesting one.
      */
    case class WalkedPool(pool: Pool, scope: Long, stageKey: List[Int], nesting: Boolean)

    /** A node of a state, with the pool that scopes its seeds. */
    case class WalkedNode(node: Node, scope: Long)

    /** Every pool and node of a state, parents before children. */
    case class Walk(pools: List[WalkedPool], nodes: List[WalkedNode])

    object Walk {
        def apply(state: TournamentState, design: TournamentDesign, rootPool: Long): Walk = {
            def node(n: Node, scope: Long): (List[WalkedPool], List[WalkedNode]) = {
                val here = WalkedNode(n, scope)
                val stages = com.vivi.matchmaker.model.tournament.Tournament.designAt(design, n.path).stages
                val inRuns = n.runs.flatMap { run =>
                    run.pools.map { p =>
                        val key = n.path :+ run.stage
                        // A nesting stage's pool that could play nothing has no sub-tournament, but is a group still.
                        val nesting = p.sub.isDefined || stages.lift(run.stage).exists(_.design.isDefined)
                        val (pools, nodes) = p.sub.fold((Nil, Nil))(node(_, p.id.toLong))
                        (WalkedPool(p, scope, key, nesting) :: pools, nodes)
                    }
                }
                (inRuns.flatMap(_._1), here :: inRuns.flatMap(_._2))
            }
            val (pools, nodes) = node(state.root, rootPool)
            Walk(pools, nodes)
        }
    }

    /** The rows of a tournament, assembled into the rules' tree. */
    private case class Assembly(
        design: TournamentDesign,
        stageIds: Map[List[Int], Long],
        pools: List[PoolRow],
        members: List[MemberRow],
        seeds: List[SeedRow],
        matches: List[MatchRow],
        seats: List[SeatRow],
        entrants: List[(Long, Option[Long], Boolean, Option[Int], Option[Long])]
    ) {
        private val keyOf: Map[Long, List[Int]] = stageIds.map(_.swap)
        private val childrenOf: Map[Long, List[PoolRow]] = pools.groupBy(_.parent.getOrElse(-1L))
        private val membersOf: Map[Long, List[MemberRow]] = members.groupBy(_.pool)
        private val seedsOf: Map[(Long, Long), List[SeedRow]] = seeds.groupBy(s => (s.pool, s.entrant))
        private val seedsIn: Map[Long, List[SeedRow]] = seeds.groupBy(_.pool)
        private val matchesOf: Map[Long, List[MatchRow]] = matches.groupBy(_.pool)
        private val seatsOf: Map[Long, List[SeatRow]] = seats.groupBy(_.matchId)

        private def designAt(path: List[Int]): TournamentDesign =
            com.vivi.matchmaker.model.tournament.Tournament.designAt(design, path)

        /** The root node, and every match's id by its pool and number. */
        def root: Option[(Node, Map[(Int, Int), MatchId])] =
            pools.find(_.kind == "R").map { r =>
                val ranking = Option.when(entrants.nonEmpty && entrants.forall(_._4.isDefined))(
                  entrants.map(e => e._1 -> e._4.get).toMap
                )
                val (n, ids) = node(r.id, Nil, ranking)
                (n, ids)
            }

        private def latest(rows: List[SeedRow], upTo: Int): Option[SeedRow] = rows.filter(_.round <= upTo).lastOption

        private def node(
            scope: Long,
            path: List[Int],
            ranking: Option[Map[Long, Int]]
        ): (Node, Map[(Int, Int), MatchId]) = {
            val rows = seedsIn.getOrElse(scope, Nil).groupBy(_.entrant)
            val members = rows.toList
                .map { (e, rs) =>
                    val last = rs.last
                    Member(e, last.seed, last.stratum, rs.head.seed, last.eliminated.isDefined)
                }
                .sortBy(m => (m.initialSeed, m.entrant))
            val children = childrenOf.getOrElse(scope, Nil)
            val grouped: List[((Int, Int), List[PoolRow])] =
                children
                    .groupBy(p => (p.round.getOrElse(0), keyOf.get(p.stage.getOrElse(-1L)).map(_.last).getOrElse(0)))
                    .toList
                    .sortBy(_._1)
            val runRounds = grouped.map(_._1._1)
            val built = grouped.zipWithIndex.map { case (((round, stage), ps), i) =>
                val repetition = grouped.take(i).count(_._1._2 == stage)
                val nextRound = runRounds.lift(i + 1).getOrElse(Int.MaxValue)
                val completed = ps.forall(_.completed)
                val made = ps.sortBy(_.id).map(p => pool(p, scope, path :+ stage, completed, nextRound))
                val pooled = made.flatMap(_._1.members.map(_.entrant)).toSet
                val withdrawn = entrants.collect { case (e, _, true, _, _) => e }.toSet
                val sittingOut = members
                    .filter(m =>
                        rows(m.entrant).head.round <= round &&
                            rows(m.entrant).last.eliminated.forall(_ > round) && !pooled(m.entrant) &&
                            !withdrawn(m.entrant)
                    )
                    .map(_.entrant)
                (StageRun(stage, repetition, made.map(_._1), sittingOut, completed), made.flatMap(_._2).toMap)
            }
            val runs = built.map(_._1)
            val stagesHere = designAt(path).stages.size
            val (stage, repetition) = runs.lastOption match {
                case _ if ranking.isDefined        => (stagesHere, 0)
                case Some(open) if !open.completed => (open.stage, open.repetition)
                case Some(done)                    => (done.stage + 1, 0)
                case None                          => (0, 0)
            }
            (Node(path, members, stage, repetition, runs, ranking), built.flatMap(_._2).toMap)
        }

        private def pool(
            p: PoolRow,
            scope: Long,
            stageKey: List[Int],
            runCompleted: Boolean,
            nextRound: Int
        ): (Pool, Map[(Int, Int), MatchId]) = {
            val round = p.round.getOrElse(0)
            val ms = membersOf
                .getOrElse(p.id, Nil)
                .map { m =>
                    val rows = seedsOf.getOrElse((scope, m.entrant), Nil)
                    val in = latest(rows, round)
                    val out = rows.filter(r => r.round > round && r.round <= nextRound).lastOption
                    PoolMember(
                      m.entrant,
                      in.map(_.seed).getOrElse(0),
                      in.map(_.stratum).getOrElse(0),
                      m.rank,
                      m.points,
                      m.totalScore.zip(m.scoreAgainst).map(_ - _),
                      m.totalScore,
                      Option.when(runCompleted)(out.map(_.stratum).getOrElse(in.map(_.stratum).getOrElse(0))),
                      runCompleted && out.exists(_.eliminated.isDefined) && !in.exists(_.eliminated.isDefined)
                    )
                }
                .sortBy(m => (m.seedIn, m.entrant))
            if (p.kind == "G") {
                // A sub-tournament once one was seeded in it: a nesting stage's pool too small to play has none.
                if (!seedsIn.contains(p.id)) (Pool(p.id.toInt, p.stratum, ms, completed = p.completed), Map.empty)
                else {
                    val ranking = Option.when(p.completed)(ms.flatMap(m => m.rank.map(m.entrant -> _)).toMap)
                    val (sub, ids) = node(p.id, stageKey, ranking)
                    (Pool(p.id.toInt, p.stratum, ms, sub = Some(sub), completed = p.completed), ids)
                }
            } else {
                val rows = matchesOf.getOrElse(p.id, Nil).map { m =>
                    val seats = seatsOf.getOrElse(m.id, Nil)
                    (m, seats)
                }
                // By round and seats, and then by the seats' own ids, which a choice of role does not change.
                val ordered = rows.sortBy { (m, seats) =>
                    (m.round, seats.map(_.entrant).mkString(","), seats.headOption.map(_.participant).getOrElse(0L))
                }
                val numbered = ordered.zipWithIndex.map { case ((m, seats), i) =>
                    // Over with a result for every seat, or called off: a cancelled match nobody ranked is over
                    // with every seat unranked.
                    val outcome = Option.when(
                      m.status == "F" && seats.nonEmpty && (m.cancelled || seats.forall(_.rank.isDefined))
                    )(
                      seats.map(s => Placing(s.entrant, s.rank, scoreOf(s.scores)))
                    )
                    val planned = PlannedMatch(
                      i + 1,
                      m.round,
                      seats.map(s => SeatPlan(s.entrant, s.role, s.chooseOrder)),
                      m.rubber
                    )
                    (
                      PoolMatch(planned, m.tiesAllowed, Option.unless(m.status == "S")(m.round), outcome),
                      (p.id.toInt, i + 1) -> MatchId(m.id)
                    )
                }
                (
                  Pool(p.id.toInt, p.stratum, ms, matches = numbered.map(_._1), completed = p.completed),
                  numbered.map(_._2).toMap
                )
            }
        }
    }
}
