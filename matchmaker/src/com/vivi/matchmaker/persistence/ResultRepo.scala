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

  // result's primary key is the composite (game_id, participant_id) — participant_id alone is
  // not declared unique (unlike character_id, which has its own explicit UNIQUE constraint), so
  // both columns are required in the WHERE clause here, not participant_id alone.
  private val selectResult: Query[(GameId, ParticipantId), (Int, Double)] =
    sql"SELECT rank, score FROM result WHERE game_id = $gameId AND participant_id = $participantId".query(int4 *: score)

  private val updateResult: Command[(Int, Double, GameId, ParticipantId)] =
    sql"UPDATE result SET rank = $int4, score = $score WHERE game_id = $gameId AND participant_id = $participantId".command

  def create(result: Result): IO[Result] =
    session.execute(insertResult)((result.gameId, result.participantId, result.rank, result.score)).as(result)

  def read(gameId: GameId, id: ParticipantId): IO[Option[Result]] =
    session.option(selectResult)((gameId, id)).map(_.map { case (rank, score) => Result(gameId, id, rank, score) })

  def update(result: Result): IO[Unit] =
    session.execute(updateResult)((result.rank, result.score, result.gameId, result.participantId)).void
}
