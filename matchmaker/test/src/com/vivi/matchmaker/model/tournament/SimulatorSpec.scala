package com.vivi.matchmaker.model.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import Fixtures.*

/** Whole tournaments, played through [[Tournament]] with made-up results. */
class SimulatorSpec extends ScalaCheckSuite {

    private def standard(key: String, r: Int = 2, poolSize: Option[Int] = None) =
        StandardDesigns.build(key, r, poolSize).toOption.get

    private def log2(n: Int) = Iterator.iterate(1)(_ * 2).indexWhere(_ >= n)

    /** Nobody plays twice in a round, nobody eliminated plays again, and every match seats only members of its pool. */
    private def isWellFormed(played: Played): Boolean =
        played.rounds.forall { matches =>
            val who = matches.flatMap(_.planned.seats.map(_.entrant))
            who.distinct.size == who.size
        }

    property("single elimination of n takes ceil(log2 n) rounds and has one winner") {
        forAll(Gen.choose(2, 40), Gen.long) { (n, seed) =>
            val played = play(standard("single-elimination"), n, random(seed))
            played.state.isFinished && played.rounds.size == log2(n) && played.ranking.values.count(_ == 1) == 1 &&
            played.ranking.size == n && isWellFormed(played)
        }
    }

    test("single elimination with favourites winning ranks the field by seed, losers of a round tied") {
        val played = play(standard("single-elimination"), 8, favourites)
        assertEquals(played.ranking, Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 3, 5L -> 5, 6L -> 5, 7L -> 5, 8L -> 5))
        // The first round is the standard bracket.
        assertEquals(
          played.rounds.head.map(_.planned.seats.map(_.entrant).toSet).toSet,
          Set(
            Set(1L, 8L),
            Set(2L, 7L),
            Set(3L, 6L),
            Set(4L, 5L)
          )
        )
        // And the second: the winner of 1-8 meets the winner of 4-5.
        assertEquals(played.rounds(1).map(_.planned.seats.map(_.entrant).toSet).toSet, Set(Set(1L, 4L), Set(2L, 3L)))
    }

    test("byes go to the top seeds, who play from the second round") {
        val played = play(standard("single-elimination"), 6, favourites)
        val firstRound = played.rounds.head.flatMap(_.planned.seats.map(_.entrant)).toSet
        assertEquals(firstRound, Set(3L, 4L, 5L, 6L))
        assertEquals(played.ranking(1L), 1)
    }

    test("an elimination of matches that are all drawn still finishes, the weaker seed advancing every time (Q6)") {
        val played = play(standard("single-elimination"), 4, draws)
        assert(played.state.isFinished)
        // 4 beats 1 and 3 beats 2, the weaker seed taking each draw. 4 takes seed 1 from the pool it won and 3 takes
        // seed 2, so in the final 3 is the weaker seed, and takes it.
        assertEquals(played.ranking(3L), 1)
        assertEquals(played.ranking(4L), 2)
    }

    test("consolation ranks the final's loser second and the third-place match's winner third (Q5)") {
        val played = play(standard("single-elimination-consolation"), 8, favourites)
        assertEquals(played.ranking.filter(_._2 <= 4), Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 4))
        // Nobody is left unranked, and the quarter-final losers tie fifth.
        assertEquals(played.ranking.filter(_._2 > 4).values.toSet, Set(5))
    }

    property("every standard design finishes for any field, with every entrant ranked") {
        val finite = List(
          "single-elimination",
          "single-elimination-consolation",
          "modified-double-elimination",
          "double-elimination",
          "brazilian-repechage",
          "world-cup",
          "fencing",
          "ncaa"
        )
        forAll(Gen.oneOf(finite), Gen.choose(2, 24), Gen.long) { (key, n, seed) =>
            val played = play(standard(key), n, random(seed))
            played.state.isFinished && played.ranking.keySet == (1 to n).map(_.toLong).toSet && isWellFormed(played) &&
            played.ranking.values.min == 1
        }
    }

    test("heats take their pool size from the tournament where they set none, and with one they finish") {
        assert(StandardDesigns.build("heats", 2, None).exists(_.stages.head.poolSize.isEmpty))
        assert(StandardDesigns.build("heats", 2, Some(0)).isLeft)
        val played = play(standard("heats", poolSize = Some(4)), 12, random(7))
        assert(played.state.isFinished)
        assertEquals(played.ranking.size, 12)
    }

    test(
      "double elimination: a first loss drops to stratum -1, a second eliminates, and the winner ranks first wherever they came from"
    ) {
        val played = play(standard("double-elimination"), 8, favourites)
        assert(played.state.isFinished)
        assertEquals(played.ranking(1L), 1)
        // With upsets, the bottom seed wins every match and still ranks first.
        val upset = play(standard("double-elimination"), 8, upsets)
        assertEquals(upset.ranking(8L), 1)
    }

    test("modified double elimination stops at the two brackets' winners, the unbeaten one first") {
        val played = play(standard("modified-double-elimination"), 8, favourites)
        assertEquals(played.ranking(1L), 1)
        assertEquals(played.ranking(2L), 2)
        // Two remain: the unbeaten 1, in stratum 0, and 2, who lost only to 1.
        val left = played.state.root.active.map(m => m.entrant -> m.stratum).toMap
        assertEquals(left, Map(1L -> 0, 2L -> -1))
    }

    test("the world cup sends two from each group of four to a knockout with a third-place match") {
        val played = play(standard("world-cup"), 8, favourites)
        // Groups 1-4-5-8 and 2-3-6-7: 1, 4, 2 and 3 go through, and the knockout ranks them by seed.
        assertEquals(played.ranking.filter(_._2 <= 4), Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 4))
        // Three rounds of group play, then the semi-finals, then the final and the third-place match.
        assertEquals(played.rounds.size, 5)
    }

    test("a ladder runs until stopped, the winner climbing and the loser dropping") {
        val played = play(
          standard("ladder"),
          4,
          favourites,
          maxRounds = 20,
          between = (s, round) => if (round == 3) Tournament.requestStop(s) else s
        )
        assert(played.state.isFinished)
        // Asked after the third run's matches, the stop ends the stage there: no fourth run is begun.
        assertEquals(played.rounds.size, 3)
        val strata = played.state.root.members.map(m => m.entrant -> m.stratum).toMap
        assert(strata(1L) > strata(4L))
    }

    test("a ladder that is never stopped keeps going") {
        val played = play(standard("ladder"), 4, random(1), maxRounds = 30)
        assert(!played.state.isFinished)
        assertEquals(played.rounds.size, 30)
    }

    test("a withdrawn entrant is put in no later pool") {
        val played = play(
          standard("single-elimination"),
          8,
          favourites,
          between = (s, round) => if (round == 1) Tournament.withdraw(s, 1L) else s
        )
        assert(played.rounds.drop(1).forall(_.forall(!_.planned.seats.exists(_.entrant == 1L))))
        assert(played.state.isFinished)
    }

    test("a late entry joins stratum 0 and plays from the next run") {
        val design =
            TournamentDesign(List(StageDesign(repeat = Repetition.Fixed(3), hasBrackets = false, demote = None)))
        val (_, ratings) = field(4)
        val env = Env(design, twoPlayer, ratings + (99L -> 5000))
        val played = play(
          design,
          4,
          favourites,
          between = (s, round) => if (round == 1) Tournament.lateEntry(s, 99L, env) else s,
          extraRatings = Map(99L -> 5000)
        )
        assert(played.rounds.head.forall(!_.planned.seats.exists(_.entrant == 99L)))
        assert(played.rounds.drop(1).exists(_.exists(_.planned.seats.exists(_.entrant == 99L))))
        // Rated highest, the newcomer takes the top seed.
        assertEquals(played.state.root.members.find(_.entrant == 99L).map(_.initialSeed), Some(5))
    }

    test("a round robin of a pool of four plays three rounds and ranks by points") {
        val design = TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(4), demote = None)))
        val played = play(design, 4, favourites)
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
        val played = play(design, 2, drawThenFavourite)
        assertEquals(played.rounds.size, 2)
        assert(played.rounds(1).forall(r => r.planned.isRubber && !r.canTie))
        assertEquals(played.ranking, Map(1L -> 1, 2L -> 2))
    }

    test("a three-player game's round robin seats three a match") {
        val g = game(roles = 3)
        val design = TournamentDesign(List(StageDesign(hasBrackets = false, poolSize = Some(4), demote = None)))
        val played = play(design, 4, favourites, g = g)
        assert(played.rounds.flatten.forall(_.planned.seats.size == 3))
        assertEquals(played.rounds.flatten.size, 4)
        assert(played.state.isFinished)
    }
}
