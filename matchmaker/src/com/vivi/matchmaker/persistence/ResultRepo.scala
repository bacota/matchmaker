package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.Result

class ResultRepo(session: Session[IO]) {

  private val insertResult: Command[(Long, Int, Double)] =
    sql"INSERT INTO result (participant_id, rank, score) VALUES ($int8, $int4, $float8)".command

  private val selectResult: Query[Long, (Int, Double)] =
    sql"SELECT rank, score FROM result WHERE participant_id = $int8".query(int4 *: float8)

  private val updateResult: Command[(Int, Double, Long)] =
    sql"UPDATE result SET rank = $int4, score = $float8 WHERE participant_id = $int8".command

  def create(result: Result): IO[Result] =
    session.transaction.use { _ =>
      session.execute(insertResult)((result.participantId, result.rank, result.score)).as(result)
    }

  def read(participantId: Long): IO[Option[Result]] =
    session.option(selectResult)(participantId).map(_.map { case (rank, score) => Result(participantId, rank, score) })

  def update(result: Result): IO[Unit] =
    session.transaction.use { _ =>
      session.execute(updateResult)((result.rank, result.score, result.participantId)).void
    }
}
