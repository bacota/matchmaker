package com.vivi.matchmaker.model

import java.time.Instant

/** A player's (or, for a `'C'`-type game, a character's) seat in a match. Mirrors the `participant` table split the
  * same way [[Challenge]] mirrors `challenge`.
  */
sealed trait Participant {
    def participantId: ParticipantId
    def gameId: GameId
    def matchId: MatchId
    def playerId: PlayerId
    def pending: Boolean
    def completed: Boolean
    def due: Option[Instant]

    /** The role this seat plays: carried over from the acceptance the participant was made from, or — for a match whose
      * roles are chosen in its engine (V52) — written when the engine reports the choice. `None` until then, and for
      * good in a match that ended before the seat chose.
      */
    def gameRoleId: Option[GameRoleId]
}

case class PlainParticipant(
    participantId: ParticipantId,
    gameId: GameId,
    matchId: MatchId,
    playerId: PlayerId,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    gameRoleId: Option[GameRoleId]
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
    gameRoleId: Option[GameRoleId]
) extends Participant
