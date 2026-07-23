package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

case class OpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    characterId: CharacterId
)
