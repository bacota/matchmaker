package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** How long a window of a completed list is: what the viewer picks, the last day by default. A month is thirty days and
  * a year 365 — a window is a length of time, not a calendar page.
  */
/** `period` is the window as a step back or forward names it: "Previous week", "Next 24 hours". */
enum CompletedFrame(val code: String, val label: String, val period: String, val days: Long) {
    case Day extends CompletedFrame("day", "Last 24 hours", "24 hours", 1)
    case Week extends CompletedFrame("week", "Last week", "week", 7)
    case Month extends CompletedFrame("month", "Last month", "month", 30)
    case Year extends CompletedFrame("year", "Last year", "year", 365)

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
) {

    /** Where the window ends: `page` frames before `asOf`. */
    def until(asOf: Instant): Instant = asOf.minus(frame.span.multipliedBy(page.toLong))

    /** Where the window begins: one frame before it ends. */
    def from(asOf: Instant): Instant = until(asOf).minus(frame.span)

    /** Whether this is a window anybody's history could hold, measured from `now` where it names no `asOf`: one that
      * ends no later than a day after now and begins no earlier than 1970. Outside that, the arithmetic is not
      * guaranteed to give an instant at all, let alone one the database can hold.
      */
    def inRange(now: Instant): Boolean =
        try {
            val base = asOf.getOrElse(now)
            !base.isAfter(now.plus(Duration.ofDays(1))) && !from(base).isBefore(Instant.EPOCH)
        } catch { case _: ArithmeticException | _: java.time.DateTimeException => false }
}

/** One window of a completed list: the matches finished from `from` up to `until`, most recent first, and never a
  * cancelled one.
  *
  * Every window ends at `until`, the most recent one included, so a list paged from one `asOf` shows the same windows
  * whichever way the reader moves through it; a match finished since `asOf` is shown by asking again without one.
  * `hasOlder` says whether anything in the same list — this player, this game, public or not — was finished before
  * `from`: once the oldest match the list can hold is on screen there is nothing further back, and the list's Next
  * goes.
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
