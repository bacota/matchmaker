package com.vivi.matchmaker.model

case class Character[T](
    characterId: CharacterId,
    gameId: GameId,
    name: String,
    description: String,
    state: T,
    playerId: Option[PlayerId]
)
