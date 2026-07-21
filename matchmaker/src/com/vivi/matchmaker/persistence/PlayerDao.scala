package com.vivi.matchmaker.persistence

import java.sql.{Connection, ResultSet}
import com.vivi.matchmaker.model.Player

class PlayerDao(conn: Connection) {

  def create(player: Player): Player = {
    val stmt = conn.prepareStatement(
      "INSERT INTO player (nickname, is_admin, external_id) VALUES (?, ?, ?) RETURNING player_id"
    )
    try {
      stmt.setString(1, player.nickname)
      stmt.setBoolean(2, player.isAdmin)
      stmt.setString(3, player.externalId)
      val rs = stmt.executeQuery()
      rs.next()
      player.copy(playerId = rs.getLong(1))
    } finally stmt.close()
  }

  def read(playerId: Long): Option[Player] = {
    val stmt = conn.prepareStatement(
      "SELECT player_id, nickname, is_admin, external_id FROM player WHERE player_id = ?"
    )
    try {
      stmt.setLong(1, playerId)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(fromRow(rs)) else None
    } finally stmt.close()
  }

  def update(player: Player): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE player SET nickname = ?, is_admin = ?, external_id = ? WHERE player_id = ?"
    )
    try {
      stmt.setString(1, player.nickname)
      stmt.setBoolean(2, player.isAdmin)
      stmt.setString(3, player.externalId)
      stmt.setLong(4, player.playerId)
      stmt.executeUpdate()
    } finally stmt.close()
  }

  private def fromRow(rs: ResultSet): Player =
    Player(
      rs.getLong("player_id"),
      rs.getString("nickname"),
      rs.getBoolean("is_admin"),
      rs.getString("external_id")
    )
}
