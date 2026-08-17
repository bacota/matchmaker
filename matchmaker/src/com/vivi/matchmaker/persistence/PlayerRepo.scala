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

  private val playerRow: Codec[(String, Boolean, String)] = text *: bool *: text

  private val insertPlayer: Query[(String, Boolean, String), PlayerId] =
    sql"""INSERT INTO player (nickname, is_admin, external_id)
          VALUES ($text, $bool, $text)
          RETURNING player_id""".query(playerId)

  private val selectPlayer: Query[PlayerId, (String, Boolean, String)] =
    sql"""SELECT nickname, is_admin, external_id FROM player WHERE player_id = $playerId""".query(playerRow)

  private val selectPlayerByExternalId: Query[String, (PlayerId, String, Boolean)] =
    sql"""SELECT player_id, nickname, is_admin FROM player WHERE external_id = $text"""
      .query(playerId *: text *: bool)

  /* As GameRepo's lockGameRow: FOR SHARE, because callers reference this player from a row they
   * are inserting rather than modifying the player itself. */
  private val selectPlayerForShare: Query[PlayerId, (String, Boolean, String)] =
    sql"""SELECT nickname, is_admin, external_id FROM player WHERE player_id = $playerId FOR SHARE"""
      .query(playerRow)

  private val selectPlayerByExternalIdForShare: Query[String, (PlayerId, String, Boolean)] =
    sql"""SELECT player_id, nickname, is_admin FROM player WHERE external_id = $text FOR SHARE"""
      .query(playerId *: text *: bool)

  private val updatePlayer: Command[(String, Boolean, String, PlayerId)] =
    sql"""UPDATE player SET nickname = $text, is_admin = $bool, external_id = $text
          WHERE player_id = $playerId""".command

  def create(player: Player): IO[Player] =
    session
      .unique(insertPlayer)((player.nickname, player.isAdmin, player.externalId))
      .map(id => player.copy(playerId = id))

  def read(id: PlayerId): IO[Option[Player]] =
    session.option(selectPlayer)(id).map(_.map { case (nickname, isAdmin, externalId) =>
      Player(id, nickname, isAdmin, externalId)
    })

  def readByExternalId(externalId: String): IO[Option[Player]] =
    session.option(selectPlayerByExternalId)(externalId).map(_.map { case (id, nickname, isAdmin) =>
      Player(id, nickname, isAdmin, externalId)
    })

  /** As `read`, but holding the player row against concurrent modification until the transaction
    * ends.
    */
  def readForShare(id: PlayerId): IO[Option[Player]] =
    session.option(selectPlayerForShare)(id).map(_.map { case (nickname, isAdmin, externalId) =>
      Player(id, nickname, isAdmin, externalId)
    })

  /** As `readByExternalId`, but holding the player row against concurrent modification until the
    * transaction ends.
    */
  def readByExternalIdForShare(externalId: String): IO[Option[Player]] =
    session.option(selectPlayerByExternalIdForShare)(externalId).map(_.map { case (id, nickname, isAdmin) =>
      Player(id, nickname, isAdmin, externalId)
    })

  def update(player: Player): IO[Unit] =
    session.execute(updatePlayer)((player.nickname, player.isAdmin, player.externalId, player.playerId)).void
}
