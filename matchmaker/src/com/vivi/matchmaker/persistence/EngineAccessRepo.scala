package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._
import EngineAccessRepo.{CodeRow, FoundCode, NewCode}

/** Which engines are restricted, who may play them, and the codes that let a player in: `restricted_engine`,
  * `engine_player` and `engine_access_code`. Who may change them is
  * [[com.vivi.matchmaker.service.EngineAccessService]]'s business, not this one's.
  */
class EngineAccessRepo(session: Session[IO]) {
    private val engineId = SkunkIdCodecs.engineId
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val codeId = SkunkIdCodecs.engineAccessCodeId
    private val instant = SkunkCodecs.instant

    // ---- the restriction ------------------------------------------------------------------------

    private val selectRestricted: Query[EngineId, EngineId] =
        sql"SELECT external_id FROM restricted_engine WHERE external_id = $engineId".query(engineId)

    private val selectRestrictedForShare: Query[EngineId, EngineId] =
        sql"SELECT external_id FROM restricted_engine WHERE external_id = $engineId FOR SHARE".query(engineId)

    private val insertRestricted: Command[(EngineId, PlayerId)] =
        sql"""INSERT INTO restricted_engine (external_id, restricted_by) VALUES ($engineId, $playerId)
          ON CONFLICT (external_id) DO NOTHING""".command

    private val deleteRestricted: Command[EngineId] =
        sql"DELETE FROM restricted_engine WHERE external_id = $engineId".command

    /** Whether only listed players may play the engine, read plainly: for what to show, not what to write. */
    def isRestricted(engine: EngineId): IO[Boolean] = session.option(selectRestricted)(engine).map(_.isDefined)

    /** Whether only listed players may play the engine, held until the transaction ends, so that lifting the
      * restriction waits for whatever this answer decides.
      */
    def isRestrictedForShare(engine: EngineId): IO[Boolean] =
        session.option(selectRestrictedForShare)(engine).map(_.isDefined)

    /** Restricts the engine. Restricting one that already is changes nothing, and not who restricted it. */
    def restrict(engine: EngineId, by: PlayerId): IO[Unit] = session.execute(insertRestricted)((engine, by)).void

    /** Opens the engine to everybody. Its players and codes are kept, for when it is restricted again. */
    def unrestrict(engine: EngineId): IO[Unit] = session.execute(deleteRestricted)(engine).void

    // ---- players --------------------------------------------------------------------------------

    private val selectListedForShare: Query[(EngineId, PlayerId), PlayerId] =
        sql"""SELECT player_id FROM engine_player WHERE external_id = $engineId AND player_id = $playerId FOR SHARE"""
            .query(playerId)

    private val insertPlayer: Query[(EngineId, PlayerId, Option[PlayerId], Option[EngineAccessCodeId]), PlayerId] =
        sql"""INSERT INTO engine_player (external_id, player_id, granted_by, code_id)
          VALUES ($engineId, $playerId, ${playerId.opt}, ${codeId.opt})
          ON CONFLICT (external_id, player_id) DO NOTHING
          RETURNING player_id""".query(playerId)

    private val deletePlayer: Command[(EngineId, PlayerId)] =
        sql"DELETE FROM engine_player WHERE external_id = $engineId AND player_id = $playerId".command

    private val selectPlayers
        : Query[EngineId, (PlayerId, String, Option[PlayerId], Option[String], Option[String], Instant)] =
        sql"""SELECT p.player_id, p.nickname, b.player_id, b.nickname, c.label, e.granted_at
          FROM engine_player e
          JOIN player p ON p.player_id = e.player_id
          LEFT JOIN player b ON b.player_id = e.granted_by
          LEFT JOIN engine_access_code c ON c.code_id = e.code_id
          WHERE e.external_id = $engineId
          ORDER BY p.nickname""".query(playerId *: text *: playerId.opt *: text.opt *: text.opt *: instant)

    /** Whether the player is listed for the engine, held until the transaction ends: FOR SHARE, so that taking them off
      * waits for whatever this answer is deciding, rather than landing between the check and the write.
      */
    def isListedForShare(engine: EngineId, player: PlayerId): IO[Boolean] =
        session.option(selectListedForShare)((engine, player)).map(_.isDefined)

    /** Lists the player for the engine, on an admin's say or a code's. False if they were listed already, which changes
      * nothing, and in particular not how they were let in.
      */
    def grant(
        engine: EngineId,
        player: PlayerId,
        grantedBy: Option[PlayerId],
        code: Option[EngineAccessCodeId]
    ): IO[Boolean] =
        session.option(insertPlayer)((engine, player, grantedBy, code)).map(_.isDefined)

    /** Takes the player off the engine's list. False if they were not on it. */
    def revoke(engine: EngineId, player: PlayerId): IO[Boolean] =
        session.execute(deletePlayer)((engine, player)).map {
            case skunk.data.Completion.Delete(n) => n > 0
            case _                               => false
        }

    /** The engine's players by nickname, with how each was let in. */
    def listPlayers(engine: EngineId): IO[List[EngineListedPlayer]] =
        session
            .execute(selectPlayers)(engine)
            .map(_.map { (id, nickname, byId, byNickname, label, at) =>
                EngineListedPlayer(
                  player = PublicPlayer(id, nickname),
                  grantedBy = byId.zip(byNickname).map((i, n) => PublicPlayer(i, n)),
                  codeLabel = label,
                  grantedAt = at
                )
            })

