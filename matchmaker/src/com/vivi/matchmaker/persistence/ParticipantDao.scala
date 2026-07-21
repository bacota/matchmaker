package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model._
import DbUtil._

class ParticipantDao(conn: Connection) {

  def create(p: Participant): Participant = {
    val stmt = conn.prepareStatement(
      "INSERT INTO participant (game_id, match_id, player_id, pending, completed, due) VALUES (?, ?, ?, ?, ?, ?) RETURNING participant_id"
    )
    val participantId =
      try {
        stmt.setInt(1, p.gameId)
        stmt.setString(2, p.matchId)
        stmt.setLong(3, p.playerId)
        stmt.setBoolean(4, p.pending)
        stmt.setBoolean(5, p.completed)
        setInstantOpt(stmt, 6, p.due)
        val rs = stmt.executeQuery()
        rs.next()
        rs.getLong(1)
      } finally stmt.close()

    p match {
      case pp: PlayerParticipant =>
        val join = conn.prepareStatement(
          "INSERT INTO player_participant (participant_id, game_id, match_id, game_role_id) VALUES (?, ?, ?, ?)"
        )
        try {
          join.setLong(1, participantId)
          join.setInt(2, pp.gameId)
          join.setString(3, pp.matchId)
          join.setInt(4, pp.gameRoleId)
          join.executeUpdate()
        } finally join.close()
        pp.copy(participantId = participantId)
      case cp: CharacterParticipant =>
        val join = conn.prepareStatement(
          "INSERT INTO character_participant (participant_id, game_id, match_id, character_id) VALUES (?, ?, ?, ?)"
        )
        try {
          join.setLong(1, participantId)
          join.setInt(2, cp.gameId)
          join.setString(3, cp.matchId)
          join.setLong(4, cp.characterId)
          join.executeUpdate()
        } finally join.close()
        cp.copy(participantId = participantId)
    }
  }

  def read(participantId: Long): Option[Participant] = {
    val stmt = conn.prepareStatement(
      """SELECT p.game_id, p.match_id, p.player_id, p.pending, p.completed, p.due,
        |       pp.game_role_id, cp.character_id
        |FROM participant p
        |LEFT JOIN player_participant pp ON pp.participant_id = p.participant_id
        |LEFT JOIN character_participant cp ON cp.participant_id = p.participant_id
        |WHERE p.participant_id = ?""".stripMargin
    )
    try {
      stmt.setLong(1, participantId)
      val rs = stmt.executeQuery()
      if (!rs.next()) None
      else {
        val gameId = rs.getInt("game_id")
        val matchId = rs.getString("match_id")
        val playerId = rs.getLong("player_id")
        val pending = rs.getBoolean("pending")
        val completed = rs.getBoolean("completed")
        val due = getInstantOpt(rs, "due")
        val gameRoleId = rs.getInt("game_role_id")
        val isPlayerParticipant = !rs.wasNull()
        if (isPlayerParticipant)
          Some(PlayerParticipant(participantId, gameId, matchId, playerId, pending, completed, due, gameRoleId))
        else
          Some(
            CharacterParticipant(
              participantId,
              gameId,
              matchId,
              playerId,
              pending,
              completed,
              due,
              rs.getLong("character_id")
            )
          )
      }
    } finally stmt.close()
  }

  def update(p: Participant): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE participant SET player_id = ?, pending = ?, completed = ?, due = ? WHERE participant_id = ?"
    )
    try {
      stmt.setLong(1, p.playerId)
      stmt.setBoolean(2, p.pending)
      stmt.setBoolean(3, p.completed)
      setInstantOpt(stmt, 4, p.due)
      stmt.setLong(5, p.participantId)
      stmt.executeUpdate()
    } finally stmt.close()

    p match {
      case pp: PlayerParticipant =>
        val join = conn.prepareStatement("UPDATE player_participant SET game_role_id = ? WHERE participant_id = ?")
        try {
          join.setInt(1, pp.gameRoleId)
          join.setLong(2, pp.participantId)
          join.executeUpdate()
        } finally join.close()
      case cp: CharacterParticipant =>
        val join = conn.prepareStatement("UPDATE character_participant SET character_id = ? WHERE participant_id = ?")
        try {
          join.setLong(1, cp.characterId)
          join.setLong(2, cp.participantId)
          join.executeUpdate()
        } finally join.close()
    }
  }
}
