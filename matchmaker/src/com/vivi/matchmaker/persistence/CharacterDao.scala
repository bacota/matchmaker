package com.vivi.matchmaker.persistence

import java.sql.Connection
import com.vivi.matchmaker.model.Character
import DbUtil._

class CharacterDao[T](conn: Connection)(using codec: TextCodec[T]) {

  def create(character: Character[T]): Character[T] = {
    val stmt = conn.prepareStatement(
      "INSERT INTO character (game_id, name, description, state, player_id) VALUES (?, ?, ?, ?, ?) RETURNING character_id"
    )
    try {
      stmt.setLong(1, character.gameId)
      stmt.setString(2, character.name)
      stmt.setString(3, character.description)
      stmt.setObject(4, jsonb(codec.encode(character.state)))
      setLongOpt(stmt, 5, character.playerId)
      val rs = stmt.executeQuery()
      rs.next()
      character.copy(characterId = rs.getLong(1))
    } finally stmt.close()
  }

  def read(characterId: Long): Option[Character[T]] = {
    val stmt = conn.prepareStatement(
      "SELECT character_id, game_id, name, description, state, player_id FROM character WHERE character_id = ?"
    )
    try {
      stmt.setLong(1, characterId)
      val rs = stmt.executeQuery()
      if (rs.next())
        Some(
          Character(
            rs.getLong("character_id"),
            rs.getLong("game_id"),
            rs.getString("name"),
            rs.getString("description"),
            codec.decode(rs.getString("state")),
            getLongOpt(rs, "player_id")
          )
        )
      else None
    } finally stmt.close()
  }

  def update(character: Character[T]): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE character SET game_id = ?, name = ?, description = ?, state = ?, player_id = ? WHERE character_id = ?"
    )
    try {
      stmt.setLong(1, character.gameId)
      stmt.setString(2, character.name)
      stmt.setString(3, character.description)
      stmt.setObject(4, jsonb(codec.encode(character.state)))
      setLongOpt(stmt, 5, character.playerId)
      stmt.setLong(6, character.characterId)
      stmt.executeUpdate()
    } finally stmt.close()
  }
}
