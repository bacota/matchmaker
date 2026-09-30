package com.vivi.matchmaker.util

import munit.FunSuite

class ChallengeSettingsSpec extends FunSuite {

    private val allowed = Map("rounds" -> (3 to 25).map(_.toString), "board" -> Seq("3x3", "4x4"))

    test("a challenge's choices are the string (or whole-number) values under its top-level keys") {
        assertEquals(
          ChallengeSettings.choices("""{"rounds":"12","board":"4x4"}"""),
          Map("rounds" -> "12", "board" -> "4x4")
        )
        assertEquals(ChallengeSettings.choices("""{"rounds":12}"""), Map("rounds" -> "12"))
        assertEquals(ChallengeSettings.choices("{}"), Map.empty[String, String])
        // A challenge stored before choices existed, or with settings nobody can read, simply has none.
        assertEquals(ChallengeSettings.choices("not json"), Map.empty[String, String])
        assertEquals(ChallengeSettings.choices("[1,2]"), Map.empty[String, String])
    }

    test("encode writes what choices reads") {
        val chosen = Map("rounds" -> "12", "board" -> "3x3")
        assertEquals(ChallengeSettings.choices(ChallengeSettings.encode(chosen)), chosen)
        assertEquals(ChallengeSettings.encode(chosen), """{"board":"3x3","rounds":"12"}""")
    }

    test("a choice must be one of its parameter's values") {
        assertEquals(ChallengeSettings.problem("""{"rounds":"12"}""", allowed), None)
        assertEquals(ChallengeSettings.problem("{}", allowed), None)
        assertEquals(
          ChallengeSettings.problem("""{"rounds":"26"}""", allowed),
          Some("'26' is not a value of rounds; expected one of " + (3 to 25).mkString(", "))
        )
        assert(ChallengeSettings.problem("""{"board":true}""", allowed).isDefined)
    }

    test("settings must be a JSON object, but keys naming no parameter are the engine's and left alone") {
        assertEquals(ChallengeSettings.problem("[]", allowed), Some("settings must be a JSON object"))
        assertEquals(ChallengeSettings.problem("", allowed), Some("settings must be a JSON object"))
        assertEquals(ChallengeSettings.problem("""{"variant":"anything"}""", allowed), None)
    }

    test("the engine is told the choice where the game still allows it, and the default otherwise") {
        val defaults = Map("rounds" -> "10", "board" -> "3x3")
        assertEquals(
          ChallengeSettings.resolve(defaults, allowed, """{"rounds":"12"}"""),
          Map("rounds" -> "12", "board" -> "3x3")
        )
        // A value the admin has since removed falls back to the default rather than failing the start.
        assertEquals(ChallengeSettings.resolve(defaults, allowed, """{"rounds":"99"}"""), defaults)
        assertEquals(ChallengeSettings.resolve(defaults, allowed, "{}"), defaults)
        // Only the game's own parameters are sent as parameters; other keys stay in `settings`.
        assertEquals(ChallengeSettings.resolve(defaults, allowed, """{"variant":"x"}""").keySet, Set("rounds", "board"))
    }
}
