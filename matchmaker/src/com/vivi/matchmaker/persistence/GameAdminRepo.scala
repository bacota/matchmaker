package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{GameAdmin, GameId, PlayerId, PublicPlayer}

/** Who administers which game, and who made them one (V35). Who may change that is
  * [[com.vivi.matchmaker.service.GameAdminService]]'s business, not this one's.
  */
class GameAdminRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId

    private val insert: Command[(PlayerId, GameId, PlayerId)] =
        sql"""INSERT INTO game_admin (player_id, game_id, granted_by) VALUES ($playerId, $gameId, $playerId)
          ON CONFLICT (player_id, game_id) DO NOTHING""".command

    private val delete: Command[(PlayerId, GameId)] =
        sql"DELETE FROM game_admin WHERE player_id = $playerId AND game_id = $gameId".command

    private val selectForShare: Query[(PlayerId, GameId), PlayerId] =
        sql"""SELECT player_id FROM game_admin WHERE player_id = $playerId AND game_id = $gameId FOR SHARE"""
            .query(playerId)

    private val selectGrantedByForUpdate: Query[(PlayerId, GameId), PlayerId] =
        sql"""SELECT granted_by FROM game_admin WHERE player_id = $playerId AND game_id = $gameId FOR UPDATE"""
            .query(playerId)

    private val selectForGame: Query[GameId, (PlayerId, String, PlayerId, String)] =
        sql"""SELECT p.player_id, p.nickname, g.player_id, g.nickname
          FROM game_admin a
          JOIN player p ON p.player_id = a.player_id
          JOIN player g ON g.player_id = a.granted_by
          WHERE a.game_id = $gameId
          ORDER BY p.nickname""".query(playerId *: text *: playerId *: text)

    /** Makes `player` an admin of the game on `grantedBy`'s say. Making an admin of somebody who already is one changes
      * nothing, and in particular not who made them one.
      */
    def grant(player: PlayerId, game: GameId, grantedBy: PlayerId): IO[Unit] =
        session.execute(insert)((player, game, grantedBy)).void

    /** Takes the player's admin of the game away. */
    def revoke(player: PlayerId, game: GameId): IO[Unit] = session.execute(delete)((player, game)).void

    /** Whether the player administers the game, holding the answer until the transaction ends: FOR SHARE, so that a
      * revoke of it waits for whatever this answer is deciding, rather than landing between the check and the write.
      */
    def isAdminForShare(player: PlayerId, game: GameId): IO[Boolean] =
        session.option(selectForShare)((player, game)).map(_.isDefined)

    /** Who made the player an admin of the game, or nothing if they are not one — locked, for a revoke that is decided
      * by the answer and then deletes the row.
      */
    def grantedByForUpdate(player: PlayerId, game: GameId): IO[Option[PlayerId]] =
        session.option(selectGrantedByForUpdate)((player, game))

    /** The game's admins by nickname, each with who made them one, as anybody may see them. */
    def listForGame(game: GameId): IO[List[GameAdmin]] =
        session
            .execute(selectForGame)(game)
            .map(
              _.map((id, nickname, byId, byNickname) =>
                  GameAdmin(PublicPlayer(id, nickname), PublicPlayer(byId, byNickname))
              )
            )
}
