package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{ParticipantId, Result}

class ResultRepo(session: Session[IO]) {
  private val participantId = SkunkIdCodecs.participantId

  // result.score is NUMERIC, not DOUBLE PRECISION, so it needs its own numeric-based codec
  // rather than float8 to satisfy skunk's strict column-type alignment check.
  private val score: Codec[Double] = numeric.imap(_.doubleValue)(BigDecimal(_))

  private val insertResult: Command[(ParticipantId, Int, Double)] =
    sql"INSERT INTO result (participant_id, rank, score) VALUES ($participantId, $int4, $score)".command

  private val selectResult: Query[ParticipantId, (Int, Double)] =
    sql"SELECT rank, score FROM result WHERE participant_id = $participantId".query(int4 *: score)

  private val updateResult: Command[(Int, Double, ParticipantId)] =
    sql"UPDATE result SET rank = $int4, score = $score WHERE participant_id = $participantId".command

  def create(result: Result): IO[Result] =
    session.transaction.use { _ =>
      session.execute(insertResult)((result.participantId, result.rank, result.score)).as(result)
    }

  def read(id: ParticipantId): IO[Option[Result]] =
    session.option(selectResult)(id).map(_.map { case (rank, score) => Result(id, rank, score) })

  def update(result: Result): IO[Unit] =
    session.transaction.use { _ =>
      session.execute(updateResult)((result.rank, result.score, result.participantId)).void
    }
}
