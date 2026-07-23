package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{Character, CharacterId, Game, GameId, Player, PlayerId}

class CharacterRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
  private val characterId = SkunkIdCodecs.characterId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val state: Codec[T] = SkunkCodecs.jsonAsText[T]

  private val characterRow: Codec[(GameId, String, String, T, Option[PlayerId])] =
    gameId *: text *: text *: state *: playerId.opt

  private val insertCharacter: Query[(GameId, String, String, T, Option[PlayerId]), CharacterId] =
    sql"""INSERT INTO character (game_id, name, description, state, player_id)
          VALUES ($gameId, $text, $text, $state, ${playerId.opt})
          RETURNING character_id""".query(characterId)

  private val selectCharacter: Query[CharacterId, (GameId, String, String, T, Option[PlayerId])] =
    sql"""SELECT game_id, name, description, state, player_id FROM character WHERE character_id = $characterId"""
      .query(characterRow)

  private val updateCharacter: Command[(GameId, String, String, T, Option[PlayerId], CharacterId)] =
    sql"""UPDATE character SET game_id = $gameId, name = $text, description = $text,
          state = $state, player_id = ${playerId.opt}
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

  private val selectCharacterWithOwnerAndGame: Query[
    CharacterId,
    (GameId, String, String, T, PlayerId, String, Boolean, String, String, String, String, Boolean, String)
  ] =
    sql"""SELECT c.game_id, c.name, c.description, c.state, c.player_id,
                 p.nickname, p.is_admin, p.external_id,
                 g.name, g.description, g.url, g.active, g.external_id
          FROM character c
          JOIN game g ON g.game_id = c.game_id
          JOIN player p ON p.player_id = c.player_id
          WHERE c.character_id = $characterId"""
      .query(
        gameId *: text *: text *: state *: playerId *:
          text *: bool *: text *:
          text *: text *: text *: bool *: text
      )

  /** Reads a character together with its owning player and its game, in a single query, by
    * joining the character, player, character_game, and game tables. Returns None both when
    * no character with this id exists and when it has no owning player (since it then has no
    * matching row in this join).
    */
  def readWithOwnerAndGame(id: CharacterId): IO[Option[(Character[T], Player, Game)]] =
    session.option(selectCharacterWithOwnerAndGame)(id).map(_.map {
      case (
            charGameId,
            name,
            description,
            state,
            charPlayerId,
            nickname,
            isAdmin,
            externalId,
            gameName,
            gameDescription,
            gameUrl,
            gameActive,
            gameExternalId
          ) =>
        val character = Character(id, charGameId, name, description, state, Some(charPlayerId))
        val player = Player(charPlayerId, nickname, isAdmin, externalId)
        val game = Game(charGameId, gameName, gameDescription, gameUrl, gameActive, Seq.empty, Seq.empty, gameExternalId)
        (character, player, game)
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
