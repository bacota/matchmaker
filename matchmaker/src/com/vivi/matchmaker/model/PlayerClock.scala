package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** What one player has left of a chess-clock budget, at the moment it was read.
  *
  * `remaining` is the match's time limit less every turn they have finished. It does *not*
  * include the turn they may be in the middle of: that turn has no cost until it is taken, and
  * a stored number that quietly went stale would be worse than one that is plainly a balance as
  * of the last move.
  *
  * `deadline` is what covers the difference. It is set only for the player currently on the
  * clock, and it is their participant row's `due` — `remaining` counted forward from the moment
  * their turn began. So a reader shows a running player their deadline (which can be counted
  * down live) and everyone else their balance (which is not moving).
  *
  * Only ever populated for a match under [[TimeLimitKind.Total]]. A per-turn limit hands the
  * whole limit back on every move, so there is no balance to report.
  */
case class PlayerClock(
    nickname: String,
    remaining: Duration,
    deadline: Option[Instant] = None
) {

  /** Whether this is the player the clock is running against. */
  def running: Boolean = deadline.isDefined
}
