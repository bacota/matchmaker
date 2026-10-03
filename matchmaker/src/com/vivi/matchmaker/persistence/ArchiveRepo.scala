package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._
import ArchiveRepo.{ArchiveRow, ReleaseRow}

/** A match's archive (V38): where it went, whether it arrived, and whether a friendly one has since expired.
  *
  * The columns live on `match`, but nothing else writes them — [[MatchRepo.update]] rewrites the match's own fields and
  * leaves these alone — so an archive's state cannot be overwritten by an unrelated change to the match.
  */
class ArchiveRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId
    private val instant = SkunkCodecs.instant

    private type Row = (
        Int,
        String,
        String,
        Option[Instant],
        Boolean,
        Boolean,
        Option[String],
        Option[String],
        Option[Instant],
        Option[Instant],
        Option[Instant]
    )

    private val row =
        int4 *: text *: text *: instant.opt *: bool *: bool *: text.opt *: text.opt *: instant.opt *: instant.opt *:
            instant.opt

    private def toRow(r: Row): ArchiveRow = {
        val (game, id, gameName, completedAt, cancelled, friendly, key, sha256, requestedAt, archivedAt, expiredAt) = r
        ArchiveRow(
          GameId(game),
          MatchId(id),
          gameName,
          completedAt,
          cancelled,
          friendly,
          key,
          sha256,
          requestedAt,
          archivedAt,
          expiredAt
        )
    }

    /* The match, found by its id and the engine asking about it rather than by game id: an engine
     * whose live copy is gone no longer knows which of matchmaker's games the match was in, only the
     * match id and who it is. Match ids are UUIDs, so the id alone picks one match; the external id
     * is what makes it this engine's. */
    private val selectForEngine: Query[(MatchId, String), Row] =
        sql"""SELECT m.game_id, m.match_id, g.name, m.completed, m.cancelled, m.friendly,
                 m.archive_key, m.archive_sha256, m.archive_requested, m.archived_at, m.archive_expired_at
          FROM match m
          JOIN game g ON g.game_id = m.game_id
          WHERE m.match_id = $matchId AND g.external_id = $text""".query(row)

    private val selectForEngineForUpdate: Query[(MatchId, String), Row] =
        sql"""SELECT m.game_id, m.match_id, g.name, m.completed, m.cancelled, m.friendly,
                 m.archive_key, m.archive_sha256, m.archive_requested, m.archived_at, m.archive_expired_at
          FROM match m
          JOIN game g ON g.game_id = m.game_id
          WHERE m.match_id = $matchId AND g.external_id = $text
          FOR UPDATE OF m""".query(row)

    private val selectByIds: Query[(GameId, MatchId), Row] =
        sql"""SELECT m.game_id, m.match_id, g.name, m.completed, m.cancelled, m.friendly,
                 m.archive_key, m.archive_sha256, m.archive_requested, m.archived_at, m.archive_expired_at
          FROM match m
          JOIN game g ON g.game_id = m.game_id
          WHERE m.game_id = $gameId AND m.match_id = $matchId""".query(row)

    /** The archive of match `id`, if the engine whose game's external id is `externalId` has such a match. */
    def readForEngine(id: MatchId, externalId: String): IO[Option[ArchiveRow]] =
        session.option(selectForEngine)((id, externalId)).map(_.map(toRow))

    /** As [[readForEngine]], holding the match's row until the transaction ends. */
    def readForEngineForUpdate(id: MatchId, externalId: String): IO[Option[ArchiveRow]] =
        session.option(selectForEngineForUpdate)((id, externalId)).map(_.map(toRow))

    def read(game: GameId, id: MatchId): IO[Option[ArchiveRow]] =
        session.option(selectByIds)((game, id)).map(_.map(toRow))

    private val updateRequest: Command[(String, String, GameId, MatchId)] =
        sql"""UPDATE match SET archive_key = $text, archive_sha256 = $text, archive_requested = now()
          WHERE game_id = $gameId AND match_id = $matchId""".command

    /** Records that the engine has been given a url to upload `sha256`'s content to under `key`. */
    def recordRequest(game: GameId, id: MatchId, key: String, sha256: String): IO[Unit] =
        session.execute(updateRequest)((key, sha256, game, id)).void

    private val updateArchived: Query[(GameId, MatchId), Instant] =
        sql"""UPDATE match SET archived_at = now()
          WHERE game_id = $gameId AND match_id = $matchId
          RETURNING archived_at""".query(instant)

    /** Records the archive as arrived, as of the database's clock, and answers when that was. */
    def recordArchived(game: GameId, id: MatchId): IO[Instant] =
        session.unique(updateArchived)((game, id))

    private val updateExpired: Command[(GameId, MatchId)] =
        sql"""UPDATE match SET archive_expired_at = now()
          WHERE game_id = $gameId AND match_id = $matchId
            AND archived_at IS NOT NULL AND archive_expired_at IS NULL""".command

    /** Records a friendly archive as gone. The condition is in the update itself, so it needs no lock: two requests
      * that both found the object gone both write the same fact, and the second changes nothing.
      */
    def recordExpired(game: GameId, id: MatchId): IO[Unit] =
        session.execute(updateExpired)((game, id)).void

    private val selectUnarchived: Query[(Instant, Int), (Int, String, Instant)] =
        sql"""SELECT game_id, match_id, completed FROM match
          WHERE completed IS NOT NULL AND archived_at IS NULL AND completed < $instant
          ORDER BY completed
          LIMIT $int4""".query(int4 *: text *: instant)

    /** Completed matches that were never archived and finished before `before`, oldest first. */
    def listUnarchived(before: Instant, limit: Int): IO[List[(GameId, MatchId, Instant)]] =
        session
            .execute(selectUnarchived)((before, limit))
            .map(_.map((game, id, completed) => (GameId(game), MatchId(id), completed)))

    private val selectUnreleased: Query[Int, (Int, String, String)] =
        sql"""SELECT game_id, match_id, cancel_url FROM match
          WHERE cancelled AND cancel_url IS NOT NULL AND engine_released IS NULL
          ORDER BY game_id, match_id
          LIMIT $int4""".query(int4 *: text *: text)

    /** Cancelled matches whose engine has not acknowledged being told. */
    def listUnreleased(limit: Int): IO[List[ReleaseRow]] =
        session
            .execute(selectUnreleased)(limit)
            .map(_.map((game, id, url) => ReleaseRow(GameId(game), MatchId(id), url)))

    private val updateReleased: Command[(GameId, MatchId)] =
        sql"""UPDATE match SET engine_released = now()
          WHERE game_id = $gameId AND match_id = $matchId AND engine_released IS NULL""".command

    /** Records that the engine acknowledged a cancel. Conditional like [[recordExpired]], and for the same reason. */
    def recordReleased(game: GameId, id: MatchId): IO[Unit] =
        session.execute(updateReleased)((game, id)).void
}

object ArchiveRepo {

    /** A match as archiving sees it: enough to decide what an engine may do with its archive, and where it is.
      *
      * @param gameName
      *   the game's `name`, its stable handle — the archive's folder
      */
    case class ArchiveRow(
        gameId: GameId,
        matchId: MatchId,
        gameName: String,
        completedAt: Option[Instant],
        cancelled: Boolean,
        friendly: Boolean,
        key: Option[String],
        sha256: Option[String],
        requestedAt: Option[Instant],
        archivedAt: Option[Instant],
        expiredAt: Option[Instant]
    )

    /** A cancelled match whose engine is still to be told, at `cancelUrl`. */
    case class ReleaseRow(gameId: GameId, matchId: MatchId, cancelUrl: String)
}
