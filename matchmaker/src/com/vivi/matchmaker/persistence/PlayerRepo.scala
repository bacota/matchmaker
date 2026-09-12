package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{Player, PlayerId}

class PlayerRepo(session: Session[IO]) {
  private val playerId = SkunkIdCodecs.playerId

  private val playerRow: Codec[(String, Boolean, String, Option[String])] = text *: bool *: text *: text.opt

  private val insertPlayer: Query[(String, Boolean, String, Option[String]), PlayerId] =
    sql"""INSERT INTO player (nickname, is_admin, external_id, email)
          VALUES ($text, $bool, $text, ${text.opt})
          RETURNING player_id""".query(playerId)

  private val selectPlayer: Query[PlayerId, (String, Boolean, String, Option[String])] =
    sql"""SELECT nickname, is_admin, external_id, email FROM player WHERE player_id = $playerId""".query(playerRow)

  private val selectPlayerByExternalId: Query[String, (PlayerId, String, Boolean, Option[String])] =
    sql"""SELECT player_id, nickname, is_admin, email FROM player WHERE external_id = $text"""
      .query(playerId *: text *: bool *: text.opt)

  /* As GameRepo's lockGameRow: FOR SHARE, because callers reference this player from a row they
   * are inserting rather than modifying the player itself. */
  private val selectPlayerForShare: Query[PlayerId, (String, Boolean, String, Option[String])] =
    sql"""SELECT nickname, is_admin, external_id, email FROM player WHERE player_id = $playerId FOR SHARE"""
      .query(playerRow)

  private val selectPlayerByExternalIdForShare: Query[String, (PlayerId, String, Boolean, Option[String])] =
    sql"""SELECT player_id, nickname, is_admin, email FROM player WHERE external_id = $text FOR SHARE"""
      .query(playerId *: text *: bool *: text.opt)

  /* Deliberately does not write `email`.
   *
   * Every caller of `update` is changing something else -- a nickname, an admin flag -- and passes
   * a whole `Player` to do it. The address is the one field on that row whose authority lives
   * outside matchmaker: Cognito owns it, and matchmaker's copy is only ever written from a token's
   * verified claim at sign-in. A general update that carried it would mean every such caller
   * quietly restating the address from whatever `Player` it happened to be holding -- a value read
   * minutes earlier, or built by a caller that never had one -- and that is precisely how a
   * confirmed change gets overwritten by a rename.
   *
   * So the column has exactly one writer, `updateEmail` below. */
  private val updatePlayer: Command[(String, Boolean, String, PlayerId)] =
    sql"""UPDATE player SET nickname = $text, is_admin = $bool, external_id = $text
          WHERE player_id = $playerId""".command

  private val updatePlayerEmail: Command[(Option[String], PlayerId)] =
    sql"UPDATE player SET email = ${text.opt} WHERE player_id = $playerId".command

  def create(player: Player): IO[Player] =
    session
      .unique(insertPlayer)((player.nickname, player.isAdmin, player.externalId, player.email))
      .map(id => player.copy(playerId = id))

  def read(id: PlayerId): IO[Option[Player]] =
    session.option(selectPlayer)(id).map(_.map { case (nickname, isAdmin, externalId, email) =>
      Player(id, nickname, isAdmin, externalId, email)
    })

  def readByExternalId(externalId: String): IO[Option[Player]] =
    session.option(selectPlayerByExternalId)(externalId).map(_.map { case (id, nickname, isAdmin, email) =>
      Player(id, nickname, isAdmin, externalId, email)
    })

  /** As `read`, but holding the player row against concurrent modification until the transaction
    * ends.
    */
  def readForShare(id: PlayerId): IO[Option[Player]] =
    session.option(selectPlayerForShare)(id).map(_.map { case (nickname, isAdmin, externalId, email) =>
      Player(id, nickname, isAdmin, externalId, email)
    })

  /** As `readByExternalId`, but holding the player row against concurrent modification until the
    * transaction ends.
    */
  def readByExternalIdForShare(externalId: String): IO[Option[Player]] =
    session.option(selectPlayerByExternalIdForShare)(externalId).map(_.map { case (id, nickname, isAdmin, email) =>
      Player(id, nickname, isAdmin, externalId, email)
    })

  /** Writes everything about a player except their address. See `updatePlayer` for why the
    * exception, and `updateEmail` for the one thing that writes it.
    */
  def update(player: Player): IO[Unit] =
    session.execute(updatePlayer)((player.nickname, player.isAdmin, player.externalId, player.playerId)).void

  /** Records where a player can be reached, and nothing else about them.
    *
    * The only writer of `player.email`. Its one caller is `PlayerService.updateEmail`, which is
    * reached only from the sign-in path -- so the address stored here always came from the `email`
    * claim of a token Cognito had just issued, which is the only statement about an address that
    * is worth anything: Cognito owns the address, verified it, and signs the player in with it.
    *
    * Takes a `PlayerId` rather than a `Player` on purpose. A whole `Player` would invite a caller
    * to pass one it read earlier and write four stale fields to correct one, which is the mistake
    * splitting this out exists to prevent.
    */
  def updateEmail(id: PlayerId, email: Option[String]): IO[Unit] =
    session.execute(updatePlayerEmail)((email, id)).void
}
