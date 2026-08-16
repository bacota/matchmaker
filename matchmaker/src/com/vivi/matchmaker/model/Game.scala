package com.vivi.matchmaker.model

case class GameParameterValue[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    value: T
)

case class GameParameter[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    name: String,
    defaultValue: Option[T],
    values: Seq[GameParameterValue[T]]
)

case class GameRole(
    gameRoleId: GameRoleId,
    gameId: GameId,
    name: String,
    optional: Boolean
)

/** Whether a game's challenges/acceptances/participants require an attached character.
  * Mirrors the `game_type` discriminator column (`'C'`/`'P'`) that `game` and every table
  * split into a `character_*` sibling (`open_challenge`, `acceptance`, `participant`) carry.
  */
enum GameType(val code: Char) {
  case Character extends GameType('C')
  case Plain extends GameType('P')
}
object GameType {
  def fromCode(c: Char): GameType =
    values.find(_.code == c).getOrElse(throw new IllegalArgumentException(s"unknown game_type code '$c'"))
}

case class Game(
    gameId: GameId,
    gameType: GameType,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    // Shared secret identifying the game itself, used to authorize requests made on the
    // game's behalf (e.g. creating or updating a character).
    externalId: String,
    minPlayers: Int,
    maxPlayers: Int
)
