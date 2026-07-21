package com.vivi.matchmaker.model

case class Character[T](
    characterId: Long,
    gameId: Long,
    name: String,
    description: String,
    state: T,
    playerId: Option[Long]
)
