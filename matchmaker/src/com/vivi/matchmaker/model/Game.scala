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
}

case class PlayerGame(
    gameId: GameId,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]]
) extends Game

case class CharacterGame(
    gameId: GameId,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    // Public key used to verify signatures on character-creation requests for this game.
    verificationKey: String
) extends Game
