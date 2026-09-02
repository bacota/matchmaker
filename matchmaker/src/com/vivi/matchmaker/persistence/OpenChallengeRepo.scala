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
case class LockedChallenge(gameType: GameType, startedMatchId: Option[MatchId])

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
  private val timeLimitKind = SkunkCodecs.timeLimitKind
  private val timeLimitUnit = SkunkCodecs.timeLimitUnit

  private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
  private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

  private val insertChallenge
      : Query[
        (GameType, PlayerId, String, Option[Instant], Option[Double], String, GameId, Boolean, TimeLimitKind,
          TimeLimitUnit),
        ChallengeId
      ] =
    sql"""INSERT INTO open_challenge (game_type, challenger, message, start, time_limit,
                                      settings, game_id, public, time_limit_kind, time_limit_unit)
          VALUES ($gameType, $playerId, $text, ${instant.opt}, ${float8.opt} * INTERVAL '1 second',
                  $settings, $gameId, $bool, $timeLimitKind, $timeLimitUnit)
          RETURNING challenge_id""".query(challengeId)

  private val insertCharacterChallenge: Command[(GameId, ChallengeId, CharacterId)] =
    sql"""INSERT INTO character_open_challenge (game_id, challenge_id, game_type, character_id)
          VALUES ($gameId, $challengeId, 'C', $characterId)""".command

  // A trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from
  // outside Ids.scala (see AcceptanceRepo's gameAndCharacterId comment); characterId is decoded
  // as a raw int8 here and wrapped afterward for the same reason.
  private val challengeRow: Codec[
    (GameType, GameId, PlayerId, String, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long],
      TimeLimitKind, TimeLimitUnit)
  ] =
    gameType *: gameId *: playerId *: text *: instant.opt *: float8.opt *: settings *: bool *: gameRoleId *: int8.opt *:
      timeLimitKind *: timeLimitUnit

  private def toChallenge(
      id: ChallengeId,
      row: (GameType, GameId, PlayerId, String, Option[Instant], Option[Double], String, Boolean, GameRoleId,
        Option[Long], TimeLimitKind, TimeLimitUnit)
  ): OpenChallenge = {
    val (gameType, gameId, challenger, message, start, timeLimitSeconds, settings, isPublic, roleId, characterIdValue,
      timeLimitKind, timeLimitUnit) = row
    val timeLimit = fromSeconds(timeLimitSeconds)
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"open_challenge ${id.value} is game_type 'C' but has no character_open_challenge row")
        )
        CharacterOpenChallenge(id, challenger, message, start, timeLimit, settings, gameId, CharacterId(cid),
          isPublic, roleId, timeLimitKind, timeLimitUnit)
      case GameType.Plain =>
        PlainOpenChallenge(id, challenger, message, start, timeLimit, settings, gameId, isPublic, roleId, timeLimitKind,
          timeLimitUnit)
    }
  }

  // open_challenge's primary key is the composite (game_id, challenge_id) — challenge_id alone
  // is not declared unique — so both columns are required here, not challenge_id alone.
  private val selectChallenge: Query[
    (GameId, ChallengeId),
    (GameType, GameId, PlayerId, String, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long],
      TimeLimitKind, TimeLimitUnit)
  ] =
    sql"""SELECT oc.game_type, oc.game_id, oc.challenger, oc.message, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.public, a.game_role_id, cc.character_id,
                 oc.time_limit_kind, oc.time_limit_unit
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          JOIN acceptance a ON a.game_id = oc.game_id AND a.challenge_id = oc.challenge_id
                           AND a.player_id = oc.challenger
          WHERE oc.game_id = $gameId AND oc.challenge_id = $challengeId"""
      .query(challengeRow)

  private val selectChallengeForUpdate: Query[(GameId, ChallengeId), (GameType, Option[MatchId])] =
    sql"""SELECT game_type, started_match_id FROM open_challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId FOR UPDATE"""
      .query(gameType *: matchId.opt)

  // gameRoleId is not written here: it belongs to the challenger's acceptance, and changing the
  // role they will play means updating that row (AcceptanceRepo), not this one.
  private val updateChallenge
      : Command[
        (PlayerId, String, Option[Instant], Option[Double], String, Boolean, TimeLimitKind, TimeLimitUnit, GameId,
          ChallengeId)
      ] =
    sql"""UPDATE open_challenge SET challenger = $playerId, message = $text,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool, time_limit_kind = $timeLimitKind, time_limit_unit = $timeLimitUnit
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
        (gt, c.challenger, c.message, c.start, toSeconds(c.timeLimit), c.settings, c.gameId, c.isPublic,
          c.timeLimitKind, c.timeLimitUnit)
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

  /** Reads a challenge's game_type and start claim, taking a row lock (`FOR UPDATE`) that is
    * held until the enclosing transaction commits or rolls back. Callers use this to serialize
    * concurrent acceptance attempts against the same challenge's roles, the game_type to decide
    * whether an acceptance must carry a characterId, and startedMatchId to refuse a challenge
    * someone is already starting.
    */
  def readForUpdate(gameId: GameId, id: ChallengeId): IO[Option[LockedChallenge]] =
    session.option(selectChallengeForUpdate)((gameId, id)).map(_.map(LockedChallenge.apply.tupled))

  private val selectChallenger: Query[(GameId, ChallengeId), PlayerId] =
    sql"""SELECT challenger FROM open_challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId""".query(playerId)

  /** Just the challenger of a challenge.
    *
    * Separate from [[read]] because that one joins the challenger's acceptance to read the role
    * they are playing, and the callers of this are asking a different question: a match's
    * creator is its challenge's challenger, and that is true whatever became of the acceptances.
    */
  def challengerOf(gameId: GameId, id: ChallengeId): IO[Option[PlayerId]] =
    session.option(selectChallenger)((gameId, id))

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
        (c.challenger, c.message, c.start, toSeconds(c.timeLimit), c.settings, c.isPublic, c.timeLimitKind,
          c.timeLimitUnit, c.gameId, c.challengeId)
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
    (GameId, PlayerId),
    (ChallengeId, GameType, PlayerId, String, Option[Instant], Option[Double], String, Boolean, GameRoleId,
      Option[Long], Long, String, TimeLimitKind, TimeLimitUnit)
  ] =
    sql"""SELECT oc.challenge_id, oc.game_type, oc.challenger, oc.message, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.public, a.game_role_id, cc.character_id,
                 (SELECT count(*) FROM acceptance ac
                   WHERE ac.game_id = oc.game_id AND ac.challenge_id = oc.challenge_id),
                 -- The roles already claimed, as a comma-separated list rather than an array:
                 -- one more scalar subquery beside the count, decoded as text below, which keeps
                 -- this row a flat tuple of scalars like every other query here.
                 (SELECT coalesce(string_agg(ac.game_role_id::text, ',' ORDER BY ac.game_role_id), '')
                    FROM acceptance ac
                   WHERE ac.game_id = oc.game_id AND ac.challenge_id = oc.challenge_id),
                 oc.time_limit_kind, oc.time_limit_unit
          FROM open_challenge oc
          LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
          JOIN acceptance a ON a.game_id = oc.game_id AND a.challenge_id = oc.challenge_id
                           AND a.player_id = oc.challenger
          WHERE oc.game_id = $gameId AND oc.started_match_id IS NULL
            -- A full challenge is nobody else's business: it cannot be accepted, and the only
            -- people it is still about are the ones already in it — who need it in order to see
            -- what they are waiting for, and who, if the challenger, need it to start the match.
            -- Full means every one of the game's roles is spoken for, optional ones included:
            -- those are seats a latecomer could still take, even though a start need not wait
            -- for them.
            AND (EXISTS (SELECT 1 FROM game_role gr
                          WHERE gr.game_id = oc.game_id
                            AND NOT EXISTS (SELECT 1 FROM acceptance ac
                                             WHERE ac.game_id = oc.game_id
                                               AND ac.challenge_id = oc.challenge_id
                                               AND ac.game_role_id = gr.game_role_id))
                 OR EXISTS (SELECT 1 FROM acceptance ac
                             WHERE ac.game_id = oc.game_id AND ac.challenge_id = oc.challenge_id
                               AND ac.player_id = $playerId))
          ORDER BY oc.create_date DESC"""
      .query(
        challengeId *: gameType *: playerId *: text *: instant.opt *: float8.opt *: settings *: bool *:
          gameRoleId *: int8.opt *: int8 *: text *: timeLimitKind *: timeLimitUnit
      )

  /** Every *open* challenge for a game that `viewer` may see, newest first, each with how many
    * players have accepted it.
    *
    * A challenge that has been started is excluded: it is no longer open, and since starting one
    * no longer deletes it, it would otherwise sit in the list forever offering a Start that would
    * be refused.
    *
    * A challenge that is full — every role of its game taken — but not yet started is excluded
    * too, unless `viewer` has accepted it. It is not something anyone else can join, and listing
    * it invites a click on an Accept the service would refuse. The challenger sees their own
    * throughout, since creating a challenge writes their acceptance of it.
    *
    * The count and the claimed roles come back with the challenge rather than from a call per
    * challenge: the UI needs both for every row it draws — the count to know whether a challenge
    * has enough acceptances to be started, the roles to know which ones are still free to accept
    * as, and together whether a start would be refused for a role nobody has taken.
    */
  def listByGame(id: GameId, viewer: PlayerId): IO[List[OpenChallengeSummary]] =
    session.execute(selectChallengesByGame)((id, viewer)).map(_.map {
      case (challengeId, gt, challenger, message, start, timeLimitSeconds, settings, isPublic, roleId,
            characterIdValue, acceptances, takenRoles, timeLimitKind, timeLimitUnit) =>
        OpenChallengeSummary(
          toChallenge(
            challengeId,
            (gt, id, challenger, message, start, timeLimitSeconds, settings, isPublic, roleId, characterIdValue,
              timeLimitKind, timeLimitUnit)
          ),
          acceptances.toInt,
          takenRoles.split(',').filter(_.nonEmpty).map(v => GameRoleId(v.toInt)).toSeq
        )
    })
}
