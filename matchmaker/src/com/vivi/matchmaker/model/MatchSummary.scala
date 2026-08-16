package com.vivi.matchmaker.model

import java.time.Instant

/** One row of a player's match list: the match, the game it belongs to, and the player's own
  * participation in it. This is a read model assembled by joining participant, match, and game
  * — it exists because the lists the UI shows need all three, and fetching them separately per
  * match would be a query per row.
  *
  * @param due when the player's turn is due, if it is their turn at all
  * @param characterId the character playing this participant's seat, if the game requires one
  */
case class MatchSummary(
    gameId: GameId,
    matchId: MatchId,
    gameName: String,
    description: String,
    completed: Boolean,
    start: Instant,
    due: Option[Instant],
    pending: Boolean,
    participantId: ParticipantId,
    characterId: Option[CharacterId]
)
