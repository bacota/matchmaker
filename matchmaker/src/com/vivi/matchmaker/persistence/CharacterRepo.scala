package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{Character, CharacterId, Game, GameId, GameType, Player, PlayerId}

/** A character together with its owning player and the game it belongs to. */
case class CharacterWithOwnerAndGame[T](character: Character[T], owner: Player, game: Game)

/** A character together with the game it belongs to. The character need not have an owner. */
case class CharacterWithGame[T](character: Character[T], game: Game)

class CharacterRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
  private val characterId = SkunkIdCodecs.characterId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val gameType = SkunkCodecs.gameType
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
    session
      .unique(insertCharacter)(
        (character.gameId, character.name, character.description, character.state, character.playerId)
      )
      .map(id => character.copy(characterId = id))

  def read(id: CharacterId): IO[Option[Character[T]]] =
    session.option(selectCharacter)(id).map(_.map { case (gameId, name, description, state, playerId) =>
      Character(id, gameId, name, description, state, playerId)
    })

  private val withOwnerAndGameRow: Codec[
    (GameId, String, String, T, PlayerId, String, Boolean, String, GameType, String, String, String, Boolean, String)
  ] =
    gameId *: text *: text *: state *: playerId *:
      text *: bool *: text *:
      gameType *: text *: text *: text *: bool *: text

  private val selectCharacterWithOwnerAndGame: Query[
    CharacterId,
    (GameId, String, String, T, PlayerId, String, Boolean, String, GameType, String, String, String, Boolean, String)
  ] =
    sql"""SELECT c.game_id, c.name, c.description, c.state, c.player_id,
                 p.nickname, p.is_admin, p.external_id,
                 g.game_type, g.name, g.description, g.url, g.active, g.external_id
          FROM character c
          JOIN game g ON g.game_id = c.game_id
          JOIN player p ON p.player_id = c.player_id
          WHERE c.character_id = $characterId"""
      .query(withOwnerAndGameRow)

  /* The same query, taking a row lock on the character for the rest of the transaction.
   *
   * `OF c` matters: an unqualified FOR UPDATE would lock the joined game and player rows too,
   * which this has no business doing — it is the character that is about to be written. */
  private val selectCharacterWithOwnerAndGameForUpdate: Query[
    CharacterId,
    (GameId, String, String, T, PlayerId, String, Boolean, String, GameType, String, String, String, Boolean, String)
  ] =
    sql"""SELECT c.game_id, c.name, c.description, c.state, c.player_id,
                 p.nickname, p.is_admin, p.external_id,
                 g.game_type, g.name, g.description, g.url, g.active, g.external_id
          FROM character c
          JOIN game g ON g.game_id = c.game_id
          JOIN player p ON p.player_id = c.player_id
          WHERE c.character_id = $characterId
          FOR UPDATE OF c"""
      .query(withOwnerAndGameRow)

  /** Reads a character together with its owning player and its game, in a single query, by
    * joining the character, player, character_game, and game tables. Returns None both when
    * no character with this id exists and when it has no owning player (since it then has no
    * matching row in this join).
    */
  def readWithOwnerAndGame(id: CharacterId): IO[Option[CharacterWithOwnerAndGame[T]]] =
    session.option(selectCharacterWithOwnerAndGame)(id).map(_.map(toCharacterWithOwnerAndGame(id, _)))

  /** As `readWithOwnerAndGame`, but locking the character row until the enclosing transaction
    * ends, so that a caller which reads, authorizes and then writes cannot have the row change
    * underneath it in between.
    */
  def readWithOwnerAndGameForUpdate(id: CharacterId): IO[Option[CharacterWithOwnerAndGame[T]]] =
    session.option(selectCharacterWithOwnerAndGameForUpdate)(id).map(_.map(toCharacterWithOwnerAndGame(id, _)))

  private def toCharacterWithOwnerAndGame(
      id: CharacterId,
      row: (GameId, String, String, T, PlayerId, String, Boolean, String, GameType, String, String, String, Boolean, String)
  ): CharacterWithOwnerAndGame[T] = row match {
    case (
        charGameId,
        name,
        description,
        state,
        charPlayerId,
        nickname,
        isAdmin,
        externalId,
        gameType,
        gameName,
        gameDescription,
        gameUrl,
        gameActive,
        gameExternalId
      ) =>
      val character = Character(id, charGameId, name, description, state, Some(charPlayerId))
      val player = Player(charPlayerId, nickname, isAdmin, externalId)
      val game = Game(charGameId, gameType, gameName, gameDescription, gameUrl, gameActive, Seq.empty, Seq.empty, gameExternalId)
      CharacterWithOwnerAndGame(character, player, game)
  }

  private val withGameRow: Codec[
    (GameId, String, String, T, Option[PlayerId], GameType, String, String, String, Boolean, String)
  ] = gameId *: text *: text *: state *: playerId.opt *: gameType *: text *: text *: text *: bool *: text

  private val selectCharacterWithGame: Query[
    CharacterId,
    (GameId, String, String, T, Option[PlayerId], GameType, String, String, String, Boolean, String)
  ] =
    sql"""SELECT c.game_id, c.name, c.description, c.state, c.player_id,
                 g.game_type, g.name, g.description, g.url, g.active, g.external_id
          FROM character c
          JOIN game g ON g.game_id = c.game_id
          WHERE c.character_id = $characterId"""
      .query(withGameRow)

  /* The same query, locking the character row. `OF c` again, for the same reason: the game is
   * read here, not written. */
  private val selectCharacterWithGameForUpdate: Query[
    CharacterId,
    (GameId, String, String, T, Option[PlayerId], GameType, String, String, String, Boolean, String)
  ] =
    sql"""SELECT c.game_id, c.name, c.description, c.state, c.player_id,
                 g.game_type, g.name, g.description, g.url, g.active, g.external_id
          FROM character c
          JOIN game g ON g.game_id = c.game_id
          WHERE c.character_id = $characterId
          FOR UPDATE OF c"""
      .query(withGameRow)

  /** Reads a character together with its game, in a single query, joining the character and
    * game tables. Unlike readWithOwnerAndGame, this does not require the character to have an
    * owning player.
    */
  def readWithGame(id: CharacterId): IO[Option[CharacterWithGame[T]]] =
    session.option(selectCharacterWithGame)(id).map(_.map(toCharacterWithGame(id, _)))

  /** As `readWithGame`, but locking the character row until the enclosing transaction ends. */
  def readWithGameForUpdate(id: CharacterId): IO[Option[CharacterWithGame[T]]] =
    session.option(selectCharacterWithGameForUpdate)(id).map(_.map(toCharacterWithGame(id, _)))

  private def toCharacterWithGame(
      id: CharacterId,
      row: (GameId, String, String, T, Option[PlayerId], GameType, String, String, String, Boolean, String)
  ): CharacterWithGame[T] = row match {
    case (charGameId, name, description, state, charPlayerId, gameType, gameName, gameDescription, gameUrl, gameActive, gameExternalId) =>
      val character = Character(id, charGameId, name, description, state, charPlayerId)
      val game = Game(charGameId, gameType, gameName, gameDescription, gameUrl, gameActive, Seq.empty, Seq.empty, gameExternalId)
      CharacterWithGame(character, game)
  }

  // `state` is deliberately not the last column: a trailing abstract-typed codec defeats skunk's
  // twiddle-list resolution, the same wrinkle `characterRow` above works around by ordering.
  private val selectCharactersForPlayerAndGame: Query[(PlayerId, GameId), (CharacterId, String, T, String)] =
    sql"""SELECT character_id, name, state, description
          FROM character
          WHERE player_id = $playerId AND game_id = $gameId
          ORDER BY name"""
      .query(characterId *: text *: state *: text)

  /** Every character this player has in this game.
    *
    * Ordered by name so the list is stable between calls: an unordered query may return rows in
    * whatever order the planner likes, which shows up as a select that reshuffles itself.
    */
  def listForPlayerAndGame(playerId: PlayerId, gameId: GameId): IO[List[Character[T]]] =
    session.execute(selectCharactersForPlayerAndGame)((playerId, gameId)).map(_.map {
      case (id, name, state, description) => Character(id, gameId, name, description, state, Some(playerId))
    })

  def update(character: Character[T]): IO[Unit] =
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
