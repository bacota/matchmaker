package com.vivi.matchmaker.model

import java.time.Instant

/** One row of a player's match list: the match, the game it belongs to, and the player's own
  * participation in it. This is a read model assembled by joining participant, match, and game
  * — it exists because the lists the UI shows need all three, and fetching them separately per
  * match would be a query per row.
  *
  * @param due when the player's turn is due, if it is their turn at all
  * @param pending whether it is this player's turn. Set from what the engine reports about
  *                each participant, and the flag `due` selects on — not, despite an older
  *                reading of the name, whether the match has started yet
  * @param completedAt when the match was played to an end, if it was — the database's clock,
  *                    stamped once. `completed` is derived from it, as on `Match`, so a list can
  *                    both ask whether a match is finished and say when
  * @param cancelled whether the creator called the match off; a cancelled match is over but has
  *                  no result, and is listed with the finished ones rather than the active ones
  * @param isCreator whether this player is the one who created the match — the challenger of the
  *                  challenge it was started from, and the only player who may cancel it. A
  *                  property of the pair, not of the match, which is why it belongs on a read
  *                  model that is already scoped to one player
  * @param characterId the character playing this participant's seat, if the game requires one
  */
case class MatchSummary(
    gameId: GameId,
    matchId: MatchId,
    gameName: String,
    description: String,
    completedAt: Option[Instant],
    cancelled: Boolean,
    isCreator: Boolean,
    start: Instant,
    due: Option[Instant],
    pending: Boolean,
    participantId: ParticipantId,
    characterId: Option[CharacterId]
) {

  /** Whether the match was played to an end. */
  def completed: Boolean = completedAt.isDefined
}
