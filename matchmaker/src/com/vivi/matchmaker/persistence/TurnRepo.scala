package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model.{GameId, MatchId, ParticipantId, Turn}

/** The turns taken in a match: one row per move, with what it cost the player who made it.
  *
  * Written from two places for the same fact — the move callback as it arrives, and the status
  * call that repairs a callback which did not — so every insert here is idempotent.
  */
class TurnRepo(session: Session[IO]) {
  private val gameId = SkunkIdCodecs.gameId
  private val matchId = SkunkIdCodecs.matchId
  private val participantId = SkunkIdCodecs.participantId
  private val instant = SkunkCodecs.instant

  /* ON CONFLICT DO NOTHING is the whole idempotency story, and it is the database's rather than
   * the caller's: the same turn is normally reported twice, and a read-then-insert would still
   * let two of them through. */
  private val insertTurn: Command[(GameId, MatchId, ParticipantId, Instant, Instant)] =
    sql"""INSERT INTO turn (game_id, match_id, participant_id, taken_at, started_at)
          VALUES ($gameId, $matchId, $participantId, $instant, $instant)
          ON CONFLICT (game_id, participant_id, taken_at) DO NOTHING""".command

  /** Records a turn, or does nothing if that turn is already recorded. */
  def create(turn: Turn): IO[Unit] =
    session
      .execute(insertTurn)((turn.gameId, turn.matchId, turn.participantId, turn.takenAt, turn.startedAt))
      .void

  private val selectLatest: Query[(GameId, MatchId), Instant] =
    sql"""SELECT taken_at FROM turn WHERE game_id = $gameId AND match_id = $matchId
          ORDER BY taken_at DESC LIMIT 1"""
      .query(instant)

  /** When the most recent turn matchmaker knows about in this match was taken.
    *
    * This is what the engine is asked to report past, so that a status call carries back the
    * turns matchmaker missed rather than every turn of the match. `None` means none are recorded
    * — a match that has not been moved in, or one whose first callback is the one that was lost.
    */
  def latestTakenAt(gameId: GameId, matchId: MatchId): IO[Option[Instant]] =
    session.option(selectLatest)((gameId, matchId))

  private val selectForMatch: Query[(GameId, MatchId), (ParticipantId, Instant, Instant)] =
    sql"""SELECT participant_id, taken_at, started_at FROM turn
          WHERE game_id = $gameId AND match_id = $matchId
          ORDER BY taken_at"""
      .query(participantId *: instant *: instant)

  def listForMatch(gameId: GameId, matchId: MatchId): IO[List[Turn]] =
    session.execute(selectForMatch)((gameId, matchId)).map(_.map { case (id, takenAt, startedAt) =>
      Turn(gameId, matchId, id, takenAt, startedAt)
    })

  /* Summed in the database rather than by folding the rows in Scala: what is wanted is one
   * number per seat, and a match played to a long end has no reason to cross the wire move by
   * move every time somebody looks at it. Seconds as a float8, the same way `time_limit` is
   * read — skunk has no INTERVAL codec here either. */
  private val selectTimeUsed: Query[(GameId, MatchId), (ParticipantId, Double)] =
    sql"""SELECT participant_id, EXTRACT(EPOCH FROM sum(taken_at - started_at))::float8
          FROM turn
          WHERE game_id = $gameId AND match_id = $matchId
          GROUP BY participant_id"""
      .query(participantId *: float8)

  /** How long each participant has spent on the turns they have finished, by seat.
    *
    * The turn a player is in the middle of is not in here — it is not a turn until it is taken —
    * so this is what they had spent when their current turn began, which is exactly what their
    * deadline for it is computed from. A seat that has never moved is absent rather than zero;
    * callers read it through `getOrElse(Duration.ZERO)`.
    */
  def timeUsed(gameId: GameId, matchId: MatchId): IO[Map[ParticipantId, Duration]] =
    session.execute(selectTimeUsed)((gameId, matchId)).map(_.map { case (id, seconds) =>
      id -> Duration.ofMillis((seconds * 1000).toLong)
    }.toMap)

  private val deleteForMatch: Command[(GameId, MatchId)] =
    sql"DELETE FROM turn WHERE game_id = $gameId AND match_id = $matchId".command

  /** Removes a match's turns. Only for undoing a match the engine failed to create, alongside
    * the participants that would otherwise block its deletion.
    */
  def deleteForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
    session.execute(deleteForMatch)((gameId, matchId)).void
}
