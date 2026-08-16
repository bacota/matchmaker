package com.vivi.matchmaker.model

case class Result(
    gameId: GameId,
    participantId: ParticipantId,
    rank: Int,
    score: Double
)
