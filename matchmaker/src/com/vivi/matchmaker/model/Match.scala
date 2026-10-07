package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** A game being played, from matchmaker's side of the fence.
  *
  * Matchmaker does not run the game — a game engine does — so a match is mostly a handle on one the engine created:
  * `statusUrl` is how matchmaker asks the engine how the game is going, `playUrl` is where an authenticated participant
  * plays it, and `publicUrl` is where anyone can watch, which the engine issues only for a public match. All three are
  * empty until the engine has answered, which is the state a match is in for the moment between being written and the
  * create call returning.
  *
  * `creator` is the player whose match this is: the one who may cancel it. For a match started from a challenge
  * (`challengeId`) that is the challenger, copied onto the match when it is started (V50). A match need not come from a
  * challenge at all — a tournament's are created by the tournament — and holding the creator on the match itself is
  * what lets every listing and the cancel say whose a match is without joining a challenge that may not exist.
  *
  * `completedAt` and `cancelled` are separate rather than one status, because they answer different questions. A
  * completed match was played to an end the engine reported; a cancelled one was called off by its creator and has no
  * result and never will. Both are over.
  *
  * `completedAt` is a time rather than a flag so that a finished match can say when it finished — a history in no
  * particular order is not much of a history. `completed` is kept beside it as a derived answer to the question most
  * callers actually ask, and is deliberately not a field: two stored columns saying the same thing could disagree.
  */
case class Match(
    gameId: GameId,
    matchId: MatchId,
    challengeId: Option[ChallengeId],
    creator: PlayerId,
    description: String,
    completedAt: Option[Instant],
    start: Instant,
    timeLimit: Option[Duration],
    settings: String,
    isPublic: Boolean = false,
    cancelled: Boolean = false,
    statusUrl: Option[String] = None,
    playUrl: Option[String] = None,
    publicUrl: Option[String] = None,
    /** What `timeLimit` is a limit on: each turn separately, or the player's whole match. Copied from the challenge
      * along with the limit itself, so that editing the challenge afterwards cannot change the terms of a match already
      * being played under it.
      */
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
    /** The unit the challenger offered `timeLimit` in, carried forward with it. */
    timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
    /** Whether this match is played live — see `Challenge.live`, which it is copied from. A live match's seats are
      * never pending here and never due: the engine runs its turns and their clock, and reports only the result.
      */
    live: Boolean = false,
    /** Whether this is a friendly match (V36). Every match is, unless it is classified otherwise. */
    friendly: Boolean = true,
    /** When the match's archive was confirmed (V38). From then on the engine has dropped its live copy, and its urls
      * are handed out marked as an archived match's — see `ArchiveService.forViewer`.
      */
    archivedAt: Option[Instant] = None,
    /** Whether a friendly match's archive has expired (V38). There is nothing left to view, so a player is not offered
      * the urls; they are cleared where they are handed out.
      */
    archiveExpired: Boolean = false,
    /** Whether the match was asked to end with somebody ahead (V51) — see `Challenge.noTie`, which it is copied from,
      * and a tournament's tie-break, which sets it.
      */
    noTie: Boolean = false
) {

    /** Whether the match was played to an end. */
    def completed: Boolean = completedAt.isDefined
}
