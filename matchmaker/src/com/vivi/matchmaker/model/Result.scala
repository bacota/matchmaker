package com.vivi.matchmaker.model

/** A participant's outcome in a game.
  *
  * `scores` is an open-ended map rather than a single number: a game may score on several axes
  * at once (points, time, objectives), and which ones exist is the game's business, not this
  * table's. It is stored as a jsonb object, so its values are whatever JSON can carry — strings,
  * numbers (read back as `Double`), booleans, nulls, and nested maps and lists.
  */
case class Result(
    gameId: GameId,
    participantId: ParticipantId,
    rank: Int,
    scores: Map[String, Any],
    isWinner: Boolean
)
