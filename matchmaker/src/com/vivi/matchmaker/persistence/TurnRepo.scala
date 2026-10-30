package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import skunk.data.Arr
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model.{GameId, MatchId, ParticipantId, Turn}

/** The turns taken in a match: one row per move, with what it cost the player who made it.
  *
  * Written from two places for the same fact — the move callback as it arrives, and the status call that repairs a
  * callback which did not — so every insert here is idempotent.
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
        sql"""INSERT INTO turn AS t (game_id, match_id, participant_id, taken_at, started_at)
          VALUES ($gameId, $matchId, $participantId, $instant, $instant)
          ON CONFLICT (game_id, participant_id, taken_at)
          DO UPDATE SET started_at = EXCLUDED.started_at
          WHERE EXCLUDED.started_at > t.started_at""".command

    /** Records a turn, or does nothing if that turn is already recorded. */
    def create(turn: Turn): IO[Unit] =
        session
            .execute(insertTurn)((turn.gameId, turn.matchId, turn.participantId, turn.takenAt, turn.startedAt))
            .void

    // As `insertTurn`, for a whole history in one statement rather than a round trip a turn.
    private val insertTurns: Command[(GameId, MatchId, Arr[Long], Arr[Instant], Arr[Instant])] =
        sql"""INSERT INTO turn AS t (game_id, match_id, participant_id, taken_at, started_at)
          SELECT $gameId, $matchId, u.participant_id, u.taken_at, u.started_at
          FROM unnest(${_int8}, ${SkunkCodecs.instantArray}, ${SkunkCodecs.instantArray})
              AS u (participant_id, taken_at, started_at)
          ON CONFLICT (game_id, participant_id, taken_at)
          DO UPDATE SET started_at = EXCLUDED.started_at
          WHERE EXCLUDED.started_at > t.started_at""".command

    /** Records several turns of a match at once, each as [[create]] records one.
      *
      * A turn given twice is written once, with the later of its starts: one statement cannot touch a row twice.
      */
    def createAll(gameId: GameId, matchId: MatchId, turns: List[Turn]): IO[Unit] = {
        val distinct = turns.groupBy(t => (t.participantId, t.takenAt)).values.map(_.maxBy(_.startedAt)).toList
        IO.whenA(distinct.nonEmpty)(
          session
              .execute(insertTurns)(
                (
                  gameId,
                  matchId,
                  Arr(distinct.map(_.participantId.value)*),
                  Arr(distinct.map(_.takenAt)*),
                  Arr(distinct.map(_.startedAt)*)
                )
              )
              .void
        )
    }

    private val selectForMatch: Query[(GameId, MatchId), (ParticipantId, Instant, Instant)] =
        sql"""SELECT participant_id, taken_at, started_at FROM turn
          WHERE game_id = $gameId AND match_id = $matchId
          ORDER BY taken_at"""
            .query(participantId *: instant *: instant)

    def listForMatch(gameId: GameId, matchId: MatchId): IO[List[Turn]] =
        session
            .execute(selectForMatch)((gameId, matchId))
            .map(_.map { case (id, takenAt, startedAt) =>
                Turn(gameId = gameId, matchId = matchId, participantId = id, takenAt = takenAt, startedAt = startedAt)
            })

    /* Summed in the database rather than by folding the rows in Scala: what is wanted is one
     * number per seat, and a match played to a long end has no reason to cross the wire move by
     * move every time somebody looks at it. Seconds as a float8, the same way `time_limit` is
     * read — skunk has no INTERVAL codec here either. */
    private val selectTimeUsed: Query[(GameId, MatchId), (ParticipantId, Double)] =
        sql"""SELECT participant_id, EXTRACT(EPOCH FROM sum(GREATEST(taken_at - started_at, INTERVAL '0')))::float8
          FROM turn
          WHERE game_id = $gameId AND match_id = $matchId
          GROUP BY participant_id"""
            .query(participantId *: float8)

    /** How long each participant has spent on the turns they have finished, by seat.
      *
      * The turn a player is in the middle of is not in here — it is not a turn until it is taken — so this is what they
      * had spent when their current turn began, which is exactly what their deadline for it is computed from. A seat
      * that has never moved is absent rather than zero; callers read it through `getOrElse(Duration.ZERO)`.
      */
    def timeUsed(gameId: GameId, matchId: MatchId): IO[Map[ParticipantId, Duration]] =
        session
            .execute(selectTimeUsed)((gameId, matchId))
            .map(_.map { case (id, seconds) =>
                id -> Duration.ofMillis((seconds * 1000).toLong)
            }.toMap)

    private val deleteForMatch: Command[(GameId, MatchId)] =
        sql"DELETE FROM turn WHERE game_id = $gameId AND match_id = $matchId".command

    /** Removes a match's turns. Only for undoing a match the engine failed to create, alongside the participants that
      * would otherwise block its deletion.
      */
    def deleteForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
        session.execute(deleteForMatch)((gameId, matchId)).void
}
