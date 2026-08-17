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

  // result.scores is jsonb holding an object; SkunkCodecs.jsonObject presents it as a Map.
  private val scores: Codec[Map[String, Any]] = SkunkCodecs.jsonObject

  private val insertResult: Command[(GameId, ParticipantId, Int, Map[String, Any], Boolean)] =
    sql"""INSERT INTO result (game_id, participant_id, rank, scores, is_winner)
          VALUES ($gameId, $participantId, $int4, $scores, $bool)""".command

  // result's primary key is the composite (game_id, participant_id) — participant_id alone is
  // not declared unique (unlike character_id, which has its own explicit UNIQUE constraint), so
  // both columns are required in the WHERE clause here, not participant_id alone.
  private val selectResult: Query[(GameId, ParticipantId), (Int, Map[String, Any], Boolean)] =
    sql"""SELECT rank, scores, is_winner FROM result
          WHERE game_id = $gameId AND participant_id = $participantId""".query(int4 *: scores *: bool)

  private val updateResult: Command[(Int, Map[String, Any], Boolean, GameId, ParticipantId)] =
    sql"""UPDATE result SET rank = $int4, scores = $scores, is_winner = $bool
          WHERE game_id = $gameId AND participant_id = $participantId""".command

  def create(result: Result): IO[Result] =
    session
      .execute(insertResult)((result.gameId, result.participantId, result.rank, result.scores, result.isWinner))
      .as(result)

  def read(gameId: GameId, id: ParticipantId): IO[Option[Result]] =
    session.option(selectResult)((gameId, id)).map(_.map { case (rank, scores, isWinner) =>
      Result(gameId, id, rank, scores, isWinner)
    })

  def update(result: Result): IO[Unit] =
    session
      .execute(updateResult)((result.rank, result.scores, result.isWinner, result.gameId, result.participantId))
      .void
}
