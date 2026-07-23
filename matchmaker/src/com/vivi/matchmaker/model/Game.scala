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

sealed trait Game{
  def gameId: GameId
  def name: String
  def description: String
  def url: String
  def active: Boolean
  def roles: Seq[GameRole]
  def parameters: Seq[GameParameter[_]]
  // Shared secret identifying the game itself, used to authorize requests made on the
  // game's behalf (e.g. creating or updating a character).
  def externalId: String
}

case class PlayerGame(
    gameId: GameId,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    externalId: String
) extends Game

case class CharacterGame(
    gameId: GameId,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    externalId: String
) extends Game
