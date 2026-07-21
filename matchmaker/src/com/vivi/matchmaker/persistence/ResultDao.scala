package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model.Result

class ResultDao(conn: Connection) {

  def create(result: Result): Result = {
    val stmt = conn.prepareStatement("INSERT INTO result (participant_id, rank, score) VALUES (?, ?, ?)")
    try {
      stmt.setLong(1, result.participantId)
      stmt.setInt(2, result.rank)
      stmt.setDouble(3, result.score)
      stmt.executeUpdate()
    } finally stmt.close()
    result
  }

  def read(participantId: Long): Option[Result] = {
    val stmt = conn.prepareStatement("SELECT rank, score FROM result WHERE participant_id = ?")
    try {
      stmt.setLong(1, participantId)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(Result(participantId, rs.getInt("rank"), rs.getDouble("score")))
      else None
    } finally stmt.close()
  }

  def update(result: Result): Unit = {
    val stmt = conn.prepareStatement("UPDATE result SET rank = ?, score = ? WHERE participant_id = ?")
    try {
      stmt.setInt(1, result.rank)
      stmt.setDouble(2, result.score)
      stmt.setLong(3, result.participantId)
      stmt.executeUpdate()
    } finally stmt.close()
  }
}
