package com.vivi.matchmaker.model

import java.time.Instant

sealed trait Participant {
  def participantId: Long
  def gameId: Int
  def matchId: String
  def playerId: Long
  def pending: Boolean
  def completed: Boolean
  def due: Option[Instant]
}

case class PlayerParticipant(
    participantId: Long,
    gameId: Int,
    matchId: String,
    playerId: Long,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    gameRoleId: Int
) extends Participant

case class CharacterParticipant(
    participantId: Long,
    gameId: Int,
    matchId: String,
    playerId: Long,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    characterId: Long
) extends Participant
