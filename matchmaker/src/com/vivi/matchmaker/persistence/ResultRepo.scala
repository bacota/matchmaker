package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Duration
import com.vivi.matchmaker.model.{GameId, MatchId, ParticipantId, PlayerId, Result}
import ResultRepo.{ParticipantResultRow, TimeTakenRow}

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
    (GameId, MatchId, ParticipantId, String, String, Option[Int], Option[Map[String, Any]], Option[Boolean],
      Option[Boolean])
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
      .query(
        gameId *: SkunkIdCodecs.matchId *: participantId *: text *: text *: int4.opt *: scores.opt *: bool.opt *:
          bool.opt
      )

  /** Every seat of every finished match this player is in, with its outcome — one row per seat,
    * the winner of each match first.
    *
    * What a seat *spent* is not here: see [[timeTakenForPlayer]], which is the other half of a
    * result row and is asked for separately.
    */
  def listForPlayer(playerId: PlayerId): IO[List[ParticipantResultRow]] =
    session.execute(selectResultsForPlayer)(playerId).map(_.map {
      case (game, match_, id, nickname, roleName, rank, scores, isWinner, forfeit) =>
        ParticipantResultRow(
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

  /* How long each seat spent over its turns, across the caller's finished matches.
   *
   * Its own query rather than a column of the one above, and an aggregate rather than a join,
   * for the same reason `MatchRepo.clocksForPlayer` is: this is a sum over every turn a seat has
   * taken, and joining `turn` into the result list would multiply each seat's row by its every
   * move for the caller to add up again — a long match would cross the wire once per move, per
   * seat, per time anybody opened their history.
   *
   * Seats with no turns recorded are simply absent; the caller reads it through
   * `getOrElse(Duration.ZERO)`, which is the right answer both for a player who never moved and
   * for a match played before turns were recorded. GREATEST clamps a turn the engine reported as
   * taken before it started, matching `Turn.elapsed`: two clocks disagreeing is not a refund. */
  private val selectTimeTakenForPlayer: Query[PlayerId, (GameId, ParticipantId, Double)] =
    sql"""SELECT t.game_id, t.participant_id,
                 EXTRACT(EPOCH FROM sum(GREATEST(t.taken_at - t.started_at, INTERVAL '0')))::float8
          FROM turn t
          JOIN participant p ON p.game_id = t.game_id AND p.participant_id = t.participant_id
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          WHERE ((m.completed IS NOT NULL) OR m.cancelled)
            AND EXISTS (SELECT 1 FROM participant mine
                         WHERE mine.game_id = m.game_id AND mine.match_id = m.match_id
                           AND mine.player_id = ${SkunkIdCodecs.playerId})
          GROUP BY t.game_id, t.participant_id"""
      .query(gameId *: participantId *: float8)

  /** What each seat of the caller's finished matches spent, by seat. */
  def timeTakenForPlayer(playerId: PlayerId): IO[List[TimeTakenRow]] =
    session.execute(selectTimeTakenForPlayer)(playerId).map(_.map { case (gameId, participantId, seconds) =>
      TimeTakenRow(gameId, participantId, Duration.ofMillis((seconds * 1000).toLong))
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

object ResultRepo {

  /** One line of a finished match's result table as the join returns it: who played, in which
    * role, and how they did.
    *
    * Everything here is a column of a row that exists — the reading of them, and the time each
    * seat spent, are put together into a [[com.vivi.matchmaker.model.ParticipantResult]] by the
    * service.
    */
  case class ParticipantResultRow(
      gameId: GameId,
      matchId: MatchId,
      participantId: ParticipantId,
      nickname: String,
      roleName: String,
      rank: Option[Int],
      scores: Map[String, Any],
      isWinner: Boolean,
      forfeit: Boolean
  )

  /** What one seat spent over its turns, all told. */
  case class TimeTakenRow(gameId: GameId, participantId: ParticipantId, timeTaken: Duration)
}
