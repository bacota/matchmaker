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
  * Every match is a tournament's (`tournamentId`) — a challenge is the simplest tournament, of one match — and is its
  * tournament's owner's: the one who may cancel it. That is read through the tournament, not copied onto the match.
  *
  * `completedAt` and `isCancelled` are separate rather than one status, because they answer different questions. A
  * completed match was played to an end the engine reported; a cancelled one was called off by its tournament's owner
  * and has no result and never will. Both are over.
  *
  * `completedAt` is a time rather than a flag so that a finished match can say when it finished — a history in no
  * particular order is not much of a history. `isCompleted` is kept beside it as a derived answer to the question most
  * callers actually ask, and is deliberately not a field: two stored columns saying the same thing could disagree.
  *
  * `status` says where the match is in its life, and is the source of truth: a tournament's match is written when its
  * pool is formed, long before the engine has a game for it, and spends that time [[MatchStatus.Scheduled]],
  * [[MatchStatus.Choosing]] and [[MatchStatus.Due]], with no urls and no `start`, until it is created in the engine and
  * is [[MatchStatus.InProgress]]. [[MatchStatus.Finished]] is completed or cancelled, which the database holds in step
  * with `completedAt` and `isCancelled`.
  */
enum MatchStatus(val code: String) {
    case Scheduled extends MatchStatus("S")
    case Choosing extends MatchStatus("C")
    case Due extends MatchStatus("D")
    case InProgress extends MatchStatus("P")
    case Finished extends MatchStatus("F")

    /** Whether the engine has been asked to create the match's game: it has begun, or is over. */
    def hasBegun: Boolean = this == InProgress || this == Finished
}

object MatchStatus {
    def fromCode(code: String): MatchStatus =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown match status '$code'"))
}

case class Match(
    gameId: GameId,
    matchId: MatchId,
    tournamentId: TournamentId,
    description: String,
    completedAt: Option[Instant],
    /** When play began: set when the match is created in the engine, and `None` until then. */
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    isPublic: Boolean = false,
    isCancelled: Boolean = false,
    statusUrl: Option[String] = None,
    playUrl: Option[String] = None,
    publicUrl: Option[String] = None,
    /** What `timeLimit` is a limit on: each turn separately, or the player's whole match. Set from its tournament's
      * round or turn duration when it is created in the engine (`Clock.forMatch`), so that editing the tournament
      * afterwards cannot change the terms of a match already being played.
      */
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
    /** The unit `timeLimit` is shown in. */
    timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
    /** Whether this match is played live, as its tournament is. A live match's seats are never pending here and never
      * due: the engine runs its turns and their clock, and reports only the result.
      */
    isLive: Boolean = false,
    /** Whether this is a friendly match. Every match is, unless it is classified otherwise. */
    isFriendly: Boolean = true,
    /** When the match's archive was confirmed. From then on the engine has dropped its live copy, and its urls are
      * handed out marked as an archived match's — see `ArchiveService.forViewer`.
      */
    archivedAt: Option[Instant] = None,
    /** Whether a friendly match's archive has expired. There is nothing left to view, so a player is not offered the
      * urls; they are cleared where they are handed out.
      */
    isArchiveExpired: Boolean = false,
    status: MatchStatus = MatchStatus.InProgress
) {

    /** Whether the match was played to an end. */
    def isCompleted: Boolean = completedAt.isDefined
}
