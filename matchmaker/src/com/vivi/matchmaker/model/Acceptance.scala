package com.vivi.matchmaker.model

case class Acceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    characterId: CharacterId
)
