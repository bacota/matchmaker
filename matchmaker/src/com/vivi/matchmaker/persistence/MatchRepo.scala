package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._
import MatchRepo.{MatchClockRow, MatchSeatRow}

class MatchRepo(session: Session[IO]) {
  private val gameId = SkunkIdCodecs.gameId
  private val matchId = SkunkIdCodecs.matchId
  private val challengeId = SkunkIdCodecs.challengeId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb
  private val timeLimitKind = SkunkCodecs.timeLimitKind

  // time_limit is bound/read as a second count rather than via a custom INTERVAL codec.
  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertMatch: Command[
    (GameId, MatchId, ChallengeId, String, Option[Instant], Boolean, Instant, Option[Double], String, Boolean,
      Option[String], Option[String], Option[String], TimeLimitKind)
  ] =
    sql"""INSERT INTO match (game_id, match_id, challenge_id, description, completed, cancelled, start, time_limit,
                             settings, public, status_url, play_url, public_url, time_limit_kind)
          VALUES ($gameId, $matchId, $challengeId, $text, ${instant.opt}, $bool, $instant, ${float8.opt} * INTERVAL '1 second',
                  $settings, $bool, ${text.opt}, ${text.opt}, ${text.opt}, $timeLimitKind)""".command

  private type MatchRow =
    (ChallengeId, String, Option[Instant], Boolean, Instant, Option[Double], String, Boolean, Option[String],
      Option[String], Option[String], TimeLimitKind)

  private val matchRow: Codec[MatchRow] =
    challengeId *: text *: instant.opt *: bool *: instant *: float8.opt *: settings *: bool *: text.opt *: text.opt *:
      text.opt *: timeLimitKind

  private val selectMatch: Query[(GameId, MatchId), MatchRow] =
    sql"""SELECT challenge_id, description, completed, cancelled, start,
                 EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url, time_limit_kind
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId"""
      .query(matchRow)

  /* As selectMatch, but holding the row until the transaction ends. Used by the game-engine
   * callbacks, which read a match, decide from it, and write it back — a concurrent callback for
   * the same match would otherwise be able to interleave between the two. */
  private val selectMatchForUpdate: Query[(GameId, MatchId), MatchRow] =
    sql"""SELECT challenge_id, description, completed, cancelled, start,
                 EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url, time_limit_kind
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId FOR UPDATE"""
      .query(matchRow)

