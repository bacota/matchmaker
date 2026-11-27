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

    /* The tokens are redacted: a refused insert (the game deleted since its trust was read, say) raises an error whose
     * message lists every argument not redacted, and that message is logged. Nothing logs a token. */
    private val insertRow: Command[(Array[Byte], GameId, String, String, Option[String], Instant)] =
        sql"""INSERT INTO sign_in_handoff (code_hash, game_id, cognito_id, id_token, refresh_token, expires_at)
          VALUES ($bytea, $gameId, $text, ${text.redacted}, ${text.opt.redacted}, $instant)""".command

    def insert(handOff: NewHandOff): IO[Unit] =
        session
            .execute(insertRow)(
              (
                handOff.codeHash,
                handOff.gameId,
                handOff.cognitoId,
                handOff.idToken,
                handOff.refreshToken,
                handOff.expiresAt
              )
            )
            .void

    /* Deleted and read in one statement, so that of any number of calls racing for one code exactly one gets its row.
     * The game's engine identity comes along, for the caller to be checked against. */
    private val deleteReturning: Query[Array[Byte], (GameId, String, String, Option[String], Instant, String)] =
        sql"""WITH taken AS (
            DELETE FROM sign_in_handoff WHERE code_hash = $bytea
            RETURNING game_id, cognito_id, id_token, refresh_token, expires_at
          )
          SELECT t.game_id, t.cognito_id, t.id_token, t.refresh_token, t.expires_at, g.external_id
          FROM taken t JOIN game g ON g.game_id = t.game_id"""
            .query(gameId *: text *: text *: text.opt *: instant *: text)

    /** The hand-off whose code hashes to `codeHash`, deleted as it is read: a code is taken at most once. */
    def take(codeHash: Array[Byte]): IO[Option[TakenHandOff]] =
        session
            .option(deleteReturning)(codeHash)
            .map(
              _.map((game, cognitoId, idToken, refreshToken, expiresAt, externalId) =>
                  TakenHandOff(
                    gameId = game,
                    cognitoId = cognitoId,
                    idToken = idToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    engineExternalId = externalId
                  )
              )
            )

    private val deleteExpiredRows: Command[Instant] =
        sql"""DELETE FROM sign_in_handoff WHERE expires_at < $instant""".command

    /** Deletes every hand-off that expired before `now`. */
    def deleteExpired(now: Instant): IO[Unit] = session.execute(deleteExpiredRows)(now).void

    private val selectTrust: Query[GameId, Boolean] =
        sql"""SELECT is_trusted FROM game WHERE game_id = $gameId""".query(bool)

    /** Whether game `id` is trusted with a refresh token, or `None` if there is no such game. Read plainly: the row
      * written refers to the game, and the foreign key is what stops it outliving a game deleted meanwhile.
      */
    def gameTrust(id: GameId): IO[Option[Boolean]] = session.option(selectTrust)(id)
}

object HandOffRepo {

    case class NewHandOff(
        codeHash: Array[Byte],
        gameId: GameId,
        cognitoId: String,
        idToken: String,
        refreshToken: Option[String],
        expiresAt: Instant
    )

    /** A hand-off as it was taken, with the engine identity of its game: `game.external_id`. */
    case class TakenHandOff(
        gameId: GameId,
        cognitoId: String,
        idToken: String,
        refreshToken: Option[String],
        expiresAt: Instant,
        engineExternalId: String
    )
}
