package com.vivi.matchmaker.model

/** A player who administers a game (V35), and who made them one: `grantedBy` is what lets a game's admin take back an
  * admin they made, and only those. Both as anybody may see them — see [[PublicPlayer]].
  */
case class GameAdmin(player: PublicPlayer, grantedBy: PublicPlayer)
