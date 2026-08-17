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
  */
case class Match(
    gameId: GameId,
    matchId: MatchId,
    description: String,
    completed: Boolean,
    start: Instant,
    timeLimit: Option[Duration],
    settings: String,
    isPublic: Boolean = false,
    statusUrl: Option[String] = None,
    playUrl: Option[String] = None,
    publicUrl: Option[String] = None
)
