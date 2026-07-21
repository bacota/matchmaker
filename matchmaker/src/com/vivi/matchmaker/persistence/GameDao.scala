package com.vivi.matchmaker.persistence

import java.sql.Types
import java.sql.Connection
import com.vivi.matchmaker.model._

/** Game, its roles, its parameters, and its parameter values are always read, written,
  * and updated together, so this DAO persists the whole aggregate in one call.
  */
class GameDao[T](conn: Connection)(using codec: TextCodec[T]) {

  def create(game: Game): Game = {
    val gameId = insertGameRow(game)
    game match {
      case _: PlayerGame    => insertJoinRow("player_game", gameId)
      case _: CharacterGame => insertJoinRow("character_game", gameId)
    }
    val roles = game.roles.map(insertRole(gameId, _))
    val parameters = game.parameters.map(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
    build(game, gameId, roles, parameters)
  }

  def read(gameId: Int): Option[Game] = {
    val stmt = conn.prepareStatement(
      """SELECT g.name, g.description, g.url, g.active,
        |       (pg.game_id IS NOT NULL) AS is_player_game
        |FROM game g
        |LEFT JOIN player_game pg ON pg.game_id = g.game_id
        |WHERE g.game_id = ?""".stripMargin
    )
    try {
      stmt.setInt(1, gameId)
      val rs = stmt.executeQuery()
      if (!rs.next()) None
      else {
        val name = rs.getString("name")
        val description = rs.getString("description")
        val url = rs.getString("url")
        val active = rs.getBoolean("active")
        val isPlayerGame = rs.getBoolean("is_player_game")
        val roles = readRoles(gameId)
        val parameters = readParameters(gameId)
        Some(
          if (isPlayerGame) PlayerGame(gameId, name, description, url, active, roles, parameters)
          else CharacterGame(gameId, name, description, url, active, roles, parameters)
        )
      }
    } finally stmt.close()
  }

  def update(game: Game): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE game SET name = ?, description = ?, url = ?, active = ? WHERE game_id = ?"
    )
    try {
      stmt.setString(1, game.name)
      stmt.setString(2, game.description)
      stmt.setString(3, game.url)
      stmt.setBoolean(4, game.active)
      stmt.setInt(5, game.gameId)
      stmt.executeUpdate()
    } finally stmt.close()

