package com.vivi.matchmaker.ui

import com.vivi.matchmaker.model._

/** Formatting that has to be readable rather than exact. */
object Format {

    /** A parameter's values in the order a dropdown should offer them.
      *
      * Values are stored as text and come back in no order of their own, so text order is what they fell into — which
      * for numbers is wrong: 3 to 25 rounds would read 10, 11, ... 19, 20, ... 25, 3, 4, ... 9. When every value is a
      * number they are sorted as numbers, low to high. Anything else — board sizes like "3x3", or a mix of words and
      * numbers — is sorted as text, which is the order it was already being shown in.
      */
    def parameterValues(values: Seq[String]): Seq[String] = {
        val numbers = values.map(v => v.trim.toDoubleOption)
        if (values.nonEmpty && numbers.forall(_.isDefined))
            values.zip(numbers.flatten).sortBy((text, number) => (number, text)).map(_._1)
        else values.sorted
    }

    /** `Instant.toString` is ISO-8601 in UTC, which is precise and unpleasant to read. This trims it to the minute and
      * marks it as UTC rather than pretending to know the user's zone — Scala.js has no time-zone database unless one
      * is bundled, and a wrong local time is worse than an explicit UTC one.
      */
    def instant(value: java.time.Instant): String =
        value.toString.replace("T", " ").takeWhile(_ != '.').stripSuffix("Z") + " UTC"

    /** The date alone, for a time whose hour is of no interest — when a match was completed reads as a day in a
      * history, not as a deadline. No UTC marker: a bare date carries none of the precision that would make the zone
      * worth mentioning.
      */
    def date(value: java.time.Instant): String =
        value.toString.takeWhile(_ != 'T')

    /** What is left on a clock, as a clock reads: `4:31`, or `1:02:09` once there is an hour of it. Whole seconds,
      * rounded up, so a countdown reaches "0:00" as the time runs out rather than a second before it.
      *
      * Nothing left is said in words rather than as `0:00`, which would go on being displayed however long the turn
      * stayed unplayed and would read as time still on the clock.
      */
    def remaining(millis: Long): String =
        if (millis <= 0) "time is up"
        else {
            val seconds = (millis + 999) / 1000
            val (hours, minutes, secs) = (seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            val clock =
                if (hours > 0) f"$hours:$minutes%02d:$secs%02d"
                else f"$minutes:$secs%02d"
            s"$clock left"
        }

    /** Time spent, as a clock reads it — `4:31`, or `1:02:09` once there is an hour of it.
      *
      * The same shape as [[remaining]] without its trailing word, since a total at the end of a match is not counting
      * towards anything and "left" would be wrong. Zero is `0:00` here rather than a phrase: in a table of times it is
      * a time like the others, and it means the player never moved.
      */
    /** A seat's Elo rating as its match began, and the match's change to it when it was rated: "Elo 1500 (+16)". The
      * change rather than where it ended up, because that is what this match did — the player's rating by the time it
      * finished may also carry their other matches. A minus sign that reads out as one, rather than a hyphen.
      */
    def elo(start: Int, delta: Option[Int]): String =
        delta.fold(s"Elo $start") { d =>
            val signed = if (d > 0) s"+$d" else if (d < 0) s"\u2212${-d}" else "\u00b10"
            s"Elo $start ($signed)"
        }

    def spent(value: java.time.Duration): String = {
        val seconds = value.getSeconds
        val (hours, minutes, secs) = (seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        if (hours > 0) f"$hours:$minutes%02d:$secs%02d" else f"$minutes:$secs%02d"
    }

    /** A time limit, in the unit it was offered in.
      *
      * Said back the way the challenger said it: 48 hours and 2 days are the same offer, and which of them to show is
      * not a calculation but a fact that travelled with the challenge. The unit is only ignored when it does not divide
      * the limit evenly — an API caller can set 90 seconds and call it minutes — in which case the largest unit that
      * does divide it is used, which is what every limit was displayed in before the unit was recorded.
      */
    def duration(value: java.time.Duration, unit: TimeLimitUnit): String = {
        val perUnit = unit.perUnit.getSeconds
        val chosen = if (perUnit > 0 && value.getSeconds % perUnit == 0) unit else TimeLimitUnit.bestFor(value)
        val amount = value.getSeconds / chosen.perUnit.getSeconds
        // The enum's label is plural, which is what it is nearly always read as; one of anything is
        // the exception and drops the 's'.
        val label = if (amount == 1) chosen.label.stripSuffix("s") else chosen.label
        if (chosen == TimeLimitUnit.Minutes && value.getSeconds % 60 != 0)
            s"${value.getSeconds} second${if (value.getSeconds == 1) "" else "s"}"
        else s"$amount $label"
    }

    /** A score reported by a game engine, which may be any JSON value.
      *
      * Strings are unquoted and whole numbers lose their `.0`, since upickle reads every JSON number as a Double and
      * "moves: 5.0" reads as a mistake. Anything structured is rendered as the JSON it is — a game that reports a
      * nested object is unusual, and showing it raw beats inventing a layout for a shape we cannot know.
      */
    def jsonValue(value: ujson.Value): String = value match {
        case ujson.Str(s)              => s
        case ujson.Num(n) if n.isWhole => n.toLong.toString
        case ujson.Num(n)              => n.toString
        case ujson.Bool(b)             => b.toString
        case ujson.Null                => "—"
        case other                     => other.render()
    }
}
