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

  private val insertMatch: Command[(GameId, MatchId, String, Boolean, Instant, Option[Double], String)] =
    sql"""INSERT INTO match (game_id, match_id, description, completed, start, time_limit, settings)
          VALUES ($gameId, $matchId, $text, $bool, $instant, ${float8.opt} * INTERVAL '1 second', $settings)""".command

  private val selectMatch: Query[(GameId, MatchId), (String, Boolean, Instant, Option[Double], String)] =
    sql"""SELECT description, completed, start, EXTRACT(EPOCH FROM time_limit)::float8, settings
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId"""
      .query(text *: bool *: instant *: float8.opt *: settings)

  private val updateMatch: Command[(String, Boolean, Instant, Option[Double], String, GameId, MatchId)] =
    sql"""UPDATE match SET description = $text, completed = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings
          WHERE game_id = $gameId AND match_id = $matchId""".command

  def create(m: CharacterMatch): IO[CharacterMatch] =
    session
      .execute(insertMatch)((m.gameId, m.matchId, m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings))
      .as(m)

  def read(gameId: GameId, matchId: MatchId): IO[Option[CharacterMatch]] =
    session.option(selectMatch)((gameId, matchId)).map(_.map {
      case (description, completed, start, timeLimitSeconds, settings) =>
        CharacterMatch(gameId, matchId, description, completed, start, fromSeconds(timeLimitSeconds), settings)
    })

  def update(m: CharacterMatch): IO[Unit] =
    session
      .execute(updateMatch)((m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings, m.gameId, m.matchId))
      .void
}