  // challenge_id is not updatable: a match is started from one challenge and stays that
  // challenge's match. Changing it would rewrite who created the match.
  private val updateMatch: Command[
    (String, Option[Instant], Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String],
      Option[String], TimeLimitKind, GameId, MatchId)
  ] =
    sql"""UPDATE match SET description = $text, completed = ${instant.opt}, cancelled = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool, status_url = ${text.opt}, play_url = ${text.opt}, public_url = ${text.opt},
          time_limit_kind = $timeLimitKind
          WHERE game_id = $gameId AND match_id = $matchId""".command

  def create(m: Match): IO[Match] =
    session
      .execute(insertMatch)(
        (m.gameId, m.matchId, m.challengeId, m.description, m.completedAt, m.cancelled, m.start, toSeconds(m.timeLimit),
          m.settings, m.isPublic, m.statusUrl, m.playUrl, m.publicUrl, m.timeLimitKind)
      )
      .as(m)

  private def toMatch(gameId: GameId, matchId: MatchId, row: MatchRow): Match = {
    val (challengeId, description, completedAt, cancelled, start, timeLimitSeconds, settings, isPublic, statusUrl,
      playUrl, publicUrl, timeLimitKind) = row
    Match(gameId, matchId, challengeId, description, completedAt, start, fromSeconds(timeLimitSeconds), settings,
      isPublic, cancelled, statusUrl, playUrl, publicUrl, timeLimitKind)
  }

  def read(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
    session.option(selectMatch)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

  /** As `read`, but locking the row for the rest of the transaction. */
  def readForUpdate(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
    session.option(selectMatchForUpdate)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

  def update(m: Match): IO[Unit] =
    session
      .execute(updateMatch)(
        (m.description, m.completedAt, m.cancelled, m.start, toSeconds(m.timeLimit), m.settings,
          m.isPublic, m.statusUrl, m.playUrl, m.publicUrl, m.timeLimitKind, m.gameId, m.matchId)
      )
      .void

  /* now() rather than a time bound from Scala: the completion time is a fact about when the
   * database recorded the match as over, and the application's clock is not the same clock. It
   * returns what was stored so the caller does not have to read the row back to find out. */
  private val completeMatch: Query[(GameId, MatchId), Instant] =
    sql"""UPDATE match SET completed = now()
          WHERE game_id = $gameId AND match_id = $matchId
          RETURNING completed"""
      .query(instant)

  /** Marks a match completed, as of the database's clock, and returns when that was.
    *
    * Overwrites an existing completion time, so callers that mean "complete it if it is not
    * already" must check first — `GameEngineService` does, under the row lock `readForUpdate`
    * takes, which is also what makes the read-then-write here safe.
    */
  def complete(gameId: GameId, matchId: MatchId): IO[Instant] =
    session.unique(completeMatch)((gameId, matchId))

  private val deleteMatch: Command[(GameId, MatchId)] =
    sql"DELETE FROM match WHERE game_id = $gameId AND match_id = $matchId".command

  /** Removes a match. Only used to undo a match whose game the engine failed to create — a
    * played match is completed, never deleted, and its participants would block this anyway.
    */
  def delete(gameId: GameId, matchId: MatchId): IO[Unit] =
    session.execute(deleteMatch)((gameId, matchId)).void

  private val playerId = SkunkIdCodecs.playerId

  // participant_id and character_id are decoded as raw int8 and wrapped below, because a
  // trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from outside
  // Ids.scala -- and the decoded tuple has to reduce to a plain tuple for the mapping to work.
  // character_id is nullable: a 'P'-type game's participant has no character_participant row.
  private val seatRow =
    gameId *: matchId *: text *: text *: instant.opt *: bool *: bool *: instant *: float8.opt *: timeLimitKind *:
      int8 *: int8.opt *: bool *: instant.opt *: text *: bool *: bool *: instant.opt

  private def toSeatRow(
      row: (GameId, MatchId, String, String, Option[Instant], Boolean, Boolean, Instant, Option[Double],
        TimeLimitKind, Long, Option[Long], Boolean, Option[Instant], String, Boolean, Boolean, Option[Instant])
  ): MatchSeatRow = {
    val (gameId, matchId, gameName, description, completedAt, cancelled, isCreator, start, timeLimitSeconds,
      timeLimitKind, callerParticipantId, callerCharacterId, callerPending, callerDue, seatNickname, seatPending,
      seatCompleted, seatDue) = row
    MatchSeatRow(
      gameId, matchId, gameName, description, completedAt, cancelled, isCreator, start,
      fromSeconds(timeLimitSeconds), timeLimitKind,
      ParticipantId(callerParticipantId), callerCharacterId.map(CharacterId.apply), callerPending, callerDue,
      seatNickname, seatPending, seatCompleted, seatDue
    )
  }

  /* The columns and joins every match list shares. Only the WHERE and the ORDER BY differ
   * between the three below, and they are written out in each rather than assembled from
   * fragments: a query that has to be pieced together to be read is harder to check against the
   * plan the database actually runs. */
  private val selectActiveForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.cancelled,
                 oc.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed, seat.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          -- The challenge the match was started from, which is never deleted: its challenger is
          -- the match's creator, and comparing them here is what tells this player whether the
          -- match is theirs to cancel.
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          -- Everyone in the match, the caller included. This is what multiplies the rows, and
          -- what lets a caller say who is playing and who is waited on without asking again.
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND m.completed IS NULL AND NOT m.cancelled
          -- Ordered by the caller's own deadline, most urgent first, with NULLS LAST so matches
          -- with no deadline do not crowd out ones that have one; then by seat, so a match's
          -- rows arrive together and in a stable order.
          ORDER BY p.due ASC NULLS LAST, m.start DESC, m.match_id, seat.participant_id"""
      .query(seatRow)

  /* Over, which a cancelled match is: it will never be played again and never gain a result, so
   * leaving it among the active ones would make cancel do nothing a player could see. It is
   * listed here instead, flagged, so that calling a match off does not erase it from the
   * creator's own history.
   *
   * Most recently finished first — a history read from the top. A cancelled match has no
   * completion time at all, so NULLS LAST puts those after the played-out ones rather than
   * ahead of everything, and `start` orders them among themselves. */
  private val selectOverForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.cancelled,
                 oc.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed, seat.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND (m.completed IS NOT NULL OR m.cancelled)
          ORDER BY m.completed DESC NULLS LAST, m.start DESC, m.match_id, seat.participant_id"""
      .query(seatRow)

  private val selectDueForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.cancelled,
                 oc.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed, seat.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND p.pending = true AND m.completed IS NULL AND m.cancelled = false
          ORDER BY p.due ASC NULLS LAST, m.start DESC, m.match_id, seat.participant_id"""
      .query(seatRow)

  /** Every match the player is in, either still running (`over = false`) or over — where over
    * means completed or cancelled, one row per seat.
    *
    * Two queries rather than one parameterized by `over`, because the two lists are read for
    * different reasons and are ordered differently: what is urgent first, or what finished most
    * recently first.
    */
  def listForPlayer(playerId: PlayerId, over: Boolean): IO[List[MatchSeatRow]] =
    session.execute(if (over) selectOverForPlayer else selectActiveForPlayer)(playerId).map(_.map(toSeatRow))

  /** The running matches in which it is this player's turn, one row per seat. */
  def listDueForPlayer(playerId: PlayerId): IO[List[MatchSeatRow]] =
    session.execute(selectDueForPlayer)(playerId).map(_.map(toSeatRow))

  /* Still its own query rather than more joined columns, because unlike whose turn it is this
   * really is an aggregate: a seat's balance is a sum over every turn it has taken, and joining
   * `turn` into the list above would multiply each match's rows by its every move for a caller
   * to add up again. One query for the whole list either way -- not one per match.
   *
   * The balance is the match's limit less the turns that seat has finished, over a LEFT JOIN so
   * a player who has not moved yet has their whole budget rather than no row. Restricted to
   * matches under a total limit: there is no balance to run down under a per-turn one.
   *
   * `due` comes along so the caller can tell the player on the clock from the rest -- theirs is
   * the balance that is being spent as it is read. */
  private val selectClocksForPlayer: Query[PlayerId, (GameId, MatchId, String, Double, Option[Instant])] =
    sql"""SELECT p.game_id, p.match_id, pl.nickname,
                 EXTRACT(EPOCH FROM (m.time_limit - coalesce(sum(GREATEST(t.taken_at - t.started_at, INTERVAL '0')), INTERVAL '0')))::float8,
                 p.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN turn t ON t.game_id = p.game_id AND t.participant_id = p.participant_id
            AND m.time_limit IS NOT NULL AND m.time_limit_kind = 'TOTAL'
            AND EXISTS (SELECT 1 FROM participant mine
                         WHERE mine.game_id = p.game_id AND mine.match_id = p.match_id
                           AND mine.player_id = $playerId)
          GROUP BY p.game_id, p.match_id, p.participant_id, pl.nickname, p.due, m.time_limit
          ORDER BY p.participant_id"""
      .query(gameId *: matchId *: text *: float8 *: instant.opt)

  /** Every seat's remaining budget, across the caller's running chess-clock matches. */
  def clocksForPlayer(playerId: PlayerId): IO[List[MatchClockRow]] =
    session.execute(selectClocksForPlayer)(playerId).map(_.map { case (gameId, matchId, nickname, seconds, due) =>
      MatchClockRow(gameId, matchId, nickname, Duration.ofMillis((seconds * 1000).toLong), due)
    })
}

object MatchRepo {

/* One (match, seat) pair, which is what a list of matches comes back as.
 *
 * The queries below join `participant` twice: once as `p`, the caller's own seat, which is
 * what scopes the list and carries the facts that are theirs alone (their deadline, whether
 * they are pending, which character they are playing); and once as `seat`, which is every
 * player in the match including them. So a two-player match is two rows, both carrying the
 * same match and the same caller.
 *
 * That is deliberately more rows than a caller wants, and the caller is expected to fold them
 * into one summary per match -- MatchService.summarise does it. The alternative, and what
 * this replaced, was a scalar subquery per derived column: one to aggregate the nicknames of
 * whoever is pending, another to take the earliest of their deadlines. Each new question about
 * the other seats wanted another subquery, each was a rule about what a match means written in
 * SQL, and the nicknames had to be joined into one string and split apart again because a
 * column cannot be a list. A join says only what the rows are; what they mean is Scala's to
 * decide, where it can be read and tested as ordinary code.
 *
 * The rows arrive in the order the summaries want, and every row of one match is adjacent to
 * its siblings -- each query orders by its own ordering columns first and by `seat` within
 * them -- so the fold preserves that order without having to sort again. */
case class MatchSeatRow(
    gameId: GameId,
    matchId: MatchId,
    gameName: String,
    description: String,
    completedAt: Option[Instant],
    cancelled: Boolean,
    isCreator: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    timeLimitKind: TimeLimitKind,
    // The caller's own seat, repeated on every row of the match.
    callerParticipantId: ParticipantId,
    callerCharacterId: Option[CharacterId],
    callerPending: Boolean,
    callerDue: Option[Instant],
    // The seat this row is about, which may be the caller's own or anyone else's.
    seatNickname: String,
    seatPending: Boolean,
    seatCompleted: Boolean,
    seatDue: Option[Instant]
)

/** What one seat has left of a chess-clock budget. */
case class MatchClockRow(gameId: GameId, matchId: MatchId, nickname: String, remaining: Duration, due: Option[Instant])
}
