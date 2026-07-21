package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model._
import DbUtil._

class OpenChallengeDao(conn: Connection) {

  def create(c: OpenChallenge): OpenChallenge = {
    val stmt = conn.prepareStatement(
      "INSERT INTO open_challenge (challenger, message, number_of_players, start, time_limit, settings) VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING challenge_id"
    )
    val challengeId =
      try {
        stmt.setLong(1, c.challenger)
        stmt.setString(2, c.message)
        stmt.setShort(3, c.numberOfPlayers)
        setInstantOpt(stmt, 4, c.start)
        setDurationOpt(stmt, 5, c.timeLimit)
        stmt.setString(6, c.settings)
        val rs = stmt.executeQuery()
        rs.next()
        rs.getLong(1)
      } finally stmt.close()

    c match {
      case poc: PlayerOpenChallenge =>
        val join = conn.prepareStatement("INSERT INTO player_open_challenge (challenge_id, game_id) VALUES (?, ?)")
        try {
          join.setLong(1, challengeId)
          join.setInt(2, poc.gameId)
          join.executeUpdate()
        } finally join.close()
        poc.copy(challengeId = challengeId)
      case coc: CharacterOpenChallenge =>
        val join = conn.prepareStatement(
          "INSERT INTO character_open_challenge (challenge_id, character_id, game_id) VALUES (?, ?, ?)"
        )
        try {
          join.setLong(1, challengeId)
          join.setLong(2, coc.characterId)
          join.setInt(3, coc.gameId)
          join.executeUpdate()
        } finally join.close()
        coc.copy(challengeId = challengeId)
    }
  }

  def read(challengeId: Long): Option[OpenChallenge] = {
    val stmt = conn.prepareStatement(
      """SELECT o.challenger, o.message, o.number_of_players, o.start, o.time_limit, o.settings,
        |       poc.game_id AS player_game_id, coc.game_id AS character_game_id, coc.character_id
        |FROM open_challenge o
        |LEFT JOIN player_open_challenge poc ON poc.challenge_id = o.challenge_id
        |LEFT JOIN character_open_challenge coc ON coc.challenge_id = o.challenge_id
        |WHERE o.challenge_id = ?""".stripMargin
    )
    try {
      stmt.setLong(1, challengeId)
      val rs = stmt.executeQuery()
      if (!rs.next()) None
      else {
        val challenger = rs.getLong("challenger")
        val message = rs.getString("message")
        val numberOfPlayers = rs.getShort("number_of_players")
        val start = getInstantOpt(rs, "start")
        val timeLimit = getDurationOpt(rs, "time_limit")
        val settings = rs.getString("settings")
        val playerGameId = rs.getInt("player_game_id")
        val isPlayerChallenge = !rs.wasNull()
        if (isPlayerChallenge)
          Some(
            PlayerOpenChallenge(challengeId, challenger, message, numberOfPlayers, start, timeLimit, settings, playerGameId)
          )
        else
          Some(
            CharacterOpenChallenge(
              challengeId,
              challenger,
              message,
              numberOfPlayers,
              start,
              timeLimit,
              settings,
              rs.getInt("character_game_id"),
              rs.getLong("character_id")
            )
          )
      }
    } finally stmt.close()
  }

  def update(c: OpenChallenge): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE open_challenge SET challenger = ?, message = ?, number_of_players = ?, start = ?, time_limit = ?, settings = ?::jsonb WHERE challenge_id = ?"
    )
    try {
      stmt.setLong(1, c.challenger)
      stmt.setString(2, c.message)
      stmt.setShort(3, c.numberOfPlayers)
      setInstantOpt(stmt, 4, c.start)
      setDurationOpt(stmt, 5, c.timeLimit)
      stmt.setString(6, c.settings)
      stmt.setLong(7, c.challengeId)
      stmt.executeUpdate()
    } finally stmt.close()
  }
}
