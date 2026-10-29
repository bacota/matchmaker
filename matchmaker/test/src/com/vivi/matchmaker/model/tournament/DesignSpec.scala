package com.vivi.matchmaker.model.tournament

import java.time.Duration
import munit.FunSuite
import upickle.default.{read, write}
import TournamentCodecs.given
import Fixtures.*

class DesignSpec extends FunSuite {

    test("validation names what is wrong, and where") {
        val bad = TournamentDesign(
          List(
            StageDesign(demote = Some(1)),
            StageDesign(poolSize = Some(4), demote = Some(4)),
            StageDesign(promote = Some(3), demote = Some(2), hasBrackets = false)
          )
        )
        val problems = Validation
            .validate(design = bad, game = twoPlayer, roundDuration = None, turnDuration = None, defaultPoolSize = None)
            .filterNot(_.isWarning)
            .map(_.describe)
        assert(problems.exists(_.startsWith("stage 1: demote must be greater than 1")), problems.toString)
        assert(
          problems.exists(_.contains("stage 2: with brackets, the number advancing (3) must divide")),
          problems.toString
        )
        assert(problems.exists(_.startsWith("stage 3: promote must be a better rank")), problems.toString)
    }

    test("a stage with no pool size takes the tournament's default, and a model alone is checked as pools of R") {
        // Three advance from each bracketed pool: a pool of the game's 2 cannot send three, a pool of 6 can.
        val design = TournamentDesign(List(StageDesign(demote = Some(4))))
        assert(
          Validation.designProblems(design = design, game = twoPlayer, defaultPoolSize = None).exists(!_.isWarning)
        )
        assertEquals(
          Validation
              .designProblems(design = design, game = twoPlayer, defaultPoolSize = Some(6))
              .filterNot(_.isWarning),
          Nil
        )
        assertEquals(
          Env(design = design, game = twoPlayer, ratings = Map.empty, defaultPoolSize = Some(6))
              .poolSize(design.stages.head),
          6
        )
        assertEquals(Env(design = design, game = twoPlayer, ratings = Map.empty).poolSize(design.stages.head), 2)
    }

    test("a match may not be drawn as its pool's only match, nor ever in a rubber; in a pool of more it may") {
        assert(!Ties.isAllowed(matches = 1))
        assert(Ties.isAllowed(matches = 2))
        assert(!Ties.isAllowed(matches = 3, rubber = true))
    }

    test(
      "a stage that says whether its matches may be drawn is followed, whatever its pools play, but not in a rubber"
    ) {
        assert(Ties.isAllowed(matches = 1, stage = Some(true)))
        assert(!Ties.isAllowed(matches = 3, stage = Some(false)))
        assert(!Ties.isAllowed(matches = 1, rubber = true, stage = Some(true)))
    }

    test("a sequential game gives each seat a chess clock of d/n; a simultaneous one the turn duration") {
        assertEquals(
          Clock.forMatch(game = twoPlayer, round = Some(hour), turn = None, seats = 2).map(_.limit),
          Some(Duration.ofMinutes(30))
        )
        val sim = twoPlayer.copy(moveType = com.vivi.matchmaker.model.MoveType.Simultaneous)
        assertEquals(
          Clock.forMatch(game = sim, round = Some(hour), turn = Some(Duration.ofMinutes(5)), seats = 2),
          Some(MatchClock(kind = com.vivi.matchmaker.model.TimeLimitKind.PerTurn, limit = Duration.ofMinutes(5)))
        )
        // Each is clocked by its own duration alone.
        assertEquals(Clock.forMatch(game = sim, round = Some(hour), turn = None, seats = 2), None)
        assertEquals(Clock.forMatch(game = twoPlayer, round = None, turn = Some(hour), seats = 2), None)
    }

    test("a simultaneous game with a round duration needs a turn duration") {
        val sim = twoPlayer.copy(moveType = com.vivi.matchmaker.model.MoveType.Simultaneous)
        val design = TournamentDesign(List(StageDesign()))
        def problems(round: Option[Duration], turn: Option[Duration]) =
            Validation
                .validate(
                  design = design,
                  game = sim,
                  roundDuration = round,
                  turnDuration = turn,
                  defaultPoolSize = None
                )
                .map(_.message)
        assert(
          problems(round = Some(hour), turn = None).contains(com.vivi.matchmaker.ErrorText.design.needsTurnDuration)
        )
        assertEquals(problems(round = Some(hour), turn = Some(Duration.ofMinutes(5))), Nil)
        assertEquals(problems(round = None, turn = None), Nil)
    }

    test("a player seated twice forfeits all but their strongest seed's seat") {
        assertEquals(MultipleEntries.forfeits(List((1L, 100L, 3), (2L, 100L, 1), (3L, 200L, 2))), Set(1L))
    }

    test("a design and a state round-trip through JSON, a design written with only what differs from the defaults") {
        val design = TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(4), demote = None)))
        assertEquals(read[TournamentDesign](write(design)), design)
        assert(!write(TournamentDesign(List(StageDesign()))).contains("allowTies"))
        val played = play(design = design, n = 4, oracle = random(3), maxRounds = 4)
        assertEquals(read[TournamentState](write(played.state)), played.state)
    }
}
