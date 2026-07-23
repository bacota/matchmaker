package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

case class CharacterMatch(
    gameId: GameId,
    matchId: MatchId,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String
)
