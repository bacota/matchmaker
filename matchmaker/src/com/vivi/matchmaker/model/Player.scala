package com.vivi.matchmaker.model

/** A registered player.
  *
  * `externalId` is the Cognito `sub`, which is what every authenticated route identifies a caller
  * by. `email` is a copy of the address that identity signs in with, kept so that matchmaker can
  * reach a player who is not the one making the request — see V12. `None` means there is nobody
  * to write to, not that the address is unknown-but-present: a player registered before the
  * column existed, or a local caller with no Cognito identity at all.
  */
case class Player(
    playerId: PlayerId,
    nickname: String,
    isAdmin: Boolean,
    externalId: String,
    email: Option[String] = None
)
