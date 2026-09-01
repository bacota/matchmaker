package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

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
    characterId: Option[CharacterId],
    // The clock this match is being played under, carried from the challenge it was started
    // from. `due` says when this player's current turn runs out; these say what rule produced
    // it, which is not recoverable from the deadline alone — and they are the only statement of
    // the terms once the challenge that set them is no longer on screen.
    timeLimit: Option[Duration] = None,
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
    // Everyone whose turn it is, by nickname — usually one, but a game where several players
    // move at once has several, and an empty list means the match is waiting on nobody (it is
    // over, or matchmaker has not yet heard who moves first).
    //
    // `pending` says whether the caller is among them, which is what the caller's own lists are
    // selected on; this says who the others are, which is what a match still being played is
    // waiting for.
    whoseTurn: Seq[String] = Nil,
    // When the turn now being taken runs out — the earliest deadline among the players named in
    // `whoseTurn`, which for a one-at-a-time game is simply that player's. Distinct from `due`,
    // which is the caller's own and is empty while it is somebody else's move.
    turnDue: Option[Instant] = None
) {

  /** Whether the match was played to an end. */
  def completed: Boolean = completedAt.isDefined
}
