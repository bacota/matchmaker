package com.vivi.matchmaker.model

import java.time.Instant

case class CharacterParticipant(
    participantId: ParticipantId,
    gameId: GameId,
    matchId: MatchId,
    playerId: PlayerId,
    pending: Boolean,
    completed: Boolean,
    due: Option[Instant],
    characterId: CharacterId
)
