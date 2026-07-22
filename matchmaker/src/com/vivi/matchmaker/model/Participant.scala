package com.vivi.matchmaker.model

import java.time.Instant

sealed trait Participant {
  def participantId: ParticipantId
  def gameId: GameId
  def matchId: MatchId
  def playerId: PlayerId
  def pending: Boolean
  def completed: Boolean
  def due: Option[Instant]
}

case class PlayerParticipant(
    participantId: ParticipantId,
    gameId: GameId,
    matchId: MatchId,
    playerId: PlayerId,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    gameRoleId: GameRoleId
) extends Participant

case class CharacterParticipant(
    participantId: ParticipantId,
    gameId: GameId,
    matchId: MatchId,
    playerId: PlayerId,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    characterId: CharacterId
) extends Participant
