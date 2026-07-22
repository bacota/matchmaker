package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.Character

class CharacterRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
  private val state: Codec[T] = SkunkCodecs.jsonAsText[T]

  private val characterRow: Codec[(Long, String, String, T, Option[Long])] =
    int8 *: varchar *: varchar *: state *: int8.opt

  private val insertCharacter: Query[(Long, String, String, T, Option[Long]), Long] =
    sql"""INSERT INTO character (game_id, name, description, state, player_id)
          VALUES ($int8, $varchar, $varchar, $state::jsonb, ${int8.opt})
          RETURNING character_id""".query(int8)

  private val selectCharacter: Query[Long, (Long, String, String, T, Option[Long])] =
    sql"""SELECT game_id, name, description, state, player_id FROM character WHERE character_id = $int8"""
      .query(characterRow)

  private val updateCharacter: Command[(Long, String, String, T, Option[Long], Long)] =
    sql"""UPDATE character SET game_id = $int8, name = $varchar, description = $varchar,
          state = $state::jsonb, player_id = ${int8.opt}
          WHERE character_id = $int8""".command

  def create(character: Character[T]): IO[Character[T]] =
    session.transaction.use { _ =>
      session
        .unique(insertCharacter)(
          (character.gameId, character.name, character.description, character.state, character.playerId)
        )
        .map(id => character.copy(characterId = id))
    }

  def read(characterId: Long): IO[Option[Character[T]]] =
    session.option(selectCharacter)(characterId).map(_.map { case (gameId, name, description, state, playerId) =>
      Character(characterId, gameId, name, description, state, playerId)
    })

  def update(character: Character[T]): IO[Unit] =
    session.transaction.use { _ =>
      session
        .execute(updateCharacter)(
          (
            character.gameId,
            character.name,
            character.description,
            character.state,
            character.playerId,
            character.characterId
          )
        )
        .void
    }
}
