package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign}

/** Reads and writes the models every game's tournaments are made from: `tournament_model`, its stages in `stage_model`
  * with their atomic and nesting rows, and `tournament_model_game`, the models each game offers.
  *
  * A model's design is its stages, chained from `first_stage_model_id` along each stage's `current_stage_model_id`. A
  * nesting stage's pools play another model, named by `nesting_stage_model.inner_tournament_model_id`: when a design is
  * written, each inner design becomes a model of its own with no classification, which is what keeps it out of every
  * list, named after its stage -- `world-cup/stage-2`. Rewriting, deactivating or deleting a model takes its inner
  * models with it.
  *
  * Opens no transaction of its own; that is the calling service's job. Writing a model writes it and its stages before
  * each points at the other, which the deferred foreign keys between them allow within one transaction.
  */
class TournamentModelRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val modelId = SkunkIdCodecs.tournamentModelId

    private type ModelRow =
        (TournamentModelId, Int, String, String, Option[String], Option[Int], Boolean, String, Boolean, Boolean, Int)
    private val modelRow: Decoder[ModelRow] =
        modelId *: int4 *: text *: text *: text.opt *: int4.opt *: bool *: text *: bool *: bool *: int4

    private val modelColumns =
        "tournament_model_id, version, name, description, classification, pool_size, has_late_entries, " +
            "first_stage_model_id, is_active, has_role_choice, display_order"

    private val selectModels: Query[Void, ModelRow] =
        sql"SELECT #$modelColumns FROM tournament_model".query(modelRow)

    private val selectStages: Query[Void, (TournamentModelId, StageRows.Row[String])] =
        sql"""SELECT s.tournament_model_id, s.stage_model_id,
                 #${StageRows.columns("current_stage_model_id", "inner_tournament_model_id")}
          FROM stage_model s
          LEFT JOIN atomic_stage_model a USING (tournament_model_id, stage_model_id)
          LEFT JOIN nesting_stage_model n USING (tournament_model_id, stage_model_id)""".query(
          modelId *: StageRows.row(text)
        )

    /** Every model, with its design, offered or not, inner ones and deactivated ones included. One read of the models
      * and one of all their stages, however many there are: they are few, and every game's.
      */
    private def all: IO[List[TournamentModel]] =
        (session.execute(selectModels), session.execute(selectStages)).tupled.map { (models, stages) =>
            val byModel: Map[TournamentModelId, Map[String, StageRows.Row[String]]] =
                stages.groupBy(_._1).view.mapValues(_.map((_, r) => r.id -> r).toMap).toMap
            val firstOf = models.map(m => m._1 -> m._8).toMap
            def designOf(model: TournamentModelId, depth: Int): TournamentDesign =
                if (depth > 8) throw new IllegalStateException(s"model $model nests inside itself")
                else
                    StageRows.chain(
                      rows = byModel.getOrElse(model, Map.empty),
                      first = firstOf.getOrElse(model, throw new IllegalStateException(s"model $model is not there")),
                      inner = inner => designOf(TournamentModelId(inner), depth + 1)
                    )
            models.map {
                case (id, version, name, description, classification, poolSize, late, _, active, choose, order) =>
                    TournamentModel(
                      tournamentModelId = id,
                      version = version,
                      name = name,
                      description = description,
                      classification = classification,
                      poolSize = poolSize,
                      hasLateEntries = late,
                      design = designOf(id, 0),
                      isActive = active,
                      hasRoleChoice = choose,
                      displayOrder = order
                    )
            }
        }

    private def listed(models: List[TournamentModel]): List[TournamentModel] =
        models.filter(_.classification.isDefined).sortBy(m => (m.displayOrder, m.name.toLowerCase))

    /** Every model a game could offer -- those with a classification that are active -- by display order and then name.
      */
    def listActive: IO[List[TournamentModel]] = all.map(ms => listed(ms.filter(_.isActive)))

    /** Every model with a classification, deactivated or not: what a sync compares with the code. */
    def listClassified: IO[List[TournamentModel]] = all.map(listed)

    private val selectOffered: Query[GameId, TournamentModelId] =
        sql"SELECT tournament_model_id FROM tournament_model_game WHERE game_id = $gameId".query(modelId)

    /** The models the game offers for creating a tournament: those its admins chose that are still active. */
    def listOffered(game: GameId): IO[List[TournamentModel]] =
        (session.execute(selectOffered)(game), listActive).mapN((offered, active) =>
            active.filter(m => offered.contains(m.tournamentModelId))
        )

    private val selectOfferedIn: Query[(GameId, TournamentModelId), TournamentModelId] =
        sql"""SELECT tournament_model_id FROM tournament_model_game
          WHERE game_id = $gameId AND tournament_model_id = $modelId""".query(modelId)

    /** Whether the game offers the model, active or not. */
    def offeredIn(game: GameId, id: TournamentModelId): IO[Boolean] =
        session.option(selectOfferedIn)((game, id)).map(_.isDefined)

    def read(id: TournamentModelId): IO[Option[TournamentModel]] =
        all.map(_.find(_.tournamentModelId == id))

    private val selectForUpdate: Query[TournamentModelId, TournamentModelId] =
        sql"SELECT tournament_model_id FROM tournament_model WHERE tournament_model_id = $modelId FOR UPDATE"
            .query(modelId)

    private val selectForShare: Query[TournamentModelId, TournamentModelId] =
        sql"SELECT tournament_model_id FROM tournament_model WHERE tournament_model_id = $modelId FOR SHARE"
            .query(modelId)

    /** Locks the model's row for the rest of the transaction: what a sync rewriting or removing it takes first. */
    def lockForUpdate(id: TournamentModelId): IO[Boolean] =
        session.option(selectForUpdate)(id).map(_.isDefined)

    /** Holds the model's row for the rest of the transaction against a sync rewriting or removing it, without making
      * two tournaments made from it, or two games offering it, wait on each other.
      */
    def lockForShare(id: TournamentModelId): IO[Boolean] =
        session.option(selectForShare)(id).map(_.isDefined)

    // ---- the models a game offers ----

    private val insertOffer: Command[(GameId, TournamentModelId)] =
        sql"""INSERT INTO tournament_model_game (game_id, tournament_model_id) VALUES ($gameId, $modelId)
          ON CONFLICT DO NOTHING""".command

    private val deleteOffer: Command[(GameId, TournamentModelId)] =
        sql"DELETE FROM tournament_model_game WHERE game_id = $gameId AND tournament_model_id = $modelId".command

    /** Offers the model in the game; nothing if it is offered already. */
    def offer(game: GameId, id: TournamentModelId): IO[Unit] = session.execute(insertOffer)((game, id)).void

    /** Stops offering the model in the game; nothing if it was not. */
    def withdraw(game: GameId, id: TournamentModelId): IO[Unit] = session.execute(deleteOffer)((game, id)).void

    // ---- writing a model from the code ----

    private type Properties = (Int, String, String, Option[String], Option[Int], Boolean, Boolean, Int)

    private def propertiesOf(m: TournamentModel): Properties =
        (
          m.version,
          m.name,
          m.description,
          m.classification,
          m.poolSize,
          m.hasLateEntries,
          m.hasRoleChoice,
          m.displayOrder
        )

    private val insertModel: Command[TournamentModelId *: Properties] =
        sql"""INSERT INTO tournament_model (tournament_model_id, version, name, description, classification, pool_size,
                                            has_late_entries, has_role_choice, display_order, first_stage_model_id)
          VALUES ($modelId, $int4, $text, $text, ${text.opt}, ${int4.opt}, $bool, $bool, $int4, '')""".command

    private val updateModel: Command[Tuple.Concat[Properties, TournamentModelId *: EmptyTuple]] =
        sql"""UPDATE tournament_model SET version = $int4, name = $text, description = $text,
                 classification = ${text.opt}, pool_size = ${int4.opt}, has_late_entries = $bool, has_role_choice = $bool,
                 display_order = $int4, is_active = true
          WHERE tournament_model_id = $modelId""".command

    private val setFirst: Command[(String, TournamentModelId)] =
        sql"UPDATE tournament_model SET first_stage_model_id = $text WHERE tournament_model_id = $modelId".command

    private val insertStage: Command[(TournamentModelId, String, (StageDesign, Option[String]))] =
        sql"""INSERT INTO stage_model (tournament_model_id, stage_model_id, #${StageRows.insertColumns(
              "current_stage_model_id"
            )})
          VALUES ($modelId, $text, ${StageRows.fields(text)})""".command

    private val insertAtomic: Command[(TournamentModelId, String, Boolean)] =
        sql"""INSERT INTO atomic_stage_model (tournament_model_id, stage_model_id, is_rotating_roles)
          VALUES ($modelId, $text, $bool)""".command

    private val insertNesting: Command[(TournamentModelId, String, TournamentModelId)] =
        sql"""INSERT INTO nesting_stage_model (tournament_model_id, stage_model_id, inner_tournament_model_id)
          VALUES ($modelId, $text, $modelId)""".command

    /** Writes a model -- one the code defines -- its stages, and a model for each inner design, active. */
    def create(m: TournamentModel): IO[Unit] = {
        val id = m.tournamentModelId
        session.execute(insertModel)(id *: propertiesOf(m)) *> writeDesign(id, m.design)
    }

    /** Rewrites a stored model as the code now defines it, active again if it was not. Its old stages and inner models
      * go; tournaments already made from it keep the stages they copied.
      */
    def replace(m: TournamentModel): IO[Unit] = {
        val id = m.tournamentModelId
        session.execute(updateModel)(propertiesOf(m) :* id) *> removeStages(id, keep = true) *>
            writeDesign(id, m.design)
    }

    /** Removes a model with its stages, inner models and the games' choices of it. Refused by the database, as a
      * foreign key violation, while a tournament made from it remains.
      */
    def delete(id: TournamentModelId): IO[Unit] = removeStages(id, keep = false)

    private val deactivateModel: Command[TournamentModelId] =
        sql"UPDATE tournament_model SET is_active = false WHERE tournament_model_id = $modelId".command
    private val deactivateStages: Command[TournamentModelId] =
        sql"UPDATE stage_model SET is_active = false WHERE tournament_model_id = $modelId".command

    /** Deactivates a model, its stages and, depth first, its inner models: offered no longer, and kept. */
    def deactivate(id: TournamentModelId): IO[Unit] =
        for {
            inner <- session.execute(selectInner)(id)
            _ <- inner.traverse_(deactivate)
            _ <- session.execute(deactivateStages)(id)
            _ <- session.execute(deactivateModel)(id)
        } yield ()

    /** The ids of a design's stages, in order: each named stage by its name, made into an id, and any other by its
      * place -- `stage-2` -- with a number added where two would be the same.
      */
    private[persistence] def stageIds(design: TournamentDesign): List[String] =
        design.stages.zipWithIndex
            .foldLeft(List.empty[String]) { case (taken, (stage, i)) =>
                val slug = stage.name.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
                val base = if (slug.isEmpty) s"stage-${i + 1}" else slug
                val id = LazyList.from(2).map(n => s"$base-$n").prepended(base).find(!taken.contains(_)).get
                taken :+ id
            }

    /* The stages of `design` written last first, so that each names the one after it as it is written, and the first
     * set as the model's own. An inner design becomes an unclassified model of its own, named after the stage. */
    private def writeDesign(model: TournamentModelId, design: TournamentDesign): IO[Unit] =
        design.stages
            .zip(stageIds(design))
            .reverse
            .foldLeftM(Option.empty[String]) { case (next, (stage, stageId)) =>
                stage.design match {
                    case Some(inner) =>
                        val innerId = TournamentModelId(s"${model.value}/$stageId")
                        for {
                            _ <- session.execute(insertModel)(
                              (innerId, 1, s"${model.value}, $stageId", "", None, None, false, false, 0)
                            )
                            _ <- writeDesign(innerId, inner)
                            _ <- session.execute(insertStage)((model, stageId, (stage, next)))
                            _ <- session.execute(insertNesting)((model, stageId, innerId))
                        } yield Some(stageId)
                    case None =>
                        for {
                            _ <- session.execute(insertStage)((model, stageId, (stage, next)))
                            _ <- session.execute(insertAtomic)((model, stageId, stage.isRotatingRoles))
                        } yield Some(stageId)
                }
            }
            .flatMap {
                case Some(first) => session.execute(setFirst)((first, model)).void
                case None        => IO.raiseError(new IllegalArgumentException("a design needs at least one stage"))
            }

    private val selectInner: Query[TournamentModelId, TournamentModelId] =
        sql"SELECT inner_tournament_model_id FROM nesting_stage_model WHERE tournament_model_id = $modelId"
            .query(modelId)

    private val deleteAtomic: Command[TournamentModelId] =
        sql"DELETE FROM atomic_stage_model WHERE tournament_model_id = $modelId".command
    private val deleteNesting: Command[TournamentModelId] =
        sql"DELETE FROM nesting_stage_model WHERE tournament_model_id = $modelId".command
    private val deleteStages: Command[TournamentModelId] =
        sql"DELETE FROM stage_model WHERE tournament_model_id = $modelId".command
    private val deleteOffers: Command[TournamentModelId] =
        sql"DELETE FROM tournament_model_game WHERE tournament_model_id = $modelId".command
    private val deleteModel: Command[TournamentModelId] =
        sql"DELETE FROM tournament_model WHERE tournament_model_id = $modelId".command

    /* A model's stages and, depth first, its inner models; the model itself too, and the games' choices of it, unless
     * `keep`. Its subtype rows go first, since they name the inner models; the model row last, since the stages are
     * its. */
    private def removeStages(model: TournamentModelId, keep: Boolean): IO[Unit] =
        for {
            inner <- session.execute(selectInner)(model)
            _ <- session.execute(deleteNesting)(model)
            _ <- session.execute(deleteAtomic)(model)
            _ <- inner.traverse_(removeStages(_, keep = false))
            _ <- session.execute(deleteStages)(model)
            _ <- IO.unlessA(keep)(session.execute(deleteOffers)(model) *> session.execute(deleteModel)(model).void)
        } yield ()
}