    private val selectBarred: Query[(PlayerId, PlayerId), GameId] =
        sql"""SELECT g.game_id
          FROM game g
          JOIN restricted_engine r ON r.external_id = g.external_id
          WHERE NOT EXISTS (
              SELECT 1 FROM engine_player e WHERE e.external_id = g.external_id AND e.player_id = $playerId)
            AND NOT EXISTS (
              SELECT 1 FROM game_admin a WHERE a.game_id = g.game_id AND a.player_id = $playerId)
          ORDER BY g.game_id""".query(gameId)

    /** The games the player may not play: those of a restricted engine they are not listed for and do not administer.
      * Read plainly, for what the UI offers; an overall admin is the caller's to leave out.
      */
    def barredGames(player: PlayerId): IO[List[GameId]] = session.execute(selectBarred)((player, player))

    // ---- codes ----------------------------------------------------------------------------------

    private val codeColumns = sql"code_id, label, max_uses, uses, expires_at, is_revoked, created_at"
    private val codeRow = codeId *: text *: int4.opt *: int4 *: instant.opt *: bool *: instant

    private val insertCode: Query[(EngineId, Array[Byte], String, Option[Int], Option[Instant], PlayerId), CodeRow] =
        sql"""INSERT INTO engine_access_code (external_id, code_hash, label, max_uses, expires_at, created_by)
          VALUES ($engineId, $bytea, $text, ${int4.opt}, ${instant.opt}, $playerId)
          RETURNING $codeColumns""".query(codeRow)

    /* Every live code, as any may still be revoked, and only the newest dead ones: revoked codes are kept, so those
     * only grow. "Live" is EngineAccessCode.isRedeemable, stated in SQL. */
    private val selectCodes: Query[(Instant, EngineId, Int), CodeRow] =
        sql"""SELECT $codeColumns FROM (
            SELECT *, row_number() OVER (PARTITION BY is_live ORDER BY created_at DESC, code_id DESC) AS place
            FROM (
              SELECT *, (NOT is_revoked AND (expires_at IS NULL OR expires_at > $instant)
                         AND (max_uses IS NULL OR uses < max_uses)) AS is_live
              FROM engine_access_code WHERE external_id = $engineId
            ) c
          ) ranked
          WHERE is_live OR place <= $int4
          ORDER BY created_at DESC, code_id DESC""".query(codeRow)

    private val selectCodeByHashForUpdate: Query[Array[Byte], EngineId *: CodeRow] =
        sql"""SELECT external_id, $codeColumns FROM engine_access_code WHERE code_hash = $bytea FOR UPDATE"""
            .query(engineId *: codeRow)

    private val updateRevoked: Command[(EngineId, EngineAccessCodeId)] =
        sql"""UPDATE engine_access_code SET is_revoked = true
          WHERE external_id = $engineId AND code_id = $codeId""".command

    private val updateUses: Command[EngineAccessCodeId] =
        sql"UPDATE engine_access_code SET uses = uses + 1 WHERE code_id = $codeId".command

    private def toCode(row: CodeRow): EngineAccessCode = {
        val (id, label, maxUses, uses, expiresAt, isRevoked, createdAt) = row
        EngineAccessCode(
          codeId = id,
          label = label,
          maxUses = maxUses,
          uses = uses,
          expiresAt = expiresAt,
          isRevoked = isRevoked,
          createdAt = createdAt
        )
    }

    /** Stores a code by its hash, and answers its row. */
    def createCode(code: NewCode): IO[EngineAccessCode] =
        session
            .unique(insertCode)((code.engine, code.codeHash, code.label, code.maxUses, code.expiresAt, code.createdBy))
            .map(toCode)

    /** The engine's codes, newest first: every one that could be redeemed at `now`, and the
      * [[EngineAccessRepo.DeadCodesListed]] newest of those revoked, expired or used up.
      */
    def listCodes(engine: EngineId, now: Instant): IO[List[EngineAccessCode]] =
        session.execute(selectCodes)((now, engine, EngineAccessRepo.DeadCodesListed)).map(_.map(toCode))

    /** The code stored by this hash, and its engine, locked for the redeem its answer decides. */
    def codeByHashForUpdate(codeHash: Array[Byte]): IO[Option[FoundCode]] =
        session
            .option(selectCodeByHashForUpdate)(codeHash)
            .map(_.map { case engine *: row => FoundCode(engine = engine, code = toCode(row)) })

    /** Revokes the engine's code. False if the engine has no such code. */
    def revokeCode(engine: EngineId, code: EngineAccessCodeId): IO[Boolean] =
        session.execute(updateRevoked)((engine, code)).map {
            case skunk.data.Completion.Update(n) => n > 0
            case _                               => false
        }

    /** Counts one use of the code. */
    def countUse(code: EngineAccessCodeId): IO[Unit] = session.execute(updateUses)(code).void
}

object EngineAccessRepo {

    /** How many revoked, expired or used-up codes an engine's list shows, newest first. */
    val DeadCodesListed = 20
    private type CodeRow = (EngineAccessCodeId, String, Option[Int], Int, Option[Instant], Boolean, Instant)

    /** A code to store: only its hash, never the code. */
    case class NewCode(
        engine: EngineId,
        codeHash: Array[Byte],
        label: String,
        maxUses: Option[Int],
        expiresAt: Option[Instant],
        createdBy: PlayerId
    )

    /** A code found by its hash, and the engine it lets a player into. */
    case class FoundCode(engine: EngineId, code: EngineAccessCode)
}
