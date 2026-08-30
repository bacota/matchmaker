package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** A game being played, from matchmaker's side of the fence.
  *
  * Matchmaker does not run the game — a game engine does — so a match is mostly a handle on one
  * the engine created: `statusUrl` is how matchmaker asks the engine how the game is going,
  * `playUrl` is where an authenticated participant plays it, and `publicUrl` is where anyone can
  * watch, which the engine issues only for a public match. All three are empty until the engine
  * has answered, which is the state a match is in for the moment between being written and the
  * create call returning.
  *
  * `challengeId` is the challenge this match was started from, which is never deleted and is
  * therefore where the match's creator comes from: the challenge's challenger. Holding the
  * challenge rather than copying its challenger into a column of its own means the two cannot
  * drift apart, and it keeps the settings, message and time limit the match was made under
  * readable beside the match itself.
  *
  * `completedAt` and `cancelled` are separate rather than one status, because they answer
  * different questions. A completed match was played to an end the engine reported; a cancelled
  * one was called off by its creator and has no result and never will. Both are over.
  *
  * `completedAt` is a time rather than a flag so that a finished match can say when it finished
  * — a history in no particular order is not much of a history. `completed` is kept beside it as
  * a derived answer to the question most callers actually ask, and is deliberately not a field:
  * two stored columns saying the same thing could disagree.
  */
case class Match(
    gameId: GameId,
    matchId: MatchId,
    challengeId: ChallengeId,
    description: String,
    completedAt: Option[Instant],
    start: Instant,
    timeLimit: Option[Duration],
    settings: String,
    isPublic: Boolean = false,
    cancelled: Boolean = false,
    statusUrl: Option[String] = None,
    playUrl: Option[String] = None,
    publicUrl: Option[String] = None
) {

  /** Whether the match was played to an end. */
  def completed: Boolean = completedAt.isDefined
}
