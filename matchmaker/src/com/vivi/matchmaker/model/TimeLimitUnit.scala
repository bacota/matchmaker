package com.vivi.matchmaker.model

import java.time.Duration

/** The unit a time limit is shown in.
  *
  * The limit is a `Duration` and means the same however it is said; this is how to say it. Kept rather than worked out
  * on the way to the screen, because several units are right for the same duration — 48 hours and 2 days are the same
  * limit — and which of them to show is decided once, as a match is created (`TournamentRounds.unitFor`).
  *
  * The challenge form offers [[TimeLimitUnit.offered]] for its time to play; seconds stay here for an API caller.
  * Anything a limit could be that is not a whole number of one of these (an API caller's 90 seconds) is displayed as
  * what it is instead; see the UI's `Format.duration`.
  */
enum TimeLimitUnit(val code: String, val label: String, val perUnit: Duration) {

    /** What a live match's turns were measured in. No longer offered by the challenge form, but read and shown for the
      * matches that were.
      */
    case Seconds extends TimeLimitUnit(code = "SECONDS", label = "seconds", perUnit = Duration.ofSeconds(1))
    case Minutes extends TimeLimitUnit(code = "MINUTES", label = "minutes", perUnit = Duration.ofMinutes(1))
    case Hours extends TimeLimitUnit(code = "HOURS", label = "hours", perUnit = Duration.ofHours(1))
    case Days extends TimeLimitUnit(code = "DAYS", label = "days", perUnit = Duration.ofDays(1))
}

object TimeLimitUnit {

    /** The units the challenge form offers: a time to play is offered in minutes at the least. */
    val offered: Seq[TimeLimitUnit] = Seq(Minutes, Hours, Days)

    def fromCode(code: String): TimeLimitUnit =
        values
            .find(_.code == code)
            .getOrElse(throw new IllegalArgumentException(s"unknown time_limit_unit '$code'"))

    /** The unit a duration is best said in when nothing recorded how it was said: the largest one that divides it
      * evenly. What every limit was displayed in before the choice was kept, and what a caller that does not name a
      * unit gets.
      */
    def bestFor(limit: Duration): TimeLimitUnit = {
        val seconds = limit.getSeconds
        if (seconds > 0 && seconds % Days.perUnit.getSeconds == 0) Days
        else if (seconds > 0 && seconds % Hours.perUnit.getSeconds == 0) Hours
        else Minutes
    }
}
