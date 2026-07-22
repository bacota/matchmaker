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
  private val gameId = SkunkIdCodecs.gameId
  private val matchId = SkunkIdCodecs.matchId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = text

  // time_limit is bound/read as a second count rather than via a custom INTERVAL codec.
  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertMatch: Command[(GameId, MatchId, String, Boolean, Instant, Option[Double], String)] =
    sql"""INSERT INTO match (game_id, match_id, description, completed, start, time_limit, settings)
          VALUES ($gameId, $matchId, $text, $bool, $instant, ${float8.opt} * INTERVAL '1 second', $settings::jsonb)""".command

  private val insertPlayerMatchJoin: Command[(GameId, MatchId)] =
    sql"INSERT INTO player_match (game_id, match_id) VALUES ($gameId, $matchId)".command

  private val insertCharacterMatchJoin: Command[(GameId, MatchId)] =
    sql"INSERT INTO character_match (game_id, match_id) VALUES ($gameId, $matchId)".command

  private val selectMatch: Query[(GameId, MatchId), (String, Boolean, Instant, Option[Double], String, Boolean)] =
    sql"""SELECT m.description, m.completed, m.start, EXTRACT(EPOCH FROM m.time_limit), m.settings,
                 (pm.game_id IS NOT NULL) AS is_player_match
          FROM match m
          LEFT JOIN player_match pm ON pm.game_id = m.game_id AND pm.match_id = m.match_id
          WHERE m.game_id = $gameId AND m.match_id = $matchId"""
      .query(text *: bool *: instant *: float8.opt *: settings *: bool)

  private val updateMatch: Command[(String, Boolean, Instant, Option[Double], String, GameId, MatchId)] =
    sql"""UPDATE match SET description = $text, completed = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings::jsonb
          WHERE game_id = $gameId AND match_id = $matchId""".command

  def create(m: Match): IO[Match] =
    session.transaction.use { _ =>
      for {
        _ <- session.execute(insertMatch)((m.gameId, m.matchId, m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings))
        _ <- m match {
          case _: PlayerMatch    => session.execute(insertPlayerMatchJoin)((m.gameId, m.matchId))
          case _: CharacterMatch => session.execute(insertCharacterMatchJoin)((m.gameId, m.matchId))
        }
      } yield m
    }

  def read(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
    session.option(selectMatch)((gameId, matchId)).map(_.map {
      case (description, completed, start, timeLimitSeconds, settings, isPlayerMatch) =>
        val timeLimit = fromSeconds(timeLimitSeconds)
        if (isPlayerMatch) PlayerMatch(gameId, matchId, description, completed, start, timeLimit, settings)
        else CharacterMatch(gameId, matchId, description, completed, start, timeLimit, settings)
    })

  def update(m: Match): IO[Unit] =
    session.transaction.use { _ =>
      session
        .execute(updateMatch)(
          (m.description, m.completed, m.start, toSeconds(m.timeLimit), m.settings, m.gameId, m.matchId)
        )
        .void
    }
}
