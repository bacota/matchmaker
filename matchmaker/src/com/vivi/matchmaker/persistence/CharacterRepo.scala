package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{Character, CharacterId, GameId, PlayerId}

class CharacterRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
  private val characterId = SkunkIdCodecs.characterId
  private val playerId = SkunkIdCodecs.playerId
  private val state: Codec[T] = SkunkCodecs.jsonAsText[T]

  // character.game_id is BIGINT in the schema (unlike game.game_id, which is INT), so it
  // needs its own bigint-based codec rather than the shared int4-based GameId codec.
  private val gameId: Codec[GameId] = int8.imap(v => GameId(v.toInt))(g => g.value.toLong)

  private val characterRow: Codec[(GameId, String, String, T, Option[PlayerId])] =
    gameId *: text *: text *: state *: playerId.opt

  private val insertCharacter: Query[(GameId, String, String, T, Option[PlayerId]), CharacterId] =
    sql"""INSERT INTO character (game_id, name, description, state, player_id)
          VALUES ($gameId, $text, $text, $state::jsonb, ${playerId.opt})
          RETURNING character_id""".query(characterId)

  private val selectCharacter: Query[CharacterId, (GameId, String, String, T, Option[PlayerId])] =
    sql"""SELECT game_id, name, description, state, player_id FROM character WHERE character_id = $characterId"""
      .query(characterRow)

  private val updateCharacter: Command[(GameId, String, String, T, Option[PlayerId], CharacterId)] =
    sql"""UPDATE character SET game_id = $gameId, name = $text, description = $text,
          state = $state::jsonb, player_id = ${playerId.opt}
          WHERE character_id = $characterId""".command

  def create(character: Character[T]): IO[Character[T]] =
    session.transaction.use { _ =>
      session
        .unique(insertCharacter)(
          (character.gameId, character.name, character.description, character.state, character.playerId)
        )
        .map(id => character.copy(characterId = id))
    }

  def read(id: CharacterId): IO[Option[Character[T]]] =
    session.option(selectCharacter)(id).map(_.map { case (gameId, name, description, state, playerId) =>
      Character(id, gameId, name, description, state, playerId)
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
