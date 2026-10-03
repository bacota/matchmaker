package com.vivi.matchmaker.model

import java.time.Instant

/** One match of a game, as the game's admins see it in the list they manage its matches from: not any one player's view
  * of it, as a [[MatchSummary]] is, but the match and who is playing it, by nickname in seat order.
  */
case class GameMatch(
    matchId: MatchId,
    description: String,
    start: Instant,
    completedAt: Option[Instant],
    cancelled: Boolean,
    friendly: Boolean,
    players: Seq[String]
)
