package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

class OpenChallengeRepo(session: Session[IO]) {
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = varchar

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge: Query[(Long, String, Short, Option[Instant], Option[Double], String), Long] =
    sql"""INSERT INTO open_challenge (challenger, message, number_of_players, start, time_limit, settings)
          VALUES ($int8, $varchar, $int2, ${instant.opt}, ${float8.opt} * INTERVAL '1 second', $settings::jsonb)
          RETURNING challenge_id""".query(int8)

  private val insertPlayerOpenChallenge: Command[(Long, Int)] =
    sql"INSERT INTO player_open_challenge (challenge_id, game_id) VALUES ($int8, $int4)".command

  private val insertCharacterOpenChallenge: Command[(Long, Long, Int)] =
    sql"INSERT INTO character_open_challenge (challenge_id, character_id, game_id) VALUES ($int8, $int8, $int4)".command

  private val selectChallenge: Query[
    Long,
    (Long, String, Short, Option[Instant], Option[Double], String, Option[Int], Option[Int], Option[Long])
  ] =
    sql"""SELECT o.challenger, o.message, o.number_of_players, o.start, EXTRACT(EPOCH FROM o.time_limit), o.settings,
                 poc.game_id AS player_game_id, coc.game_id AS character_game_id, coc.character_id
          FROM open_challenge o
          LEFT JOIN player_open_challenge poc ON poc.challenge_id = o.challenge_id
          LEFT JOIN character_open_challenge coc ON coc.challenge_id = o.challenge_id
          WHERE o.challenge_id = $int8"""
      .query(int8 *: varchar *: int2 *: instant.opt *: float8.opt *: settings *: int4.opt *: int4.opt *: int8.opt)

  private val updateChallenge: Command[(Long, String, Short, Option[Instant], Option[Double], String, Long)] =
    sql"""UPDATE open_challenge SET challenger = $int8, message = $varchar, number_of_players = $int2,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings::jsonb
          WHERE challenge_id = $int8""".command

  def create(c: OpenChallenge): IO[OpenChallenge] =
    for {
      challengeId <- session.unique(insertChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings)
      )
      result <- c match {
        case poc: PlayerOpenChallenge =>
          session
            .execute(insertPlayerOpenChallenge)((challengeId, poc.gameId))
            .as(poc.copy(challengeId = challengeId): OpenChallenge)
        case coc: CharacterOpenChallenge =>
          session
            .execute(insertCharacterOpenChallenge)((challengeId, coc.characterId, coc.gameId))
            .as(coc.copy(challengeId = challengeId): OpenChallenge)
      }
    } yield result

  def read(challengeId: Long): IO[Option[OpenChallenge]] =
    session.option(selectChallenge)(challengeId).map(_.map {
      case (challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, playerGameId, characterGameId, characterId) =>
        val timeLimit = fromSeconds(timeLimitSeconds)
        playerGameId match {
          case Some(gameId) =>
            PlayerOpenChallenge(challengeId, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId)
          case None =>
            CharacterOpenChallenge(
              challengeId,
              challenger,
              message,
              numberOfPlayers,
              start,
              timeLimit,
              settings,
              characterGameId.get,
              characterId.get
            )
        }
    })

  def update(c: OpenChallenge): IO[Unit] =
    session
      .execute(updateChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.challengeId)
      )
      .void
}
