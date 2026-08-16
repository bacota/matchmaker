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
  private val gameType = SkunkCodecs.gameType
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge
      : Query[(GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, GameId), ChallengeId] =
    sql"""INSERT INTO open_challenge (game_type, challenger, message, number_of_players, start, time_limit, settings, game_id)
          VALUES ($gameType, $playerId, $text, $int2, ${instant.opt}, ${float8.opt} * INTERVAL '1 second', $settings, $gameId)
          RETURNING challenge_id""".query(challengeId)

  // challenge_id is GENERATED ALWAYS AS IDENTITY on this table too, but it must actually equal
  // the challenge_id its FK references on open_challenge, so it has to be supplied explicitly
  // rather than left to generate its own (unrelated) value — hence OVERRIDING SYSTEM VALUE.
  private val insertCharacterChallenge: Command[(GameId, ChallengeId, CharacterId)] =
    sql"""INSERT INTO character_open_challenge (game_id, challenge_id, game_type, character_id)
          OVERRIDING SYSTEM VALUE
          VALUES ($gameId, $challengeId, 'C', $characterId)""".command

  // A trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from
  // outside Ids.scala (see AcceptanceRepo's gameAndCharacterId comment); characterId is decoded
  // as a raw int8 here and wrapped afterward for the same reason.
  private val challengeRow: Codec[
    (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Option[Long])
  ] =
    gameType *: gameId *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: int8.opt

  private def toChallenge(
      id: ChallengeId,
      row: (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Option[Long])
  ): OpenChallenge = {
    val (gameType, gameId, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, characterIdValue) = row
    val timeLimit = fromSeconds(timeLimitSeconds)
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"open_challenge ${id.value} is game_type 'C' but has no character_open_challenge row")
        )
        CharacterOpenChallenge(id, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId, CharacterId(cid))
      case GameType.Plain =>
        PlainOpenChallenge(id, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId)
    }
  }

  private val selectChallenge: Query[
    ChallengeId,
    (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Option[Long])
  ] =
    sql"""SELECT oc.game_type, oc.game_id, oc.challenger, oc.message, oc.number_of_players, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, cc.character_id
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          WHERE oc.challenge_id = $challengeId"""
      .query(challengeRow)

  private val selectChallengeForUpdate: Query[ChallengeId, (GameId, GameType, Short)] =
    sql"""SELECT game_id, game_type, number_of_players FROM open_challenge WHERE challenge_id = $challengeId FOR UPDATE"""
      .query(gameId *: gameType *: int2)

  private val updateChallenge: Command[(PlayerId, String, Short, Option[Instant], Option[Double], String, ChallengeId)] =
    sql"""UPDATE open_challenge SET challenger = $playerId, message = $text, number_of_players = $int2,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings
          WHERE challenge_id = $challengeId""".command

  def create(c: OpenChallenge): IO[OpenChallenge] =
    session.transaction.use { _ =>
      val gt = c match {
        case _: CharacterOpenChallenge => GameType.Character
        case _: PlainOpenChallenge     => GameType.Plain
      }
      for {
        id <- session.unique(insertChallenge)(
          (gt, c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.gameId)
        )
        _ <- c match {
          case cc: CharacterOpenChallenge => session.execute(insertCharacterChallenge)((c.gameId, id, cc.characterId)).void
          case _: PlainOpenChallenge      => IO.unit
        }
      } yield c match {
        case cc: CharacterOpenChallenge => cc.copy(challengeId = id)
        case pc: PlainOpenChallenge     => pc.copy(challengeId = id)
      }
    }

  def read(id: ChallengeId): IO[Option[OpenChallenge]] =
    session.option(selectChallenge)(id).map(_.map(row => toChallenge(id, row)))

  /** Reads a challenge's gameId, game_type, and numberOfPlayers, taking a row lock (`FOR UPDATE`)
    * that is held until the enclosing transaction commits or rolls back. Callers use this to
    * serialize concurrent acceptance attempts against the same challenge's capacity check, and
    * the game_type to decide whether an acceptance must carry a characterId.
    */
  def readForUpdate(id: ChallengeId): IO[Option[(GameId, GameType, Short)]] =
    session.option(selectChallengeForUpdate)(id)

  def update(c: OpenChallenge): IO[Unit] =
    session
      .execute(updateChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.challengeId)
      )
      .void

  // character_open_challenge has a FK to open_challenge, so its row must go first — deleting
  // the parent row while a character_open_challenge row still references it is a FK violation.
  private val deleteCharacterChallenge: Command[ChallengeId] =
    sql"DELETE FROM character_open_challenge WHERE challenge_id = $challengeId".command

  private val deleteChallenge: Command[ChallengeId] =
    sql"DELETE FROM open_challenge WHERE challenge_id = $challengeId".command

  def delete(id: ChallengeId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterChallenge)(id)
      _ <- session.execute(deleteChallenge)(id)
    } yield ()

  private val selectChallengesByGame: Query[
    GameId,
    (ChallengeId, GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, Option[Long])
  ] =
    sql"""SELECT oc.challenge_id, oc.game_type, oc.challenger, oc.message, oc.number_of_players, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, cc.character_id
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          WHERE oc.game_id = $gameId
          ORDER BY oc.create_date DESC"""
      .query(challengeId *: gameType *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: int8.opt)

  /** Every open challenge for a game, newest first. */
  def listByGame(id: GameId): IO[List[OpenChallenge]] =
    session.execute(selectChallengesByGame)(id).map(_.map {
      case (challengeId, gt, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, characterIdValue) =>
        toChallenge(challengeId, (gt, id, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, characterIdValue))
    })
}
