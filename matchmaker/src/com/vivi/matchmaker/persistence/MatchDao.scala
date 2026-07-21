package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model._
import DbUtil._

class MatchDao(conn: Connection) {

  def create(m: Match): Match = {
    val stmt = conn.prepareStatement(
      "INSERT INTO match (game_id, match_id, description, completed, start, time_limit, settings) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)"
    )
    try {
      stmt.setInt(1, m.gameId)
      stmt.setString(2, m.matchId)
      stmt.setString(3, m.description)
      stmt.setBoolean(4, m.completed)
      setInstant(stmt, 5, m.start)
      setDurationOpt(stmt, 6, m.timeLimit)
      stmt.setString(7, m.settings)
      stmt.executeUpdate()
    } finally stmt.close()

    val table = m match {
      case _: PlayerMatch    => "player_match"
      case _: CharacterMatch => "character_match"
    }
    val joinStmt = conn.prepareStatement(s"INSERT INTO $table (game_id, match_id) VALUES (?, ?)")
    try {
      joinStmt.setInt(1, m.gameId)
      joinStmt.setString(2, m.matchId)
      joinStmt.executeUpdate()
    } finally joinStmt.close()

    m
  }

  def read(gameId: Int, matchId: String): Option[Match] = {
    val stmt = conn.prepareStatement(
      """SELECT m.description, m.completed, m.start, m.time_limit, m.settings,
        |       (pm.game_id IS NOT NULL) AS is_player_match
        |FROM match m
        |LEFT JOIN player_match pm ON pm.game_id = m.game_id AND pm.match_id = m.match_id
        |WHERE m.game_id = ? AND m.match_id = ?""".stripMargin
    )
    try {
      stmt.setInt(1, gameId)
      stmt.setString(2, matchId)
      val rs = stmt.executeQuery()
      if (!rs.next()) None
      else {
        val description = rs.getString("description")
        val completed = rs.getBoolean("completed")
        val start = getInstant(rs, "start")
        val timeLimit = getDurationOpt(rs, "time_limit")
        val settings = rs.getString("settings")
        val isPlayerMatch = rs.getBoolean("is_player_match")
        Some(
          if (isPlayerMatch) PlayerMatch(gameId, matchId, description, completed, start, timeLimit, settings)
          else CharacterMatch(gameId, matchId, description, completed, start, timeLimit, settings)
        )
      }
    } finally stmt.close()
  }

  def update(m: Match): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE match SET description = ?, completed = ?, start = ?, time_limit = ?, settings = ?::jsonb WHERE game_id = ? AND match_id = ?"
    )
    try {
      stmt.setString(1, m.description)
      stmt.setBoolean(2, m.completed)
      setInstant(stmt, 3, m.start)
      setDurationOpt(stmt, 4, m.timeLimit)
      stmt.setString(5, m.settings)
      stmt.setInt(6, m.gameId)
      stmt.setString(7, m.matchId)
      stmt.executeUpdate()
    } finally stmt.close()
  }
}
