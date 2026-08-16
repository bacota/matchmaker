package com.vivi.matchmaker.model

import java.time.Instant

/** A player's (or, for a `'C'`-type game, a character's) seat in a match. Mirrors the
  * `participant` table split the same way [[OpenChallenge]] mirrors `open_challenge`.
  */
sealed trait Participant {
  def participantId: ParticipantId
  def gameId: GameId
  def matchId: MatchId
  def playerId: PlayerId
  def pending: Boolean
  def completed: Boolean
  def due: Option[Instant]
}

case class PlainParticipant(
    participantId: ParticipantId,
    gameId: GameId,
    matchId: MatchId,
    playerId: PlayerId,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant]
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
