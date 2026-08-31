package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{GameId, MatchId, ParticipantResult, ParticipantId, PlayerId, Result}

class ResultRepo(session: Session[IO]) {
  private val gameId = SkunkIdCodecs.gameId
  private val participantId = SkunkIdCodecs.participantId

  // result.scores is jsonb holding an object; SkunkCodecs.jsonObject presents it as a Map.
  private val scores: Codec[Map[String, Any]] = SkunkCodecs.jsonObject

  private val insertResult: Command[(GameId, ParticipantId, Int, Map[String, Any], Boolean, Boolean)] =
    sql"""INSERT INTO result (game_id, participant_id, rank, scores, is_winner, forfeit)
          VALUES ($gameId, $participantId, $int4, $scores, $bool, $bool)""".command

  // result's primary key is the composite (game_id, participant_id) — participant_id alone is
  // not declared unique (unlike character_id, which has its own explicit UNIQUE constraint), so
  // both columns are required in the WHERE clause here, not participant_id alone.
  private val selectResult: Query[(GameId, ParticipantId), (Int, Map[String, Any], Boolean, Boolean)] =
    sql"""SELECT rank, scores, is_winner, forfeit FROM result
          WHERE game_id = $gameId AND participant_id = $participantId""".query(int4 *: scores *: bool *: bool)

  private val updateResult: Command[(Int, Map[String, Any], Boolean, Boolean, GameId, ParticipantId)] =
    sql"""UPDATE result SET rank = $int4, scores = $scores, is_winner = $bool, forfeit = $bool
          WHERE game_id = $gameId AND participant_id = $participantId""".command

  // Every seat of every finished match this player is in, with its outcome.
  //
  // One query for the whole completed list rather than one per match: the UI shows the table on
  // each finished row, and asking per row would be a request per row. `mine` is the caller's own
  // seat, which is what scopes the list; `p` is everyone's, which is what fills the table.
  //
  // A LEFT JOIN onto result, so a participant the engine reported no result for is still a row
  // — see ParticipantResult. Ordered by rank within a match, unreported seats last, so the
  // winner comes first without the caller having to sort.
  private val selectResultsForPlayer: Query[
    PlayerId,
    (GameId, MatchId, ParticipantId, String, String, Option[Int], Option[Map[String, Any]], Option[Boolean], Option[Boolean])
  ] =
    sql"""SELECT p.game_id, p.match_id, p.participant_id, pl.nickname, gr.name, r.rank, r.scores, r.is_winner, r.forfeit
          FROM participant mine
          JOIN match m ON m.game_id = mine.game_id AND m.match_id = mine.match_id
          JOIN participant p ON p.game_id = m.game_id AND p.match_id = m.match_id
          JOIN player pl ON pl.player_id = p.player_id
          JOIN game_role gr ON gr.game_id = p.game_id AND gr.game_role_id = p.game_role_id
          LEFT JOIN result r ON r.game_id = p.game_id AND r.participant_id = p.participant_id
          WHERE mine.player_id = ${SkunkIdCodecs.playerId} AND ((m.completed IS NOT NULL) OR m.cancelled)
          ORDER BY p.match_id, r.rank ASC NULLS LAST, p.participant_id"""
      .query(gameId *: SkunkIdCodecs.matchId *: participantId *: text *: text *: int4.opt *: scores.opt *: bool.opt *: bool.opt)

  def listForPlayer(playerId: PlayerId): IO[List[ParticipantResult]] =
    session.execute(selectResultsForPlayer)(playerId).map(_.map {
      case (game, match_, id, nickname, roleName, rank, scores, isWinner, forfeit) =>
        ParticipantResult(
          game,
          match_,
          id,
          nickname,
          roleName,
          rank,
          scores.getOrElse(Map.empty),
          isWinner.getOrElse(false),
          forfeit.getOrElse(false)
        )
    })

  def create(result: Result): IO[Result] =
    session
      .execute(insertResult)(
        (result.gameId, result.participantId, result.rank, result.scores, result.isWinner, result.forfeit)
      )
      .as(result)

  def read(gameId: GameId, id: ParticipantId): IO[Option[Result]] =
    session.option(selectResult)((gameId, id)).map(_.map { case (rank, scores, isWinner, forfeit) =>
      Result(gameId, id, rank, scores, isWinner, forfeit)
    })

  def update(result: Result): IO[Unit] =
    session
      .execute(updateResult)(
        (result.rank, result.scores, result.isWinner, result.forfeit, result.gameId, result.participantId)
      )
      .void
}
