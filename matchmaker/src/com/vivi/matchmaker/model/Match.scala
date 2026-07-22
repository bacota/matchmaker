package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

sealed trait Match {
  def gameId: GameId
  def matchId: MatchId
  def description: String
  def completed: Boolean
  def start: Instant
  def timeLimit: Option[Duration]
  def settings: String
}

case class PlayerMatch(
    gameId: GameId,
    matchId: MatchId,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String
) extends Match

case class CharacterMatch(
    gameId: GameId,
    matchId: MatchId,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String
) extends Match
