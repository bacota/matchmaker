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

  /** The role this seat plays, carried over from the acceptance the participant was made from.
    * Mandatory there, and so mandatory here. */
  def gameRoleId: GameRoleId
}

case class PlainParticipant(
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
    characterId: CharacterId,
    gameRoleId: GameRoleId
) extends Participant
