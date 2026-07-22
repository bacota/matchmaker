package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

sealed trait OpenChallenge {
  def challengeId: ChallengeId
  def challenger: PlayerId
  def message: String
  def numberOfPlayers: Short
  def start: Option[Instant]
  def timeLimit: Option[Duration]
  def settings: String
}

case class PlayerOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId
) extends OpenChallenge

case class CharacterOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    characterId: CharacterId
) extends OpenChallenge
