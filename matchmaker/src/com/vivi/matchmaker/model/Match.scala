package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

sealed trait Match {
  def gameId: Int
  def matchId: String
  def description: String
  def completed: Boolean
  def start: Instant
  def timeLimit: Option[Duration]
  def settings: String
}

case class PlayerMatch(
    gameId: Int,
    matchId: String,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String
) extends Match

case class CharacterMatch(
    gameId: Int,
    matchId: String,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String
) extends Match
