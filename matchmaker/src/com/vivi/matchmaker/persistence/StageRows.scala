package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import com.vivi.matchmaker.model.tournament.{Repetition, SeedPolicy, StageDesign, TournamentDesign}

/** A stage as a row, the same in `stage_model` and in `stage`: its kind, its properties, the stage played after it, and
  * its subtype's one column -- `rotate_roles` for an atomic stage, the inner design for a nesting one, which a model
  * names as another model and a tournament as the first stage of a chain of its own.
  *
  * Shared by [[TournamentModelRepo]] and [[StageRepo]], which read and write the two tables with the same columns. A
  * model's stages are named by text and a tournament's by number, so the id's type `I` is the caller's.
  */
private[persistence] object StageRows {

    case class Row[I](
        id: I,
        kind: String,
        poolSize: Option[Int],
        name: String,
        description: String,
        concurrentRounds: Boolean,
        bracketed: Boolean,
        repetitions: Option[Int],
        remaining: Option[Int],
        demote: Option[Int],
        maxStratum: Option[Int],
        minStratum: Option[Int],
        pointsForWin: Int,
        pointsForDraw: Int,
        pointsBonus: Boolean,
        promote: Option[Int],
        rubberMatch: Boolean,
        strictStrata: Boolean,
        tieBreakerStage: Boolean,
        seedPolicy: String,
        next: Option[I],
        rotateRoles: Option[Boolean],
        inner: Option[I]
    )

    /** The columns of a stage row, `s` the stage table, `a` and `n` its subtypes left-joined, and `inner` the nesting
      * table's column naming the inner design. Decoded by [[row]].
      */
    def columns(next: String, inner: String): String =
        s"""s.kind::text, s.pool_size, s.name, s.description, s.concurrent_rounds, s.bracketed, s.repetitions,
            s.remaining_participants, s.demote, s.max_stratum, s.min_stratum, s.points_for_win, s.points_for_draw,
            s.points_bonus, s.promote, s.rubber_match, s.strict_strata, s.tie_breaker_stage, s.seed_policy,
            s.$next, a.rotate_roles, n.$inner"""

    /** Decodes the stage's id, by `id`, followed by [[columns]]. */
    def row[I](id: Codec[I]): Decoder[Row[I]] =
        (id *: text *: int4.opt *: text *: text *: bool *: bool *: int4.opt *: int4.opt *: int4.opt *: int4.opt *:
            int4.opt *: int4 *: int4 *: bool *: int4.opt *: bool *: bool *: bool *: text *: id.opt *: bool.opt *:
            id.opt).to[Row[I]]

    /** What is written of a stage: its properties and the stage after it, in the order of [[insertColumns]]. */
    def fields[I](id: Codec[I]): Encoder[(StageDesign, Option[I])] =
        (text *: int4.opt *: text *: text *: bool *: bool *: int4.opt *: int4.opt *: int4.opt *: int4.opt *: int4.opt *:
            int4 *: int4 *: bool *: int4.opt *: bool *: bool *: bool *: text *: id.opt).contramap { case (s, next) =>
            val (repetitions, remaining) = s.repeat match {
                case Repetition.Fixed(k)          => (Some(k), None)
                case Repetition.UntilRemaining(c) => (None, Some(c))
                case Repetition.UntilStopped      => (None, None)
            }
            (
              if (s.design.isDefined) "N" else "A",
              s.poolSize,
              s.name,
              s.description,
              s.concurrentRounds,
              s.brackets,
              repetitions,
              remaining,
              s.demote,
              s.maxStratum,
              s.minStratum,
              s.points,
              s.pointsForDraw,
              s.pointsBonus,
              s.promote,
              s.rubberMatch,
              s.strictStrata,
              s.tieBreakerStage,
              s.seedPolicy.code,
              next
            )
        }

    /** The columns [[fields]] writes, `next` being the table's own column for the stage played after. */
    def insertColumns(next: String): String =
        s"""kind, pool_size, name, description, concurrent_rounds, bracketed, repetitions, remaining_participants,
            demote, max_stratum, min_stratum, points_for_win, points_for_draw, points_bonus, promote, rubber_match,
            strict_strata, tie_breaker_stage, seed_policy, $next"""

    /** A stage from its row, with `inner` the design its pools play if it is a nesting stage. */
    def stageOf[I](r: Row[I], inner: Option[TournamentDesign]): StageDesign =
        StageDesign(
          repeat = (r.repetitions, r.remaining) match {
              case (Some(k), _) => Repetition.Fixed(k)
              case (_, Some(c)) => Repetition.UntilRemaining(c)
              case _            => Repetition.UntilStopped
          },
          brackets = r.bracketed,
          demote = r.demote,
          promote = r.promote,
          maxStratum = r.maxStratum,
          minStratum = r.minStratum,
          points = r.pointsForWin,
          pointsForDraw = r.pointsForDraw,
          pointsBonus = r.pointsBonus,
          poolSize = r.poolSize,
          concurrentRounds = r.concurrentRounds,
          rotateRoles = r.rotateRoles.getOrElse(false),
          strictStrata = r.strictStrata,
          design = inner,
          tieBreakerStage = r.tieBreakerStage,
          seedPolicy = SeedPolicy.fromCode(r.seedPolicy),
          rubberMatch = r.rubberMatch,
          name = r.name,
          description = r.description
        )

    /** The design whose first stage is `first`, following each stage's `next` through `rows`, with `inner` giving the
      * design a nesting stage's pools play from the row's inner reference. A chain that comes back on itself, or names
      * a stage that is not there, is a broken design rather than one to loop on, which the schema cannot rule out.
      */
    def chain[I](rows: Map[I, Row[I]], first: I, inner: I => TournamentDesign): TournamentDesign = {
        def walk(at: Option[I], seen: Set[I]): List[StageDesign] =
            at match {
                case None => Nil
                case Some(id) =>
                    if (seen(id)) throw new IllegalStateException(s"the stages after stage $id come back to it")
                    val r = rows.getOrElse(id, throw new IllegalStateException(s"stage $id is not there"))
                    stageOf(r, if (r.kind == "N") r.inner.map(inner) else None) :: walk(r.next, seen + id)
            }
        TournamentDesign(walk(Some(first), Set.empty))
    }
}
