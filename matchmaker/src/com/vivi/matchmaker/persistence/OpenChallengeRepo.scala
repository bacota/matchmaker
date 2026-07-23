package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

class OpenChallengeRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge
      : Query[(PlayerId, String, Short, Option[Instant], Option[Double], String, GameId, CharacterId), ChallengeId] =
    sql"""INSERT INTO open_challenge (challenger, message, number_of_players, start, time_limit, settings, game_id, character_id)
          VALUES ($playerId, $text, $int2, ${instant.opt}, ${float8.opt} * INTERVAL '1 second', $settings, $gameId, $characterId)
          RETURNING challenge_id""".query(challengeId)

  private val selectChallenge
      : Query[ChallengeId, (GameId, CharacterId, PlayerId, String, Short, Option[Instant], Option[Double], String)] =
    sql"""SELECT game_id, character_id, challenger, message, number_of_players, start,
                 EXTRACT(EPOCH FROM time_limit)::float8, settings
          FROM open_challenge
          WHERE challenge_id = $challengeId"""
      .query(gameId *: characterId *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings)

  private val updateChallenge: Command[(PlayerId, String, Short, Option[Instant], Option[Double], String, ChallengeId)] =
    sql"""UPDATE open_challenge SET challenger = $playerId, message = $text, number_of_players = $int2,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings
          WHERE challenge_id = $challengeId""".command

  def create(c: CharacterOpenChallenge): IO[CharacterOpenChallenge] =
    session
      .unique(insertChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.gameId, c.characterId)
      )
      .map(id => c.copy(challengeId = id))

  def read(id: ChallengeId): IO[Option[CharacterOpenChallenge]] =
    session.option(selectChallenge)(id).map(_.map {
      case (gameId, characterId, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings) =>
        CharacterOpenChallenge(id, challenger, message, numberOfPlayers, start, fromSeconds(timeLimitSeconds), settings, gameId, characterId)
    })

  def update(c: CharacterOpenChallenge): IO[Unit] =
    session
      .execute(updateChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.challengeId)
      )
      .void
}
