package com.vivi.matchmaker.model

case class Player(
    playerId: Long,
    nickname: String,
    isAdmin: Boolean,
    externalId: String
)
