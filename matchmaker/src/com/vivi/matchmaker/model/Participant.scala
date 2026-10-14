package com.vivi.matchmaker.model

import java.time.Instant

/** A player's (or, for a `'C'`-type game, a character's) seat in a match. Mirrors the `participant` table split: a
  * character game's seat has a `character_participant` row naming the character.
  */
sealed trait Participant {
    def participantId: ParticipantId
    def gameId: GameId
    def matchId: MatchId
    def playerId: PlayerId
    def pending: Boolean
    def completed: Boolean
    def due: Option[Instant]

    /** The role this seat plays. A seat may wait for one while its match's roles are chosen, but a match is not played
      * until every seat has one, and a participant is read only from a match that is.
      */
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
