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

  private val updatePlayer: Command[(String, Boolean, String, PlayerId)] =
    sql"""UPDATE player SET nickname = $text, is_admin = $bool, external_id = $text
          WHERE player_id = $playerId""".command

  def create(player: Player): IO[Player] =
    session.transaction.use { _ =>
      session
        .unique(insertPlayer)((player.nickname, player.isAdmin, player.externalId))
        .map(id => player.copy(playerId = id))
    }

  def read(id: PlayerId): IO[Option[Player]] =
    session.option(selectPlayer)(id).map(_.map { case (nickname, isAdmin, externalId) =>
      Player(id, nickname, isAdmin, externalId)
    })

  def update(player: Player): IO[Unit] =
    session.transaction.use { _ =>
      session.execute(updatePlayer)((player.nickname, player.isAdmin, player.externalId, player.playerId)).void
    }
}
