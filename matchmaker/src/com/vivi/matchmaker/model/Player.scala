package com.vivi.matchmaker.model

case class Player(
    playerId: PlayerId,
    nickname: String,
    isAdmin: Boolean,
    externalId: String
)