    replaceRoles(game.gameId, game.roles)
    replaceParameters(game.gameId, game.parameters)
  }

  private def insertGameRow(game: Game): Int = {
    val stmt = conn.prepareStatement(
      "INSERT INTO game (name, description, url, active) VALUES (?, ?, ?, ?) RETURNING game_id"
    )
    try {
      stmt.setString(1, game.name)
      stmt.setString(2, game.description)
      stmt.setString(3, game.url)
      stmt.setBoolean(4, game.active)
      val rs = stmt.executeQuery()
      rs.next()
      rs.getInt(1)
    } finally stmt.close()
  }

  private def insertJoinRow(table: String, gameId: Int): Unit = {
    val stmt = conn.prepareStatement(s"INSERT INTO $table (game_id) VALUES (?)")
    try {
      stmt.setInt(1, gameId)
      stmt.executeUpdate()
    } finally stmt.close()
  }

  private def insertRole(gameId: Int, role: GameRole): GameRole = {
    val stmt = conn.prepareStatement(
      "INSERT INTO game_role (game_id, name, optional) VALUES (?, ?, ?) RETURNING game_role_id"
    )
    try {
      stmt.setInt(1, gameId)
      stmt.setString(2, role.name)
      stmt.setBoolean(3, role.optional)
      val rs = stmt.executeQuery()
      rs.next()
      role.copy(gameRoleId = rs.getInt(1), gameId = gameId)
    } finally stmt.close()
  }

  private def readRoles(gameId: Int): Seq[GameRole] = {
    val stmt = conn.prepareStatement(
      "SELECT game_role_id, name, optional FROM game_role WHERE game_id = ?"
    )
    try {
      stmt.setInt(1, gameId)
      val rs = stmt.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[GameRole]
      while (rs.next())
        buf += GameRole(rs.getInt("game_role_id"), gameId, rs.getString("name"), rs.getBoolean("optional"))
      buf.toSeq
    } finally stmt.close()
  }

  private def replaceRoles(gameId: Int, roles: Seq[GameRole]): Unit = {
    val del = conn.prepareStatement("DELETE FROM game_role WHERE game_id = ?")
    try { del.setInt(1, gameId); del.executeUpdate() } finally del.close()
    roles.foreach(r => insertRole(gameId, r))
  }

  // game_parameter.default_value has a composite FK to game_parameter_value(game_id,
  // game_parameter_id, value), so the parameter row must be inserted before its values
  // exist, and default_value can only be set once a matching value row is present.
  private def insertParameter(gameId: Int, parameter: GameParameter[T]): GameParameter[T] = {
    val insertStmt = conn.prepareStatement(
      "INSERT INTO game_parameter (game_id, name) VALUES (?, ?) RETURNING game_parameter_id"
    )
    val parameterId =
      try {
        insertStmt.setInt(1, gameId)
        insertStmt.setString(2, parameter.name)
        val rs = insertStmt.executeQuery()
        rs.next()
        rs.getInt(1)
      } finally insertStmt.close()

    val values = parameter.values.map(v => insertParameterValue(gameId, parameterId, v))

    parameter.defaultValue.foreach { v =>
      val updateStmt = conn.prepareStatement(
        "UPDATE game_parameter SET default_value = ? WHERE game_id = ? AND game_parameter_id = ?"
      )
      try {
        updateStmt.setString(1, codec.encode(v))
        updateStmt.setInt(2, gameId)
        updateStmt.setInt(3, parameterId)
        updateStmt.executeUpdate()
      } finally updateStmt.close()
    }

    parameter.copy(gameId = gameId, gameParameterId = parameterId, values = values)
  }

  private def insertParameterValue(
      gameId: Int,
      parameterId: Int,
      value: GameParameterValue[T]
  ): GameParameterValue[T] = {
    val stmt = conn.prepareStatement(
      "INSERT INTO game_parameter_value (game_id, game_parameter_id, value) VALUES (?, ?, ?)"
    )
    try {
      stmt.setInt(1, gameId)
      stmt.setInt(2, parameterId)
      stmt.setString(3, codec.encode(value.value))
      stmt.executeUpdate()
    } finally stmt.close()
    value.copy(gameId = gameId, gameParameterId = parameterId)
  }

  private def readParameters(gameId: Int): Seq[GameParameter[T]] = {
    val stmt = conn.prepareStatement(
      "SELECT game_parameter_id, name, default_value FROM game_parameter WHERE game_id = ?"
    )
    try {
      stmt.setInt(1, gameId)
      val rs = stmt.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[GameParameter[T]]
      while (rs.next()) {
        val parameterId = rs.getInt("game_parameter_id")
        val defaultValue = Option(rs.getString("default_value")).map(codec.decode)
        val values = readParameterValues(gameId, parameterId)
        buf += GameParameter(gameId, parameterId, rs.getString("name"), defaultValue, values)
      }
      buf.toSeq
    } finally stmt.close()
  }

  private def readParameterValues(gameId: Int, parameterId: Int): Seq[GameParameterValue[T]] = {
    val stmt = conn.prepareStatement(
      "SELECT value FROM game_parameter_value WHERE game_id = ? AND game_parameter_id = ?"
    )
    try {
      stmt.setInt(1, gameId)
      stmt.setInt(2, parameterId)
      val rs = stmt.executeQuery()
      val buf = scala.collection.mutable.ArrayBuffer.empty[GameParameterValue[T]]
      while (rs.next())
        buf += GameParameterValue(gameId, parameterId, codec.decode(rs.getString("value")))
      buf.toSeq
    } finally stmt.close()
  }

  private def replaceParameters(gameId: Int, parameters: Seq[GameParameter[_]]): Unit = {
    val clearDefaults = conn.prepareStatement("UPDATE game_parameter SET default_value = NULL WHERE game_id = ?")
    try { clearDefaults.setInt(1, gameId); clearDefaults.executeUpdate() } finally clearDefaults.close()

    val delValues = conn.prepareStatement("DELETE FROM game_parameter_value WHERE game_id = ?")
    try { delValues.setInt(1, gameId); delValues.executeUpdate() } finally delValues.close()

    val delParams = conn.prepareStatement("DELETE FROM game_parameter WHERE game_id = ?")
    try { delParams.setInt(1, gameId); delParams.executeUpdate() } finally delParams.close()

    parameters.foreach(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
  }

  private def build(game: Game, gameId: Int, roles: Seq[GameRole], parameters: Seq[GameParameter[T]]): Game =
    game match {
      case g: PlayerGame    => g.copy(gameId = gameId, roles = roles, parameters = parameters)
      case g: CharacterGame => g.copy(gameId = gameId, roles = roles, parameters = parameters)
    }
}
