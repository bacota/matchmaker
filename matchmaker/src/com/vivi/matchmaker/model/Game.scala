package com.vivi.matchmaker.model

case class GameParameterValue[T](
    gameId: Int,
    gameParameterId: Int,
    value: T
)

case class GameParameter[T](
    gameId: Int,
    gameParameterId: Int,
    name: String,
    defaultValue: Option[T],
    values: Seq[GameParameterValue[T]]
)

case class GameRole(
    gameRoleId: Int,
    gameId: Int,
    name: String,
    optional: Boolean
)

sealed trait Game[T] {
  def gameId: Int
  def name: String
  def description: String
  def url: String
  def active: Boolean
  def roles: Seq[GameRole]
  def parameters: Seq[GameParameter[T]]
}

case class PlayerGame[T](
    gameId: Int,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[T]]
) extends Game[T]

case class CharacterGame[T](
    gameId: Int,
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[T]]
) extends Game[T]
