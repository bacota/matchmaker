package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** One turn a participant took, and how long it cost them.
  *
  * `takenAt` is when the move was made; `startedAt` is when that player's clock started running
  * for it — the previous move in the match, or the match's own start for the first turn. The
  * difference is what the turn spent, which is the whole reason these are recorded: a per-turn
  * limit needs only the turn being taken now, but a total budget is a sum over every turn a
  * player has ever taken in the match.
  *
  * Whose turn it was and when it happened are the engine's to say — matchmaker learns both from
  * the move callback, and again from the status call, which is how a lost callback is repaired.
  * A turn is therefore recorded twice in the ordinary case and stored once: `(gameId,
  * participantId, takenAt)` is its identity, and a second insert of the same turn is dropped.
  */
case class Turn(
    gameId: GameId,
    matchId: MatchId,
    participantId: ParticipantId,
    takenAt: Instant,
    startedAt: Instant
) {

  /** What this turn cost the player who took it. Never negative: a turn reported as taken before
    * it started is a disagreement between two clocks, not a refund.
    */
  def elapsed: Duration = {
    val spent = Duration.between(startedAt, takenAt)
    if (spent.isNegative) Duration.ZERO else spent
  }
}
