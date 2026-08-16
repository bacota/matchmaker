package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{GameId, ParticipantId, Result}

class ResultRepo(session: Session[IO]) {
  private val gameId = SkunkIdCodecs.gameId
  private val participantId = SkunkIdCodecs.participantId

  // result.score is NUMERIC, not DOUBLE PRECISION, so it needs its own numeric-based codec
  // rather than float8 to satisfy skunk's strict column-type alignment check.
  private val score: Codec[Double] = numeric.imap(_.doubleValue)(BigDecimal(_))

  private val insertResult: Command[(GameId, ParticipantId, Int, Double)] =
    sql"INSERT INTO result (game_id, participant_id, rank, score) VALUES ($gameId, $participantId, $int4, $score)".command

  // participant_id alone already identifies the row (it's globally unique, not just per game),
  // so looking it up doesn't need game_id — but game_id is still selected, both to populate the
  // model and because result's primary key is (game_id, participant_id).
  private val selectResult: Query[ParticipantId, (GameId, Int, Double)] =
    sql"SELECT game_id, rank, score FROM result WHERE participant_id = $participantId".query(gameId *: int4 *: score)

  private val updateResult: Command[(Int, Double, ParticipantId)] =
    sql"UPDATE result SET rank = $int4, score = $score WHERE participant_id = $participantId".command

  def create(result: Result): IO[Result] =
    session.transaction.use { _ =>
      session.execute(insertResult)((result.gameId, result.participantId, result.rank, result.score)).as(result)
    }

  def read(id: ParticipantId): IO[Option[Result]] =
    session.option(selectResult)(id).map(_.map { case (gameId, rank, score) => Result(gameId, id, rank, score) })

  def update(result: Result): IO[Unit] =
    session.transaction.use { _ =>
      session.execute(updateResult)((result.rank, result.score, result.participantId)).void
    }
}
