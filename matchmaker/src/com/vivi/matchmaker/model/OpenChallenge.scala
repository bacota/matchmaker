package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

sealed trait OpenChallenge {
  def challengeId: Long
  def challenger: Long
  def message: String
  def numberOfPlayers: Short
  def start: Option[Instant]
  def timeLimit: Option[Duration]
  def settings: String
}

case class PlayerOpenChallenge(
    challengeId: Long,
    challenger: Long,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: Int
) extends OpenChallenge

case class CharacterOpenChallenge(
    challengeId: Long,
    challenger: Long,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: Int,
    characterId: Long
) extends OpenChallenge
