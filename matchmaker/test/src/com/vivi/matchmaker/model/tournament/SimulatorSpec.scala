package com.vivi.matchmaker.model.tournament

import munit.FunSuite
import Fixtures.*

/** Whole tournaments, played through [[Tournament]] with made-up results. */
class SimulatorSpec extends FunSuite {

    test("a late entry joins stratum 0 and plays from the next run") {
        val design =
            TournamentDesign(List(StageDesign(repeat = Repetition.Fixed(3), hasBrackets = false, demote = None)))
        val (_, ratings) = field(4)
        val env = Env(design = design, game = twoPlayer, ratings = ratings + (99L -> 5000))
        val played = play(
          design = design,
          n = 4,
          oracle = favourites,
          between = (s, round) => if (round == 1) Tournament.lateEntry(state = s, entrant = 99L, env = env) else s,
          extraRatings = Map(99L -> 5000)
        )
        assert(played.rounds.head.forall(!_.planned.seats.exists(_.entrant == 99L)))
        assert(played.rounds.drop(1).exists(_.exists(_.planned.seats.exists(_.entrant == 99L))))
        // Rated highest, the newcomer takes the top seed.
        assertEquals(played.state.root.members.find(_.entrant == 99L).map(_.initialSeed), Some(5))
    }

    test("a round robin of a pool of four plays three rounds and ranks by points") {
        val design = TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(4), demote = None)))
        val played = play(design = design, n = 4, oracle = favourites)
        assertEquals(played.rounds.size, 3)
        assertEquals(played.ranking, Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 4))
    }

    test("a rubber breaks a tie in a two-player round robin, in a round of its own") {
        val design = TournamentDesign(
          List(StageDesign(hasBrackets = false, poolSize = Some(2), demote = None, hasRubberMatch = true))
        )
        // A draw, then whatever the rubber says: here the favourite.
        var calls = 0
        val drawThenFavourite: Oracle = m => {
            calls += 1
            if (m.planned.isRubber) favourites(m) else draws(m)
        }
        val played = play(design = design, n = 2, oracle = drawThenFavourite)
        assertEquals(played.rounds.size, 2)
        assert(played.rounds(1).forall(r => r.planned.isRubber && !r.canTie))
        assertEquals(played.ranking, Map(1L -> 1, 2L -> 2))
    }

    test("a three-player game's round robin seats three a match") {
        val g = game(roles = 3)
        val design = TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(4), demote = None)))
        val played = play(design = design, n = 4, oracle = favourites, g = g)
        assert(played.rounds.flatten.forall(_.planned.seats.size == 3))
        assertEquals(played.rounds.flatten.size, 4)
        assert(played.state.isFinished)
    }

    // ---- whether a match may be drawn: by how many matches its pool plays ----

    private def roundRobin(poolSize: Int) =
        TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(poolSize), demote = None)))

    test("a pool of two playing one match may not draw it, but a pool of four's round robin may") {
        val two = play(design = roundRobin(2), n = 2, oracle = favourites).rounds.flatten
        val four = play(design = roundRobin(4), n = 4, oracle = favourites).rounds.flatten
        assertEquals(two.map(_.canTie), List(false))
        assertEquals(four.size, 6)
        assert(four.forall(_.canTie))
    }

    test("a pool of two that plays twice, the roles turned, may draw either match") {
        val design = TournamentDesign(
          List(StageDesign(hasBrackets = false, poolSize = Some(2), demote = None, isRotatingRoles = true))
        )
        val played = play(design = design, n = 2, oracle = favourites).rounds.flatten
        assertEquals(played.size, 2)
        assert(played.forall(_.canTie))
    }

    test("a game without roles that seats a whole pool in one match may not draw it, however many are in it") {
        val played =
            play(design = roundRobin(4), n = 4, oracle = favourites, g = roleless(most = Some(4))).rounds.flatten
        assertEquals(played.map(m => (m.planned.seats.size, m.canTie)), List((4, false)))
    }

    test("a stage that allows draws lets its pool's only match be drawn, as a challenge's") {
        val design = TournamentDesign(
          List(StageDesign(hasBrackets = false, poolSize = Some(2), demote = None, canTie = Some(true)))
        )
        assertEquals(play(design = design, n = 2, oracle = favourites).rounds.flatten.map(_.canTie), List(true))
    }
}
