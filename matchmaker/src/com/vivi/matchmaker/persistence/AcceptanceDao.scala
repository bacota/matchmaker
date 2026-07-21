package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model._

class AcceptanceDao(conn: Connection) {

  def create(a: Acceptance): Acceptance = {
    val stmt = conn.prepareStatement("INSERT INTO acceptance (challenge_id, player_id) VALUES (?, ?)")
    try {
      stmt.setLong(1, a.challengeId)
      stmt.setLong(2, a.playerId)
      stmt.executeUpdate()
    } finally stmt.close()

    a match {
      case pa: PlayerAcceptance =>
        val join = conn.prepareStatement("INSERT INTO player_acceptance (challenge_id, player_id) VALUES (?, ?)")
        try {
          join.setLong(1, pa.challengeId)
          join.setLong(2, pa.playerId)
          join.executeUpdate()
        } finally join.close()
      case ca: CharacterAcceptance =>
        val join = conn.prepareStatement(
          "INSERT INTO character_acceptance (challenge_id, player_id, character_id) VALUES (?, ?, ?)"
        )
        try {
          join.setLong(1, ca.challengeId)
          join.setLong(2, ca.playerId)
          join.setLong(3, ca.characterId)
          join.executeUpdate()
        } finally join.close()
    }
    a
  }

  def read(challengeId: Long, playerId: Long): Option[Acceptance] = {
    val stmt = conn.prepareStatement(
      """SELECT ca.character_id
        |FROM acceptance a
        |LEFT JOIN character_acceptance ca ON ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
        |WHERE a.challenge_id = ? AND a.player_id = ?""".stripMargin
    )
    try {
      stmt.setLong(1, challengeId)
      stmt.setLong(2, playerId)
      val rs = stmt.executeQuery()
      if (!rs.next()) None
      else {
        val characterId = rs.getLong("character_id")
        if (rs.wasNull()) Some(PlayerAcceptance(challengeId, playerId))
        else Some(CharacterAcceptance(challengeId, playerId, characterId))
      }
    } finally stmt.close()
  }

  // Acceptance's only fields are the composite key (plus characterId, which is fixed at
  // creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): Unit = ()
}
