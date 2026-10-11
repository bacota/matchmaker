package com.vivi.matchmaker.model.tournament

import java.time.Duration
import munit.FunSuite
import upickle.default.{read, write}
import TournamentCodecs.given
import Fixtures.*

class DesignSpec extends FunSuite {

    test("every standard design is valid for a two-player game, with no errors") {
        StandardDesigns.all.foreach { std =>
            val design = StandardDesigns.build(std.key, 2, Some(4)).toOption.get
            val errors = Validation.validate(design, twoPlayer, Some(hour), None).filterNot(_.warning)
            assertEquals(errors, Nil, std.key)
        }
    }

    test("a two-role format is refused for a game of more players") {
        assert(StandardDesigns.build("double-elimination", 3, None).isLeft)
        assert(StandardDesigns.build("single-elimination", 3, None).isRight)
    }

    test("validation names what is wrong, and where") {
        val bad = TournamentDesign(
          List(
            StageDesign(demote = Some(1)),
            StageDesign(poolSize = Some(4), demote = Some(4)),
            StageDesign(promote = Some(3), demote = Some(2), brackets = false)
          )
        )
        val problems = Validation.validate(bad, twoPlayer, Some(hour), None).filterNot(_.warning).map(_.describe)
        assert(problems.exists(_.startsWith("stage 1: demote must be greater than 1")), problems.toString)
        assert(
          problems.exists(_.contains("stage 2: with brackets, the number advancing (3) must divide")),
          problems.toString
        )
        assert(problems.exists(_.startsWith("stage 3: promote must be a better rank")), problems.toString)
    }

    test("a round duration must be set somewhere, and is inherited from the nearest stage that sets one (Q10)") {
        val inner = TournamentDesign(List(StageDesign()))
        val design = TournamentDesign(List(StageDesign(roundDuration = Some(Duration.ofDays(2)), design = Some(inner))))
        assertEquals(Validation.validate(design, twoPlayer, None, None).filterNot(_.warning), Nil)
        assertEquals(Clock.duration(design, List(0), 0, None), Some(Duration.ofDays(2)))
        assertEquals(Clock.duration(TournamentDesign(List(StageDesign())), Nil, 0, Some(hour)), Some(hour))
        assert(Validation.validate(TournamentDesign(List(StageDesign())), twoPlayer, None, None).exists(!_.warning))
    }

    test("a sequential game gives each seat a chess clock of d/n; a simultaneous one a turn limit of d/t") {
        assertEquals(
          Clock.forMatch(twoPlayer, hour, 2, tiesAllowed = true, choosing = false, None).limit,
          Duration.ofMinutes(30)
        )
        val sim = twoPlayer.copy(moveType = com.vivi.matchmaker.model.MoveType.Simultaneous, maxTurns = Some(5))
        assertEquals(
          Clock.forMatch(sim, hour, 2, tiesAllowed = true, choosing = false, None).limit,
          Duration.ofMinutes(12)
        )
        // One more turn when ties are not allowed, and one more for choosing roles: 60 / 7.
        assertEquals(
          Clock.forMatch(sim, Duration.ofMinutes(70), 2, tiesAllowed = false, choosing = true, None).limit,
          Duration.ofMinutes(10)
        )
    }

    test("a player seated twice forfeits all but their strongest seed's seat") {
        assertEquals(MultipleEntries.forfeits(List((1L, 100L, 3), (2L, 100L, 1), (3L, 200L, 2))), Set(1L))
    }

    test("a design and a state round-trip through JSON, a design written with only what differs from the defaults") {
        val design = StandardDesigns.build("world-cup", 2, None).toOption.get
        assertEquals(read[TournamentDesign](write(design)), design)
        assert(!write(TournamentDesign(List(StageDesign()))).contains("allowTies"))
        val played = play(design, 9, random(3), maxRounds = 4)
        assertEquals(read[TournamentState](write(played.state)), played.state)
    }
}
