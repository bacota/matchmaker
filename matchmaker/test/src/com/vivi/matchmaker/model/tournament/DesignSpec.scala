package com.vivi.matchmaker.model.tournament

import java.time.Duration
import munit.FunSuite
import upickle.default.{read, write}
import TournamentCodecs.given
import Fixtures.*
import standard.StandardModels

class DesignSpec extends FunSuite {

    test("every standard design is valid for a two-player game, with no errors") {
        StandardDesigns.all.foreach { std =>
            val design = StandardDesigns.build(key = std.key, r = 2, poolSize = Some(4)).toOption.get
            val errors = Validation
                .validate(design = design, game = twoPlayer, maxTurns = None, defaultPoolSize = None)
                .filterNot(_.isWarning)
            assertEquals(errors, Nil, std.key)
        }
    }

    test("a two-role format is refused for a game of more players") {
        assert(StandardDesigns.build(key = "double-elimination", r = 3, poolSize = None).isLeft)
        assert(StandardDesigns.build(key = "single-elimination", r = 3, poolSize = None).isRight)
    }

    test("validation names what is wrong, and where") {
        val bad = TournamentDesign(
          List(
            StageDesign(demote = Some(1)),
            StageDesign(poolSize = Some(4), demote = Some(4)),
            StageDesign(promote = Some(3), demote = Some(2), hasBrackets = false)
          )
        )
        val problems = Validation
            .validate(design = bad, game = twoPlayer, maxTurns = None, defaultPoolSize = None)
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

    test("every standard model is valid with the pool size it suggests, and only heats leaves a stage unsized") {
        StandardModels.all.foreach { m =>
            assertEquals(
              Validation
                  .designProblems(design = m.design, game = twoPlayer, defaultPoolSize = m.poolSize)
                  .filterNot(_.isWarning),
              Nil,
              m.id
            )
        }
        val heats = standard.Heats
        assertEquals(heats.poolSize, Some(4))
        assertEquals(heats.design.stages.head.poolSize, None)
        def sized(d: TournamentDesign): Boolean = d.stages.forall(s => s.poolSize.isDefined && s.design.forall(sized))
        assert(sized(TournamentDesign(heats.design.stages.tail)), heats.design.stages.tail)
    }

    test("each standard model is named once, by a descriptive id, at a version") {
        val ids = StandardModels.all.map(_.id)
        assertEquals(ids.distinct, ids)
        StandardModels.all.foreach { m =>
            assert(m.version >= 1, m.id)
            assert(m.id.matches("[a-z0-9-]+"), m.id)
            assertEquals(StandardModels.find(m.id), Some(m))
        }
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

    test("a sequential game gives each seat a chess clock of d/n; a simultaneous one a turn limit of d/t") {
        assertEquals(
          Clock.forMatch(game = twoPlayer, d = hour, seats = 2, tiesAllowed = true, choosing = false, None).limit,
          Duration.ofMinutes(30)
        )
        val sim = twoPlayer.copy(moveType = com.vivi.matchmaker.model.MoveType.Simultaneous, maxTurns = Some(5))
        assertEquals(
          Clock.forMatch(game = sim, d = hour, seats = 2, tiesAllowed = true, choosing = false, None).limit,
          Duration.ofMinutes(12)
        )
        // One more turn when ties are not allowed, and one more for choosing roles: 60 / 7.
        assertEquals(
          Clock
              .forMatch(game = sim, d = Duration.ofMinutes(70), seats = 2, tiesAllowed = false, choosing = true, None)
              .limit,
          Duration.ofMinutes(10)
        )
    }

    test("a player seated twice forfeits all but their strongest seed's seat") {
        assertEquals(MultipleEntries.forfeits(List((1L, 100L, 3), (2L, 100L, 1), (3L, 200L, 2))), Set(1L))
    }

    test("a design and a state round-trip through JSON, a design written with only what differs from the defaults") {
        val design = StandardDesigns.build(key = "world-cup", r = 2, poolSize = None).toOption.get
        assertEquals(read[TournamentDesign](write(design)), design)
        assert(!write(TournamentDesign(List(StageDesign()))).contains("allowTies"))
        val played = play(design = design, n = 9, oracle = random(3), maxRounds = 4)
        assertEquals(read[TournamentState](write(played.state)), played.state)
    }
}
