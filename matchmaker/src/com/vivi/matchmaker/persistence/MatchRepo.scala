package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

class MatchRepo(session: Session[IO]) {
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = varchar

  // time_limit is bound/read as a second count rather than via a custom INTERVAL codec.
  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertMatch: Command[(Int, String, String, Boolean, Instant, Option[Double], String)] =
    sql"""INSERT INTO match (game_id, match_id, description, completed, start, time_limit, settings)
          VALUES ($int4, $varchar, $varchar, $bool, $instant, ${float8.opt} * INTERVAL '1 second', $settings::jsonb)""".command

  private val insertPlayerMatchJoin: Command[(Int, String)] =
    sql"INSERT INTO player_match (game_id, match_id) VALUES ($int4, $varchar)".command

  private val insertCharacterMatchJoin: Command[(Int, String)] =
    sql"INSERT INTO character_match (game_id, match_id) VALUES ($int4, $varchar)".command

  private val selectMatch: Query[(Int, String), (String, Boolean, Instant, Option[Double], String, Boolean)] =
    sql"""SELECT m.description, m.completed, m.start, EXTRACT(EPOCH FROM m.time_limit), m.settings,
                 (pm.game_id IS NOT NULL) AS is_player_match
          FROM match m
          LEFT JOIN player_match pm ON pm.game_id = m.game_id AND pm.match_id = m.match_id
          WHERE m.game_id = $int4 AND m.match_id = $varchar"""
      .query(varchar *: bool *: instant *: float8.opt *: settings *: bool)

  private val updateMatch: Command[(String, Boolean, Instant, Option[Double], String, Int, String)] =
    sql"""UPDATE match SET description = $varchar, completed = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings::jsonb
          WHERE game_id = $int4 AND match_id = $varchar""".command

  def create(m: Match): IO[Match] =
    for {
      _ <- session.execute(insertMatch)((m.gameId, m.matchId, m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings))
      _ <- m match {
        case _: PlayerMatch    => session.execute(insertPlayerMatchJoin)((m.gameId, m.matchId))
        case _: CharacterMatch => session.execute(insertCharacterMatchJoin)((m.gameId, m.matchId))
      }
    } yield m

  def read(gameId: Int, matchId: String): IO[Option[Match]] =
    session.option(selectMatch)((gameId, matchId)).map(_.map {
      case (description, completed, start, timeLimitSeconds, settings, isPlayerMatch) =>
        val timeLimit = fromSeconds(timeLimitSeconds)
        if (isPlayerMatch) PlayerMatch(gameId, matchId, description, completed, start, timeLimit, settings)
        else CharacterMatch(gameId, matchId, description, completed, start, timeLimit, settings)
    })

  def update(m: Match): IO[Unit] =
    session
      .execute(updateMatch)(
        (m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings, m.gameId, m.matchId)
      )
      .void
}
