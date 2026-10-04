package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** How long a window of a completed list is: what the viewer picks, the last day by default. A month is thirty days and
  * a year 365 — a window is a length of time, not a calendar page.
  */
enum CompletedFrame(val code: String, val label: String, val days: Long) {
    case Day extends CompletedFrame("day", "Last 24 hours", 1)
    case Week extends CompletedFrame("week", "Last week", 7)
    case Month extends CompletedFrame("month", "Last month", 30)
    case Year extends CompletedFrame("year", "Last year", 365)

    def span: Duration = Duration.ofDays(days)
}

object CompletedFrame {
    def fromCode(code: String): Option[CompletedFrame] = values.find(_.code == code)
}

/** Which window of a completed list is wanted.
  *
  * `page` counts windows back from `asOf`: 0 is the frame just ended at `asOf`, 1 the one before it, and so on. `asOf`
  * is absent on a first ask — the server takes its own now and answers with it — and is sent back unchanged to page
  * through the same list, so that the windows do not drift with the clock while somebody is reading them.
  *
  * `gameId` narrows the list to one game, as the game screen's list and a game on a player's page are.
  */
case class CompletedQuery(
    frame: CompletedFrame = CompletedFrame.Day,
    page: Int = 0,
    asOf: Option[Instant] = None,
    gameId: Option[GameId] = None
)

/** One window of a completed list: the matches finished from `from` up to `until`, most recent first, and never a
  * cancelled one.
  *
  * The most recent window (`page` 0) runs on past `until`, so a match finished since `asOf` is in it rather than in no
  * window at all. `hasOlder` says whether anything in the same list — this player, this game, public or not — was
  * finished before `from`: once the oldest match the list can hold is on screen there is nothing further back, and the
  * list's Next goes.
  */
case class CompletedPage(
    matches: Seq[MatchSummary],
    frame: CompletedFrame,
    page: Int,
    asOf: Instant,
    from: Instant,
    until: Instant,
    hasOlder: Boolean
)
