package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign}

/** Reads and writes the models a game's admins offer (V52): `tournament_model`, and its stages in `stage_model` with
  * their atomic and nesting rows.
  *
  * A model's design is its stages, chained from `first_stage_model_id` along each stage's `current_stage_model_id`. A
  * nesting stage's pools play another model, named by `nesting_stage_model.inner_tournament_model_id`: when a design is
  * written, each inner design becomes a model of its own with no classification, which is what keeps it out of the list
  * a tournament is created from. Editing, deactivating or deleting a model takes its inner models with it.
  *
  * Opens no transaction of its own; that is the calling service's job. Writing a model writes it and its stages before
  * each points at the other, which the deferred foreign keys between them allow within one transaction.
  */
class TournamentModelRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val modelId = SkunkIdCodecs.tournamentModelId

    private type ModelRow = (TournamentModelId, String, String, Option[String], Option[Int], Boolean, Long, Boolean)
    private val modelRow: Decoder[ModelRow] = modelId *: text *: text *: text.opt *: int4.opt *: bool *: int8 *: bool

    private val selectModels: Query[GameId, ModelRow] =
        sql"""SELECT tournament_model_id, name, description, classification, pool_size, late_entries, first_stage_model_id,
                 active
          FROM tournament_model WHERE game_id = $gameId""".query(modelRow)

    private val selectModelForUpdate: Query[(GameId, TournamentModelId), ModelRow] =
        sql"""SELECT tournament_model_id, name, description, classification, pool_size, late_entries, first_stage_model_id,
                 active
          FROM tournament_model WHERE game_id = $gameId AND tournament_model_id = $modelId FOR UPDATE""".query(modelRow)

    private val selectStages: Query[GameId, (Long, StageRows.Row)] =
        sql"""SELECT s.tournament_model_id, s.stage_model_id,
                 #${StageRows.columns("current_stage_model_id", "inner_tournament_model_id")}
          FROM stage_model s
          LEFT JOIN atomic_stage_model a USING (game_id, tournament_model_id, stage_model_id)
          LEFT JOIN nesting_stage_model n USING (game_id, tournament_model_id, stage_model_id)
          WHERE s.game_id = $gameId""".query(int8 *: StageRows.row)

    /** Every model of the game, with its design, offered or not. One read of the models and one of all their stages,
      * however many there are.
      */
    private def all(game: GameId): IO[List[TournamentModel]] =
        (session.execute(selectModels)(game), session.execute(selectStages)(game)).tupled.map { (models, stages) =>
            val byModel: Map[Long, Map[Long, StageRows.Row]] =
                stages.groupBy(_._1).view.mapValues(_.map((_, r) => r.id -> r).toMap).toMap
            val firstOf = models.map(m => m._1.value -> m._7).toMap
            def designOf(model: Long, depth: Int): TournamentDesign =
                if (depth > 8) throw new IllegalStateException(s"model $model nests inside itself")
                else
                    StageRows.chain(
                      byModel.getOrElse(model, Map.empty),
                      firstOf.getOrElse(model, throw new IllegalStateException(s"model $model is not there")),
                      inner => designOf(inner, depth + 1)
                    )
            models.map { case (id, name, description, classification, poolSize, late, _, active) =>
                TournamentModel(
                  game,
                  id,
                  name,
                  description,
                  classification,
                  poolSize,
                  late,
                  designOf(id.value, 0),
                  active
                )
            }
        }

    /** The models offered for creating a tournament of the game -- those with a classification that are active -- by
      * classification and then name.
      */
    def listOffered(game: GameId): IO[List[TournamentModel]] =
        listClassified(game).map(_.filter(_.active))

    /** Every model of the game with a classification, deactivated or not, by classification and then name. */
    def listClassified(game: GameId): IO[List[TournamentModel]] =
        all(game).map(_.filter(_.classification.isDefined).sortBy(m => (m.classification, m.name.toLowerCase)))

    def read(game: GameId, id: TournamentModelId): IO[Option[TournamentModel]] =
        all(game).map(_.find(_.tournamentModelId == id))

    /** Locks the model's row for the rest of the transaction: what an edit or a delete takes first. */
    def lockForUpdate(game: GameId, id: TournamentModelId): IO[Boolean] =
        session.option(selectModelForUpdate)((game, id)).map(_.isDefined)

    private val selectModelForShare: Query[(GameId, TournamentModelId), TournamentModelId] =
        sql"""SELECT tournament_model_id FROM tournament_model
          WHERE game_id = $gameId AND tournament_model_id = $modelId FOR SHARE""".query(modelId)

    /** Holds the model's row for the rest of the transaction against an admin deactivating or deleting it, without
      * making two tournaments made from it wait on each other: what making a tournament from it takes first.
      */
    def lockForShare(game: GameId, id: TournamentModelId): IO[Boolean] =
        session.option(selectModelForShare)((game, id)).map(_.isDefined)

    private val insertModel: Query[(GameId, String, String, Option[String], Option[Int], Boolean), TournamentModelId] =
        sql"""INSERT INTO tournament_model (game_id, name, description, classification, pool_size, late_entries,
                                            first_stage_model_id)
          VALUES ($gameId, $text, $text, ${text.opt}, ${int4.opt}, $bool, 0)
          RETURNING tournament_model_id""".query(modelId)

    private val updateModel
        : Command[(String, String, Option[String], Option[Int], Boolean, GameId, TournamentModelId)] =
        sql"""UPDATE tournament_model SET name = $text, description = $text, classification = ${text.opt},
                 pool_size = ${int4.opt}, late_entries = $bool
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".command

    private val setFirst: Command[(Long, GameId, TournamentModelId)] =
        sql"""UPDATE tournament_model SET first_stage_model_id = $int8
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".command

    private val insertStage: Query[(GameId, TournamentModelId, (StageDesign, Option[Long])), Long] =
        sql"""INSERT INTO stage_model (game_id, tournament_model_id, #${StageRows.insertColumns(
              "current_stage_model_id"
            )})
          VALUES ($gameId, $modelId, ${StageRows.fields})
          RETURNING stage_model_id""".query(int8)

    private val insertAtomic: Command[(GameId, TournamentModelId, Long, Boolean)] =
        sql"""INSERT INTO atomic_stage_model (game_id, tournament_model_id, stage_model_id, rotate_roles)
          VALUES ($gameId, $modelId, $int8, $bool)""".command

    private val insertNesting: Command[(GameId, TournamentModelId, Long, TournamentModelId)] =
        sql"""INSERT INTO nesting_stage_model (game_id, tournament_model_id, stage_model_id, inner_tournament_model_id)
          VALUES ($gameId, $modelId, $int8, $modelId)""".command

    /** Writes a new model, its design's stages, and a model for each inner design. */
    def create(game: GameId, request: TournamentModelRequest): IO[TournamentModelId] =
        session
            .unique(insertModel)(
              (game, request.name, request.description, request.classification, request.poolSize, request.lateEntries)
            )
            .flatTap(id => writeDesign(game, id, request.name, request.design))

    /** Replaces a model's properties and design. Its old stages and inner models go; tournaments already made from it
      * keep the stages they copied.
      */
    def update(game: GameId, id: TournamentModelId, request: TournamentModelRequest): IO[Unit] =
        session.execute(updateModel)(
          (request.name, request.description, request.classification, request.poolSize, request.lateEntries, game, id)
        ) *> removeStages(game, id, keep = true) *> writeDesign(game, id, request.name, request.design)

    /** Removes a model with its stages and inner models. Refused by the database, as a foreign key violation, while a
      * tournament made from it remains.
      */
    def delete(game: GameId, id: TournamentModelId): IO[Unit] = removeStages(game, id, keep = false)

    private val deactivateModel: Command[(GameId, TournamentModelId)] =
        sql"""UPDATE tournament_model SET active = false
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".command
    private val deactivateStages: Command[(GameId, TournamentModelId)] =
        sql"""UPDATE stage_model SET active = false
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".command

    /** Deactivates a model, its stages and, depth first, its inner models: offered no longer, and kept. */
    def deactivate(game: GameId, id: TournamentModelId): IO[Unit] =
        for {
            inner <- session.execute(selectInner)((game, id))
            _ <- inner.traverse_(deactivate(game, _))
            _ <- session.execute(deactivateStages)((game, id))
            _ <- session.execute(deactivateModel)((game, id))
        } yield ()

    /* The stages of `design` written last first, so that each names the one after it as it is written, and the first
     * set as the model's own. An inner design becomes an unclassified model of its own, named after the stage. */
    private def writeDesign(game: GameId, model: TournamentModelId, name: String, design: TournamentDesign): IO[Unit] =
        design.stages.zipWithIndex.reverse
            .foldLeftM(Option.empty[Long]) { case (next, (stage, i)) =>
                stage.design match {
                    case Some(inner) =>
                        val innerName = s"$name, stage ${i + 1}"
                        for {
                            innerId <- create(game, TournamentModelRequest(innerName, design = inner))
                            id <- session.unique(insertStage)((game, model, (stage, next)))
                            _ <- session.execute(insertNesting)((game, model, id, innerId))
                        } yield Some(id)
                    case None =>
                        for {
                            id <- session.unique(insertStage)((game, model, (stage, next)))
                            _ <- session.execute(insertAtomic)((game, model, id, stage.rotateRoles))
                        } yield Some(id)
                }
            }
            .flatMap {
                case Some(first) => session.execute(setFirst)((first, game, model)).void
                case None        => IO.raiseError(new IllegalArgumentException("a design needs at least one stage"))
            }

    private val selectInner: Query[(GameId, TournamentModelId), TournamentModelId] =
        sql"""SELECT inner_tournament_model_id FROM nesting_stage_model
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".query(modelId)

    private val deleteAtomic: Command[(GameId, TournamentModelId)] =
        sql"DELETE FROM atomic_stage_model WHERE game_id = $gameId AND tournament_model_id = $modelId".command
    private val deleteNesting: Command[(GameId, TournamentModelId)] =
        sql"DELETE FROM nesting_stage_model WHERE game_id = $gameId AND tournament_model_id = $modelId".command
    private val deleteStages: Command[(GameId, TournamentModelId)] =
        sql"DELETE FROM stage_model WHERE game_id = $gameId AND tournament_model_id = $modelId".command
    private val deleteModel: Command[(GameId, TournamentModelId)] =
        sql"DELETE FROM tournament_model WHERE game_id = $gameId AND tournament_model_id = $modelId".command

    /* A model's stages and, depth first, its inner models; the model itself too unless `keep`. Its subtype rows go
     * first, since they name the inner models; the model row last, since the stages are its. */
    private def removeStages(game: GameId, model: TournamentModelId, keep: Boolean): IO[Unit] =
        for {
            inner <- session.execute(selectInner)((game, model))
            _ <- session.execute(deleteNesting)((game, model))
            _ <- session.execute(deleteAtomic)((game, model))
            _ <- inner.traverse_(removeStages(game, _, keep = false))
            _ <- session.execute(deleteStages)((game, model))
            _ <- IO.unlessA(keep)(session.execute(deleteModel)((game, model)).void)
        } yield ()
}
