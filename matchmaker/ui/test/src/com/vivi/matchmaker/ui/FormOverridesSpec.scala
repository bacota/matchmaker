package com.vivi.matchmaker.ui

import munit.FunSuite
import com.vivi.matchmaker.model.tournament.standard.StandardModels

/** A competition format's wording of the competition form: what it rewords and leaves out, and that what it names is
  * there to be reworded or left out.
  */
class FormOverridesSpec extends FunSuite {

    private val overrides = Map(
      "labels.competition.poolSize" -> Some("Group size"),
      "tips.competition.playersPerMatchBetween" -> Some("From {fewest} to {most} a match, {fewest} at the least."),
      "labels.competition.maxEntrants" -> None,
      "tips.competition.live" -> None
    )

    test("a caption or a tip the format rewords is its words, and any other the UI's own") {
        assertEquals(FormOverrides.label(overrides, "competition.poolSize", "Pool Size"), Some("Group size"))
        assertEquals(FormOverrides.label(overrides, "competition.live", "Live"), Some("Live"))
        // A tip of the same key as a reworded caption is not reworded with it.
        assertEquals(FormOverrides.tip(overrides, "competition.poolSize", "How many."), Some("How many."))
    }

    test("a reworded tip has each {name} filled in, as the UI's own is") {
        assertEquals(
          FormOverrides.tip(overrides, "competition.playersPerMatchBetween", "2 to 4", "fewest" -> "2", "most" -> "4"),
          Some("From 2 to 4 a match, 2 at the least.")
        )
    }

    test("a caption the format leaves out leaves out its field, and a tip just the tip") {
        assertEquals(FormOverrides.label(overrides, "competition.maxEntrants", "Max players"), None)
        assertEquals(FormOverrides.tip(overrides, "competition.maxEntrants", "Leave blank."), Some("Leave blank."))
        assertEquals(FormOverrides.tip(overrides, "competition.live", "In real time."), None)
        assertEquals(FormOverrides.label(overrides, "competition.live", "Live"), Some("Live"))
    }

    test("the name, the format and the time unit are shown though a format leaves them out") {
        val hiding = Map("labels.competition.name" -> None, "labels.competition.format" -> None)
        assertEquals(FormOverrides.label(hiding, "competition.name", "Name"), Some("Name"))
        assertEquals(FormOverrides.unhideable(hiding), Set("labels.competition.name", "labels.competition.format"))
        assertEquals(FormOverrides.unhideable(overrides), Set.empty[String])
    }

    test("a key that names no caption or tip there is is found out") {
        val wrong = overrides ++ Map(
          "labels.competition.nonesuch" -> Some("x"),
          "competition.poolSize" -> Some("x"),
          "tip.competition.poolSize" -> None
        )
        assertEquals(
          FormOverrides.unknown(wrong),
          Set("labels.competition.nonesuch", "competition.poolSize", "tip.competition.poolSize")
        )
    }

    test("every format the code defines rewords or leaves out only captions and tips there are, and may be") {
        StandardModels.all.foreach { m =>
            assertEquals(FormOverrides.unknown(m.formOverrides), Set.empty[String], m.id)
            assertEquals(FormOverrides.unhideable(m.formOverrides), Set.empty[String], m.id)
        }
    }
}
