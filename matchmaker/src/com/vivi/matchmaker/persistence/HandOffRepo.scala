package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model.GameId
import HandOffRepo.{NewHandOff, TakenHandOff}

/** Sign-ins handed from matchmaker's UI to an engine's page, each kept by its one-time code's hash for a minute at most
  * (design/sign-in-handoff.md). `sign_in_handoff`.
  */
class HandOffRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val instant = SkunkCodecs.instant

    /* The sealed tokens are redacted all the same: a refused insert (the game deleted since its trust was read, say)
     * raises an error whose message lists every argument not redacted, and that message is logged. */
    private val insertRow: Command[(Array[Byte], GameId, String, Array[Byte], Option[Array[Byte]], Instant)] =
        sql"""INSERT INTO sign_in_handoff
            (code_hash, game_id, cognito_id, id_token_sealed, refresh_token_sealed, expires_at)
          VALUES ($bytea, $gameId, $text, ${bytea.redacted}, ${bytea.opt.redacted}, $instant)""".command

    def insert(handOff: NewHandOff): IO[Unit] =
        session
            .execute(insertRow)(
              (
                handOff.codeHash,
                handOff.gameId,
                handOff.cognitoId,
                handOff.idTokenSealed,
                handOff.refreshTokenSealed,
                handOff.expiresAt
              )
            )
            .void

    /* Deleted and read in one statement, so that of any number of calls racing for one code exactly one gets its row.
     * The game's engine identity comes along, for the caller to be checked against, and its trust as it is now. */
    private val deleteReturning
        : Query[Array[Byte], (GameId, String, Array[Byte], Option[Array[Byte]], Instant, String, Boolean)] =
        sql"""WITH taken AS (
            DELETE FROM sign_in_handoff WHERE code_hash = $bytea
            RETURNING game_id, cognito_id, id_token_sealed, refresh_token_sealed, expires_at
          )
          SELECT t.game_id, t.cognito_id, t.id_token_sealed, t.refresh_token_sealed, t.expires_at, g.external_id,
            g.is_trusted
          FROM taken t JOIN game g ON g.game_id = t.game_id"""
            .query(gameId *: text *: bytea *: bytea.opt *: instant *: text *: bool)

    /** The hand-off whose code hashes to `codeHash`, deleted as it is read: a code is taken at most once. */
    def take(codeHash: Array[Byte]): IO[Option[TakenHandOff]] =
        session
            .option(deleteReturning)(codeHash)
            .map(
              _.map((game, cognitoId, idTokenSealed, refreshTokenSealed, expiresAt, externalId, isTrusted) =>
                  TakenHandOff(
                    gameId = game,
                    cognitoId = cognitoId,
                    idTokenSealed = idTokenSealed,
                    refreshTokenSealed = refreshTokenSealed,
                    expiresAt = expiresAt,
                    engineExternalId = externalId,
                    isGameTrusted = isTrusted
                  )
              )
            )

    /* Rows another transaction holds are skipped, not waited for. A redeem holds the row it took until it commits, and
     * sweeps in the same transaction: if that row had expired, two sweeps waiting on each other's taken rows would be
     * a deadlock, and Postgres would abort one of them. A skipped row is left for the next sweep, if its holder rolls
     * back rather than deleting it. */
    private val deleteExpiredRows: Command[Instant] =
        sql"""DELETE FROM sign_in_handoff WHERE code_hash IN (
            SELECT code_hash FROM sign_in_handoff WHERE expires_at < $instant FOR UPDATE SKIP LOCKED
          )""".command

    /** Deletes every hand-off that expired before `now`, but for any another transaction holds. */
    def deleteExpired(now: Instant): IO[Unit] = session.execute(deleteExpiredRows)(now).void

    private val selectTrust: Query[GameId, Boolean] =
        sql"""SELECT is_trusted FROM game WHERE game_id = $gameId""".query(bool)

    /** Whether game `id` is trusted with a refresh token, or `None` if there is no such game. Read plainly: the row
      * written refers to the game, and the foreign key is what stops it outliving a game deleted meanwhile.
      */
    def gameTrust(id: GameId): IO[Option[Boolean]] = session.option(selectTrust)(id)
}

object HandOffRepo {

    /** A hand-off to store. Its tokens come sealed, under a key derived from the code (`HandOffService.seal`): this
      * repo never sees them as they are.
      */
    case class NewHandOff(
        codeHash: Array[Byte],
        gameId: GameId,
        cognitoId: String,
        idTokenSealed: Array[Byte],
        refreshTokenSealed: Option[Array[Byte]],
        expiresAt: Instant
    )

    /** A hand-off as it was taken, with the engine identity of its game (`game.external_id`) and whether the game is
      * trusted now, which may have changed since the code was made.
      */
    case class TakenHandOff(
        gameId: GameId,
        cognitoId: String,
        idTokenSealed: Array[Byte],
        refreshTokenSealed: Option[Array[Byte]],
        expiresAt: Instant,
        engineExternalId: String,
        isGameTrusted: Boolean
    )
}
