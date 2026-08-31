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
    isWinner: Boolean,
    // Whether the match was ended by a turn running out rather than by being played to an end.
    // Recorded on every row of such a match, so that a row can say "won by forfeit" or
    // "forfeited" — `isWinner` says which — without reading the rest of the table.
    forfeit: Boolean = false
)

/** One line of a finished match's result table: who played, in which role, and how they did.
  *
  * A read model joining result, participant, player and game_role, for the same reason
  * [[MatchSummary]] is one — the UI needs all four and a query per row would be the alternative.
  *
  * `rank` is optional because a participant is listed whether or not the engine reported a result
  * for them. An engine reports what it chose to report, and a seat it said nothing about is still
  * someone who was in the match; leaving them out of the table would misrepresent who played.
  * Such a row has no rank, no scores and is not a winner.
  */
case class ParticipantResult(
    gameId: GameId,
    matchId: MatchId,
    participantId: ParticipantId,
    nickname: String,
    roleName: String,
    rank: Option[Int],
    scores: Map[String, Any],
    isWinner: Boolean,
    forfeit: Boolean = false
)
