package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** The fields read (and locked) by [[OpenChallengeRepo.readForUpdate]].
  *
  * `startedMatchId` is non-empty once a challenge has been claimed by
  * `GameEngineService.start`; see [[OpenChallengeRepo.claimForStart]].
  */
case class LockedChallenge(gameType: GameType, numberOfPlayers: Short, startedMatchId: Option[MatchId])

/** Reads and writes `open_challenge` (plus its `character_open_challenge` sibling).
  *
  * `OpenChallenge.gameRoleId` has no column here — the challenger's role lives on their own
  * acceptance, which `OpenChallengeService.create` writes in the same transaction as the
  * challenge. Reads join that acceptance back in, so a challenge still reports the role its
  * challenger will play without the fact being stored twice.
  */
class OpenChallengeRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId
  private val gameType = SkunkCodecs.gameType
  private val gameRoleId = SkunkIdCodecs.gameRoleId
  private val matchId = SkunkIdCodecs.matchId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge
      : Query[(GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, GameId, Boolean), ChallengeId] =
    sql"""INSERT INTO open_challenge (game_type, challenger, message, number_of_players, start, time_limit,
                                      settings, game_id, public)
          VALUES ($gameType, $playerId, $text, $int2, ${instant.opt}, ${float8.opt} * INTERVAL '1 second',
                  $settings, $gameId, $bool)
          RETURNING challenge_id""".query(challengeId)

  private val insertCharacterChallenge: Command[(GameId, ChallengeId, CharacterId)] =
    sql"""INSERT INTO character_open_challenge (game_id, challenge_id, game_type, character_id)
          VALUES ($gameId, $challengeId, 'C', $characterId)""".command

  // A trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from
  // outside Ids.scala (see AcceptanceRepo's gameAndCharacterId comment); characterId is decoded
  // as a raw int8 here and wrapped afterward for the same reason.
  private val challengeRow: Codec[
    (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long])
  ] =
    gameType *: gameId *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: bool *: gameRoleId *: int8.opt

  private def toChallenge(
      id: ChallengeId,
      row: (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long])
  ): OpenChallenge = {
    val (gameType, gameId, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, isPublic, roleId, characterIdValue) = row
    val timeLimit = fromSeconds(timeLimitSeconds)
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"open_challenge ${id.value} is game_type 'C' but has no character_open_challenge row")
        )
        CharacterOpenChallenge(id, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId, CharacterId(cid), isPublic, roleId)
      case GameType.Plain =>
        PlainOpenChallenge(id, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId, isPublic, roleId)
    }
  }

  // open_challenge's primary key is the composite (game_id, challenge_id) — challenge_id alone
  // is not declared unique — so both columns are required here, not challenge_id alone.
  private val selectChallenge: Query[
    (GameId, ChallengeId),
    (GameType, GameId, PlayerId, String, Short, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long])
  ] =
    sql"""SELECT oc.game_type, oc.game_id, oc.challenger, oc.message, oc.number_of_players, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.public, a.game_role_id, cc.character_id
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          JOIN acceptance a ON a.game_id = oc.game_id AND a.challenge_id = oc.challenge_id
                           AND a.player_id = oc.challenger
          WHERE oc.game_id = $gameId AND oc.challenge_id = $challengeId"""
      .query(challengeRow)

  private val selectChallengeForUpdate: Query[(GameId, ChallengeId), (GameType, Short, Option[MatchId])] =
    sql"""SELECT game_type, number_of_players, started_match_id FROM open_challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId FOR UPDATE"""
      .query(gameType *: int2 *: matchId.opt)

  // gameRoleId is not written here: it belongs to the challenger's acceptance, and changing the
  // role they will play means updating that row (AcceptanceRepo), not this one.
  private val updateChallenge
      : Command[(PlayerId, String, Short, Option[Instant], Option[Double], String, Boolean, GameId, ChallengeId)] =
    sql"""UPDATE open_challenge SET challenger = $playerId, message = $text, number_of_players = $int2,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

  /** Inserts the challenge, and for a [[CharacterOpenChallenge]] its character row too.
    *
    * Like every write in this package it opens no transaction of its own — that is the calling
    * service's job, and skunk rejects nested transactions outright anyway.
    */
  def create(c: OpenChallenge): IO[OpenChallenge] = {
    val gt = c match {
      case _: CharacterOpenChallenge => GameType.Character
      case _: PlainOpenChallenge     => GameType.Plain
    }
    for {
      id <- session.unique(insertChallenge)(
        (gt, c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.gameId, c.isPublic)
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

  def read(gameId: GameId, id: ChallengeId): IO[Option[OpenChallenge]] =
    session.option(selectChallenge)((gameId, id)).map(_.map(row => toChallenge(id, row)))

  /** Reads a challenge's game_type, numberOfPlayers and start claim, taking a row lock
    * (`FOR UPDATE`) that is held until the enclosing transaction commits or rolls back. Callers
    * use this to serialize concurrent acceptance attempts against the same challenge's capacity
    * check, the game_type to decide whether an acceptance must carry a characterId, and
    * startedMatchId to refuse a challenge someone is already starting.
    */
  def readForUpdate(gameId: GameId, id: ChallengeId): IO[Option[LockedChallenge]] =
    session.option(selectChallengeForUpdate)((gameId, id)).map(_.map(LockedChallenge.apply.tupled))

  private val claimChallengeForStart: Command[(MatchId, GameId, ChallengeId)] =
    sql"""UPDATE open_challenge SET started_match_id = $matchId
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

  private val releaseChallengeStartClaim: Command[(GameId, ChallengeId)] =
    sql"""UPDATE open_challenge SET started_match_id = NULL
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

  /** Marks a challenge as being started as `matchId`, so that a second concurrent start is
    * refused rather than producing a second match.
    *
    * Must be called in the same transaction as the [[readForUpdate]] whose row lock it is
    * guarding: the lock alone only serializes two starts, it does not tell the second one that
    * the first has already happened. This is the write that does.
    */
  def claimForStart(gameId: GameId, id: ChallengeId, matchId: MatchId): IO[Unit] =
    session.execute(claimChallengeForStart)((matchId, gameId, id)).void

  /** Clears a [[claimForStart]], returning the challenge to startable. Used when the engine call
    * that the claim was taken for fails and the half-made match is undone.
    */
  def releaseStartClaim(gameId: GameId, id: ChallengeId): IO[Unit] =
    session.execute(releaseChallengeStartClaim)((gameId, id)).void

  def update(c: OpenChallenge): IO[Unit] =
    session
      .execute(updateChallenge)(
        (c.challenger, c.message, c.numberOfPlayers, c.start, toSeconds(c.timeLimit), c.settings, c.isPublic, c.gameId, c.challengeId)
      )
      .void

  // character_open_challenge has a FK to open_challenge, so its row must go first — deleting
  // the parent row while a character_open_challenge row still references it is a FK violation.
  // open_challenge's primary key is the composite (game_id, challenge_id) — challenge_id alone
  // is not declared unique — so both columns are required here, not challenge_id alone.
  private val deleteCharacterChallenge: Command[(GameId, ChallengeId)] =
    sql"DELETE FROM character_open_challenge WHERE game_id = $gameId AND challenge_id = $challengeId".command

  private val deleteChallenge: Command[(GameId, ChallengeId)] =
    sql"DELETE FROM open_challenge WHERE game_id = $gameId AND challenge_id = $challengeId".command

  def delete(gameId: GameId, id: ChallengeId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterChallenge)((gameId, id))
      _ <- session.execute(deleteChallenge)((gameId, id))
    } yield ()

  // The acceptance count is a scalar subquery rather than another LEFT JOIN: this query already
  // joins the challenger's own acceptance to read their role, and counting over a second join to
  // the same table would multiply the rows rather than count them.
  private val selectChallengesByGame: Query[
    GameId,
    (ChallengeId, GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, Boolean, GameRoleId,
      Option[Long], Long, String)
  ] =
    sql"""SELECT oc.challenge_id, oc.game_type, oc.challenger, oc.message, oc.number_of_players, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.public, a.game_role_id, cc.character_id,
                 (SELECT count(*) FROM acceptance ac
                   WHERE ac.game_id = oc.game_id AND ac.challenge_id = oc.challenge_id),
                 -- The roles already claimed, as a comma-separated list rather than an array:
                 -- one more scalar subquery beside the count, decoded as text below, which keeps
                 -- this row a flat tuple of scalars like every other query here.
                 (SELECT coalesce(string_agg(ac.game_role_id::text, ',' ORDER BY ac.game_role_id), '')
                    FROM acceptance ac
                   WHERE ac.game_id = oc.game_id AND ac.challenge_id = oc.challenge_id)
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          JOIN acceptance a ON a.game_id = oc.game_id AND a.challenge_id = oc.challenge_id
                           AND a.player_id = oc.challenger
          WHERE oc.game_id = $gameId
          ORDER BY oc.create_date DESC"""
      .query(
        challengeId *: gameType *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: bool *:
          gameRoleId *: int8.opt *: int8 *: text
      )

  /** Every open challenge for a game, newest first, each with how many players have accepted it.
    *
    * The count and the claimed roles come back with the challenge rather than from a call per
    * challenge: the UI needs both for every row it draws — the count to know whether a challenge
    * has enough acceptances to be started, the roles to know which ones are still free to accept
    * as, and together whether a start would be refused for a role nobody has taken.
    */
  def listByGame(id: GameId): IO[List[OpenChallengeSummary]] =
    session.execute(selectChallengesByGame)(id).map(_.map {
      case (challengeId, gt, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, isPublic, roleId,
            characterIdValue, acceptances, takenRoles) =>
        OpenChallengeSummary(
          toChallenge(
            challengeId,
            (gt, id, challenger, message, numberOfPlayers, start, timeLimitSeconds, settings, isPublic, roleId, characterIdValue)
          ),
          acceptances.toInt,
          takenRoles.split(',').filter(_.nonEmpty).map(v => GameRoleId(v.toInt)).toSeq
        )
    })
}
