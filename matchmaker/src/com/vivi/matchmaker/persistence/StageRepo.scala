package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign}

/** A tournament's stages: `stage`, with its atomic and nesting rows, copied from the model the tournament was made
  * from.
  *
  * Every stage of a tournament -- however deeply nested -- belongs to it: an inner design is a chain of the
  * tournament's own stages, from `nesting_stage.inner_first_stage_id` along each `current_stage_id`, as its top-level
  * design is from `tournament.first_stage_id`.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class StageRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId

    private val selectStages: Query[(GameId, TournamentId), StageRows.Row[Long]] =
        sql"""SELECT s.stage_id, #${StageRows.columns("current_stage_id", "inner_first_stage_id")}
          FROM stage s
          LEFT JOIN atomic_stage a USING (game_id, tournament_id, stage_id)
          LEFT JOIN nesting_stage n USING (game_id, tournament_id, stage_id)
          WHERE s.game_id = $gameId AND s.tournament_id = $tournamentId""".query(StageRows.row(int8))

    private val selectFirst: Query[(GameId, TournamentId), Long] =
        sql"SELECT first_stage_id FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId".query(int8)

    /** The tournament's design, as the rules take it: its stages from the first, each nesting stage with the design its
      * pools play. `None` for a tournament that is not there.
      */
    def design(game: GameId, tournament: TournamentId): IO[Option[TournamentDesign]] =
        session.option(selectFirst)((game, tournament)).flatMap {
            case None => IO.pure(None)
            case Some(first) =>
                session.execute(selectStages)((game, tournament)).map { rows =>
                    val byId = rows.map(r => r.id -> r).toMap
                    def chain(from: Long, depth: Int): TournamentDesign =
                        if (depth > 8) throw new IllegalStateException(s"tournament $tournament nests inside itself")
                        else StageRows.chain(byId, from, inner => chain(inner, depth + 1))
                    Some(chain(first, 0))
                }
        }

    /** The tournament's design with its stages' ids, each keyed as the rules name a stage: the indices of the stages
      * from the top-level design down to it, a node's `path` and then the stage's index in the node's design.
      */
    def designWithIds(game: GameId, tournament: TournamentId): IO[Option[(TournamentDesign, Map[List[Int], Long])]] =
        session.option(selectFirst)((game, tournament)).flatMap {
            case None => IO.pure(None)
            case Some(first) =>
                session.execute(selectStages)((game, tournament)).map { rows =>
                    val byId = rows.map(r => r.id -> r).toMap
                    def chain(from: Long, depth: Int): TournamentDesign =
                        if (depth > 8) throw new IllegalStateException(s"tournament $tournament nests inside itself")
                        else StageRows.chain(byId, from, inner => chain(inner, depth + 1))
                    // The ids along a chain, the same way the chain is followed.
                    def ids(from: Long, prefix: List[Int]): Map[List[Int], Long] = {
                        val along = Iterator
                            .iterate(Option(from))(_.flatMap(id => byId.get(id).flatMap(_.next)))
                            .takeWhile(_.isDefined)
                            .flatten
                            .take(byId.size)
                            .toList
                        along.zipWithIndex.flatMap { (id, i) =>
                            val key = prefix :+ i
                            (key -> id) :: byId(id).inner.filter(_ => byId(id).kind == "N").toList.flatMap(ids(_, key))
                        }.toMap
                    }
                    Some((chain(first, 0), ids(first, Nil)))
                }
        }

    private val insertStage: Query[(GameId, TournamentId, (StageDesign, Option[Long])), Long] =
        sql"""INSERT INTO stage (game_id, tournament_id, #${StageRows.insertColumns("current_stage_id")})
          VALUES ($gameId, $tournamentId, ${StageRows.fields(int8)})
          RETURNING stage_id""".query(int8)

    private val insertAtomic: Command[(GameId, TournamentId, Long, Boolean)] =
        sql"""INSERT INTO atomic_stage (game_id, tournament_id, stage_id, is_rotate_roles)
          VALUES ($gameId, $tournamentId, $int8, $bool)""".command

    private val insertNesting: Command[(GameId, TournamentId, Long, Long)] =
        sql"""INSERT INTO nesting_stage (game_id, tournament_id, stage_id, inner_first_stage_id)
          VALUES ($gameId, $tournamentId, $int8, $int8)""".command

    private val setFirst: Command[(Long, GameId, TournamentId)] =
        sql"UPDATE tournament SET first_stage_id = $int8 WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    /** Writes `design` as the tournament's stages and makes its first stage the tournament's: what creating a
      * tournament from a model does with the model's design.
      */
    def copy(game: GameId, tournament: TournamentId, design: TournamentDesign): IO[Unit] =
        write(game, tournament, design).flatMap(first => session.execute(setFirst)((first, game, tournament)).void)

    /* A chain written last first, so that each stage names the one after it as it is written. A nesting stage's
     * inner chain is written before it, so that it can name the inner chain's first stage. */
    private def write(game: GameId, tournament: TournamentId, design: TournamentDesign): IO[Long] =
        design.stages.reverse
            .foldLeftM(Option.empty[Long]) { (next, stage) =>
                stage.design match {
                    case Some(inner) =>
                        for {
                            innerFirst <- write(game, tournament, inner)
                            id <- session.unique(insertStage)((game, tournament, (stage, next)))
                            _ <- session.execute(insertNesting)((game, tournament, id, innerFirst))
                        } yield Some(id)
                    case None =>
                        for {
                            id <- session.unique(insertStage)((game, tournament, (stage, next)))
                            _ <- session.execute(insertAtomic)((game, tournament, id, stage.isRotateRoles))
                        } yield Some(id)
                }
            }
            .flatMap {
                case Some(first) => IO.pure(first)
                case None        => IO.raiseError(new IllegalArgumentException("a design needs at least one stage"))
            }

    private val deleteAtomic: Command[(GameId, TournamentId)] =
        sql"DELETE FROM atomic_stage WHERE game_id = $gameId AND tournament_id = $tournamentId".command
    private val deleteNesting: Command[(GameId, TournamentId)] =
        sql"DELETE FROM nesting_stage WHERE game_id = $gameId AND tournament_id = $tournamentId".command
    private val deleteStages: Command[(GameId, TournamentId)] =
        sql"DELETE FROM stage WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    /** Removes every stage of a tournament that has not started, for one being made from a different model. The
      * tournament's `first_stage_id` is left naming a stage that is gone, which the deferred key allows until the
      * transaction commits: [[copy]] the new design before then.
      */
    def removeAll(game: GameId, tournament: TournamentId): IO[Unit] =
        session.execute(deleteNesting)((game, tournament)) *> session.execute(deleteAtomic)((game, tournament)) *>
            session.execute(deleteStages)((game, tournament)).void
}
