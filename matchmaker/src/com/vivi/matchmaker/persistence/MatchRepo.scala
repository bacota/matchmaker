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
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  // time_limit is bound/read as a second count rather than via a custom INTERVAL codec.
  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertMatch
      : Command[(GameId, MatchId, String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String])] =
    sql"""INSERT INTO match (game_id, match_id, description, completed, start, time_limit, settings,
                             public, status_url, play_url, public_url)
          VALUES ($gameId, $matchId, $text, $bool, $instant, ${float8.opt} * INTERVAL '1 second', $settings,
                  $bool, ${text.opt}, ${text.opt}, ${text.opt})""".command

  private val matchRow: Codec[(String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String])] =
    text *: bool *: instant *: float8.opt *: settings *: bool *: text.opt *: text.opt *: text.opt

  private val selectMatch: Query[
    (GameId, MatchId),
    (String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String])
  ] =
    sql"""SELECT description, completed, start, EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId"""
      .query(matchRow)

  /* As selectMatch, but holding the row until the transaction ends. Used by the game-engine
   * callbacks, which read a match, decide from it, and write it back — a concurrent callback for
   * the same match would otherwise be able to interleave between the two. */
  private val selectMatchForUpdate: Query[
    (GameId, MatchId),
    (String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String])
  ] =
    sql"""SELECT description, completed, start, EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId FOR UPDATE"""
      .query(matchRow)

  private val updateMatch: Command[
    (String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String], GameId, MatchId)
  ] =
    sql"""UPDATE match SET description = $text, completed = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool, status_url = ${text.opt}, play_url = ${text.opt}, public_url = ${text.opt}
          WHERE game_id = $gameId AND match_id = $matchId""".command

  def create(m: Match): IO[Match] =
    session
      .execute(insertMatch)(
        (m.gameId, m.matchId, m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings,
          m.isPublic, m.statusUrl, m.playUrl, m.publicUrl)
      )
      .as(m)

  private def toMatch(
      gameId: GameId,
      matchId: MatchId,
      row: (String, Boolean, Instant, Option[Double], String, Boolean, Option[String], Option[String], Option[String])
  ): Match = {
    val (description, completed, start, timeLimitSeconds, settings, isPublic, statusUrl, playUrl, publicUrl) = row
    Match(gameId, matchId, description, completed, start, fromSeconds(timeLimitSeconds), settings, isPublic, statusUrl, playUrl, publicUrl)
  }

  def read(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
    session.option(selectMatch)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

  /** As `read`, but locking the row for the rest of the transaction. */
  def readForUpdate(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
    session.option(selectMatchForUpdate)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

  def update(m: Match): IO[Unit] =
    session
      .execute(updateMatch)(
        (m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings,
          m.isPublic, m.statusUrl, m.playUrl, m.publicUrl, m.gameId, m.matchId)
      )
      .void

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
    gameId *: matchId *: text *: text *: bool *: instant *: instant.opt *: int8 *: int8.opt *: bool

  private def toSummary(
      row: (GameId, MatchId, String, String, Boolean, Instant, Option[Instant], Long, Option[Long], Boolean)
  ): MatchSummary = {
    val (gameId, matchId, gameName, description, completed, start, due, participantId, characterId, pending) = row
    MatchSummary(
      gameId,
      matchId,
      gameName,
      description,
      completed,
      start,
      due,
      pending,
      ParticipantId(participantId),
      characterId.map(CharacterId.apply)
    )
  }

  // Ordering puts the most urgent first for due lists and the most recent first for history;
  // NULLS LAST keeps matches with no deadline from crowding out ones that have a deadline.
  private val selectForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.start,
                 p.due, p.participant_id, cp.character_id, p.pending
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.player_id = $playerId AND m.completed = $bool
          ORDER BY p.due ASC NULLS LAST, m.start DESC"""
      .query(summaryColumns)

  private val selectDueForPlayer =
    sql"""SELECT m.game_id, m.match_id, g.name, m.description, m.completed, m.start,
                 p.due, p.participant_id, cp.character_id, p.pending
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.player_id = $playerId AND p.pending = true AND m.completed = false
          ORDER BY p.due ASC NULLS LAST, m.start DESC"""
      .query(summaryColumns)

  /** Every match the player is in, either still running (`completed = false`) or finished. */
  def listForPlayer(playerId: PlayerId, completed: Boolean): IO[List[MatchSummary]] =
    session.execute(selectForPlayer)((playerId, completed)).map(_.map(toSummary))

  /** The running matches in which it is this player's turn. */
  def listDueForPlayer(playerId: PlayerId): IO[List[MatchSummary]] =
    session.execute(selectDueForPlayer)(playerId).map(_.map(toSummary))
}
