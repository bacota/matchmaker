package com.vivi.matchmaker.ui

import munit.FunSuite
import com.vivi.matchmaker.model.tournament.standard.StandardModels

/** A competition format's wording of the competition form: what it rewords, and that what it names is there to be
  * reworded.
  */
class FormOverridesSpec extends FunSuite {

    private val overrides = Map(
      "labels.competition.poolSize" -> "Group size",
      "tips.competition.playersPerMatchBetween" -> "From {fewest} to {most} a match, {fewest} at the least."
    )

    test("a caption or a tip the format rewords is its words, and any other the UI's own") {
        assertEquals(FormOverrides.label(overrides, "competition.poolSize", "Pool Size"), "Group size")
        assertEquals(FormOverrides.label(overrides, "competition.live", "Live"), "Live")
        // A tip of the same key as a reworded caption is not reworded with it.
        assertEquals(FormOverrides.tip(overrides, "competition.poolSize", "How many."), "How many.")
    }

    test("a reworded tip has each {name} filled in, as the UI's own is") {
        assertEquals(
          FormOverrides.tip(overrides, "competition.playersPerMatchBetween", "2 to 4", "fewest" -> "2", "most" -> "4"),
          "From 2 to 4 a match, 2 at the least."
        )
    }

    test("a key that names no caption or tip there is is found out") {
        val wrong = overrides ++ Map(
          "labels.competition.nonesuch" -> "x",
          "competition.poolSize" -> "x",
          "tip.competition.poolSize" -> "x"
        )
        assertEquals(
          FormOverrides.unknown(wrong),
          Set("labels.competition.nonesuch", "competition.poolSize", "tip.competition.poolSize")
        )
    }

    test("every format the code defines rewords only captions and tips there are") {
        StandardModels.all.foreach(m => assertEquals(FormOverrides.unknown(m.formOverrides), Set.empty[String], m.id))
    }
}
