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
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = text

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge: Query[(PlayerId, String, Short, Option[Instant], Option[Double], String), ChallengeId] =
    sql"""INSERT INTO open_challenge (challenger, message, number_of_players, start, time_limit, settings)
          VALUES ($playerId, $text, $int2, ${instant.opt}, ${float8.opt} * INTERVAL '1 second', $settings::jsonb)
          RETURNING challenge_id""".query(challengeId)

  private val insertPlayerOpenChallenge: Command[(ChallengeId, GameId)] =
    sql"INSERT INTO player_open_challenge (challenge_id, game_id) VALUES ($challengeId, $gameId)".command

  private val insertCharacterOpenChallenge: Command[(ChallengeId, CharacterId, GameId)] =
    sql"INSERT INTO character_open_challenge (challenge_id, character_id, game_id) VALUES ($challengeId, $characterId, $gameId)".command

  private val selectChallenge: Query[
    ChallengeId,
    (PlayerId, String, Short, Option[Instant], Option[Double], String, Option[GameId], Option[GameId], Option[CharacterId])
  ] =
    sql"""SELECT o.challenger, o.message, o.number_of_players, o.start, EXTRACT(EPOCH FROM o.time_limit), o.settings,
                 poc.game_id AS player_game_id, coc.game_id AS character_game_id, coc.character_id
          FROM open_challenge o
          LEFT JOIN player_open_challenge poc ON poc.challenge_id = o.challenge_id
          LEFT JOIN character_open_challenge coc ON coc.challenge_id = o.challenge_id
          WHERE o.challenge_id = $challengeId"""
      .query(playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: gameId.opt *: gameId.opt *: characterId.opt)

  private val updateChallenge: Command[(PlayerId, String, Short, Option[Instant], Option[Double], String, ChallengeId)] =
    sql"""UPDATE open_challenge SET challenger = $playerId, message = $text, number_of_players = $int2,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings::jsonb
          WHERE challenge_id = $challengeId""".command

  def create(c: OpenChallenge): IO[OpenChallenge] =
    session.transaction.use { _ =>
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
    }

  def read(id: ChallengeId): IO[Option[OpenChallenge]] =
    session.option(selectChallenge)(id).map(_.map {
      case (challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, playerGameId, characterGameId, characterId) =>
        val timeLimit = fromSeconds(timeLimitSeconds)
        playerGameId match {
          case Some(gameId) =>
            PlayerOpenChallenge(id, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId)
          case None =>
            CharacterOpenChallenge(
              id,
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
    session.transaction.use { _ =>
      session
        .execute(updateChallenge)(
          (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.challengeId)
        )
        .void
    }
}
