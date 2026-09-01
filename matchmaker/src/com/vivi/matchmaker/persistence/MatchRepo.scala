package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

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

  // participant_id and character_id are decoded as raw int8 and wrapped in `toSummary`, and
  // `pending` is selected last, so that the twiddle ends in a concrete type: one ending in an
  // opaque id does not reduce to a tuple, because an opaque type cannot be shown to be disjoint
  // from Tuple outside the scope that defines it. character_id is nullable: a 'P'-type game's
  // participant has no character_participant row at all.
  private val summaryColumns =
    gameId *: matchId *: text *: text *: instant.opt *: bool *: bool *: instant *: instant.opt *: int8 *: int8.opt *:
      bool *: float8.opt *: timeLimitKind *: text *: instant.opt

  private def toSummary(
      row: (GameId, MatchId, String, String, Option[Instant], Boolean, Boolean, Instant, Option[Instant], Long,
        Option[Long], Boolean, Option[Double], TimeLimitKind, String, Option[Instant])
  ): MatchSummary = {
    val (gameId, matchId, gameName, description, completedAt, cancelled, isCreator, start, due, participantId,
      characterId, pending, timeLimitSeconds, timeLimitKind, whoseTurn, turnDue) = row
    MatchSummary(
      gameId,
      matchId,
      gameName,
      description,
      completedAt,
      cancelled,
      isCreator,
      start,
      due,
      pending,
      ParticipantId(participantId),
      characterId.map(CharacterId.apply),
      fromSeconds(timeLimitSeconds),
      timeLimitKind,
      // Split back out of the aggregate below. A nickname containing a newline would divide
      // into two here; it would also be unrenderable in a one-line list, so it is a problem to
      // refuse at registration rather than to encode around in every query that lists one.
      whoseTurn.split('\n').filter(_.nonEmpty).toSeq,
      turnDue
    )
  }

  // Still being played: neither completed nor called off. Ordered by the caller's own deadline,
  // most urgent first, with NULLS LAST so matches with no deadline do not crowd out ones that
  // have one.
  private val selectActiveForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.cancelled,
                 oc.challenger = p.player_id, m.start,
                 p.due, p.participant_id, cp.character_id, p.pending,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 -- Whose turn it is, and when that turn runs out: two scalar subqueries over
                 -- everyone's participant row rather than a second join, which would multiply
                 -- the rows instead of summarising them. `p` above is the caller's own seat;
                 -- these are about the match.
                 (SELECT coalesce(string_agg(turn_player.nickname, E'\n' ORDER BY turn_seat.participant_id), '')
                    FROM participant turn_seat
                    JOIN player turn_player ON turn_player.player_id = turn_seat.player_id
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed),
                 -- The earliest, so a game where several move at once counts down to the first
                 -- clock to run out, which is the first one anything happens on.
                 (SELECT min(turn_seat.due)
                    FROM participant turn_seat
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed)
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          -- The challenge the match was started from, which is never deleted: its challenger is
          -- the match's creator, and comparing them here is what tells this player whether the
          -- match is theirs to cancel.
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.player_id = $playerId AND m.completed IS NULL AND NOT m.cancelled
          ORDER BY p.due ASC NULLS LAST, m.start DESC"""
      .query(summaryColumns)

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
                 p.due, p.participant_id, cp.character_id, p.pending,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 -- Whose turn it is, and when that turn runs out: two scalar subqueries over
                 -- everyone's participant row rather than a second join, which would multiply
                 -- the rows instead of summarising them. `p` above is the caller's own seat;
                 -- these are about the match.
                 (SELECT coalesce(string_agg(turn_player.nickname, E'\n' ORDER BY turn_seat.participant_id), '')
                    FROM participant turn_seat
                    JOIN player turn_player ON turn_player.player_id = turn_seat.player_id
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed),
                 -- The earliest, so a game where several move at once counts down to the first
                 -- clock to run out, which is the first one anything happens on.
                 (SELECT min(turn_seat.due)
                    FROM participant turn_seat
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed)
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.player_id = $playerId AND (m.completed IS NOT NULL OR m.cancelled)
          ORDER BY m.completed DESC NULLS LAST, m.start DESC"""
      .query(summaryColumns)

  private val selectDueForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.cancelled,
                 oc.challenger = p.player_id, m.start,
                 p.due, p.participant_id, cp.character_id, p.pending,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind,
                 -- Whose turn it is, and when that turn runs out: two scalar subqueries over
                 -- everyone's participant row rather than a second join, which would multiply
                 -- the rows instead of summarising them. `p` above is the caller's own seat;
                 -- these are about the match.
                 (SELECT coalesce(string_agg(turn_player.nickname, E'\n' ORDER BY turn_seat.participant_id), '')
                    FROM participant turn_seat
                    JOIN player turn_player ON turn_player.player_id = turn_seat.player_id
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed),
                 -- The earliest, so a game where several move at once counts down to the first
                 -- clock to run out, which is the first one anything happens on.
                 (SELECT min(turn_seat.due)
                    FROM participant turn_seat
                   WHERE turn_seat.game_id = m.game_id AND turn_seat.match_id = m.match_id
                     AND turn_seat.pending AND NOT turn_seat.completed)
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          -- The challenge the match was started from, which is never deleted: its challenger is
          -- the match's creator, and comparing them here is what tells this player whether the
          -- match is theirs to cancel.
          JOIN open_challenge oc ON oc.game_id = m.game_id AND oc.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.player_id = $playerId AND p.pending = true AND m.completed IS NULL AND m.cancelled = false
          ORDER BY p.due ASC NULLS LAST, m.start DESC"""
      .query(summaryColumns)

  /* What each seat has left of a chess-clock budget, across the caller's running matches.
   *
   * A second query rather than more columns on the summary, because this is a list per match
   * rather than another fact about one: a row of the summary is one seat's, and these are all of
   * them. One query for the whole list either way — not one per match.
   *
   * The balance is the match's limit less the turns that seat has finished, which is a LEFT JOIN
   * so a player who has not moved yet has their whole budget rather than no row. Restricted to
   * matches under a total limit: there is no balance to run down under a per-turn one.
   *
   * `due` comes along so the caller can tell the player on the clock from the rest — theirs is
   * the balance that is being spent as it is read. */
  private val selectClocksForPlayer: Query[PlayerId, (GameId, MatchId, String, Double, Option[Instant])] =
    sql"""SELECT p.game_id, p.match_id, pl.nickname,
                 EXTRACT(EPOCH FROM (m.time_limit - coalesce(sum(t.taken_at - t.started_at), INTERVAL '0')))::float8,
                 p.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN turn t ON t.game_id = p.game_id AND t.participant_id = p.participant_id
          WHERE m.completed IS NULL AND NOT m.cancelled
            AND m.time_limit IS NOT NULL AND m.time_limit_kind = 'TOTAL'
            AND EXISTS (SELECT 1 FROM participant mine
                         WHERE mine.game_id = p.game_id AND mine.match_id = p.match_id
                           AND mine.player_id = $playerId)
          GROUP BY p.game_id, p.match_id, p.participant_id, pl.nickname, p.due, m.time_limit
          ORDER BY p.participant_id"""
      .query(gameId *: matchId *: text *: float8 *: instant.opt)

  /** Every match the player is in, either still running (`over = false`) or over — where over
    * means completed or cancelled. Two queries rather than one parameterized by `over`, because
    * the two lists are read for different reasons and are ordered differently: what is urgent
    * first, or what finished most recently first.
    *
    * A running match under a chess clock also carries what every seat has left. The over list
    * does not: a finished match's clocks are not something anybody can spend.
    */
  def listForPlayer(playerId: PlayerId, over: Boolean): IO[List[MatchSummary]] =
    session.execute(if (over) selectOverForPlayer else selectActiveForPlayer)(playerId).map(_.map(toSummary)).flatMap {
      summaries =>
        // Asked for at all only when one of the matches is played under a chess clock, which
        // most are not.
        if (over || !summaries.exists(s => s.timeLimit.isDefined && s.timeLimitKind == TimeLimitKind.Total))
          IO.pure(summaries)
        else withClocks(playerId, summaries)
    }

  private def withClocks(playerId: PlayerId, summaries: List[MatchSummary]): IO[List[MatchSummary]] =
    session.execute(selectClocksForPlayer)(playerId).map { rows =>
      val byMatch = rows
        .groupBy((gameId, matchId, _, _, _) => (gameId, matchId))
        .view
        .mapValues(_.map { case (_, _, nickname, seconds, due) =>
          PlayerClock(nickname, Duration.ofMillis((seconds * 1000).toLong), due)
        })
        .toMap
      summaries.map(s => byMatch.get((s.gameId, s.matchId)).fold(s)(clocks => s.copy(clocks = clocks)))
    }

  /** The running matches in which it is this player's turn. */
  def listDueForPlayer(playerId: PlayerId): IO[List[MatchSummary]] =
    session.execute(selectDueForPlayer)(playerId).map(_.map(toSummary))
}
