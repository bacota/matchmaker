package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.Player

class PlayerRepo(session: Session[IO]) {

  private val playerRow: Codec[(String, Boolean, String)] = varchar *: bool *: varchar

  private val insertPlayer: Query[(String, Boolean, String), Long] =
    sql"""INSERT INTO player (nickname, is_admin, external_id)
          VALUES ($varchar, $bool, $varchar)
          RETURNING player_id""".query(int8)

  private val selectPlayer: Query[Long, (String, Boolean, String)] =
    sql"""SELECT nickname, is_admin, external_id FROM player WHERE player_id = $int8""".query(playerRow)

  private val updatePlayer: Command[(String, Boolean, String, Long)] =
    sql"""UPDATE player SET nickname = $varchar, is_admin = $bool, external_id = $varchar
          WHERE player_id = $int8""".command

  def create(player: Player): IO[Player] =
    session
      .unique(insertPlayer)((player.nickname, player.isAdmin, player.externalId))
      .map(id => player.copy(playerId = id))

  def read(playerId: Long): IO[Option[Player]] =
    session.option(selectPlayer)(playerId).map(_.map { case (nickname, isAdmin, externalId) =>
      Player(playerId, nickname, isAdmin, externalId)
    })

  def update(player: Player): IO[Unit] =
    session.execute(updatePlayer)((player.nickname, player.isAdmin, player.externalId, player.playerId)).void
}
