package com.vivi.matchmaker.model

import java.time.Duration

/** The unit a time limit was offered in.
  *
  * The limit is a `Duration` and means the same however it was said; this is how the challenger
  * said it. Kept rather than worked out on the way to the screen, because several units are
  * right for the same duration — 48 hours and 2 days are the same offer — and which of them to
  * show is a fact about the offer, not a calculation over it.
  *
  * The set is the set the challenge form offers. Anything a limit could be that is not a whole
  * number of one of these (an API caller's 90 seconds) is displayed as what it is instead; see
  * the UI's `Format.duration`.
  */
enum TimeLimitUnit(val code: String, val label: String, val perUnit: Duration) {
  case Minutes extends TimeLimitUnit("MINUTES", "minutes", Duration.ofMinutes(1))
  case Hours extends TimeLimitUnit("HOURS", "hours", Duration.ofHours(1))
  case Days extends TimeLimitUnit("DAYS", "days", Duration.ofDays(1))
}

object TimeLimitUnit {
  def fromCode(code: String): TimeLimitUnit =
    values
      .find(_.code == code)
      .getOrElse(throw new IllegalArgumentException(s"unknown time_limit_unit '$code'"))

  /** The unit a duration is best said in when nothing recorded how it was said: the largest one
    * that divides it evenly. What every limit was displayed in before the choice was kept, and
    * what a caller that does not name a unit gets.
    */
  def bestFor(limit: Duration): TimeLimitUnit = {
    val seconds = limit.getSeconds
    if (seconds > 0 && seconds % Days.perUnit.getSeconds == 0) Days
    else if (seconds > 0 && seconds % Hours.perUnit.getSeconds == 0) Hours
    else Minutes
  }
}
