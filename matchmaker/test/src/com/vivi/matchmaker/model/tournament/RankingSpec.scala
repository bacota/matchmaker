package com.vivi.matchmaker.model.tournament

import munit.FunSuite
import Fixtures.*

class RankingSpec extends FunSuite {

    private def placed(ranks: (Long, Option[Int])*) = ranks.toList.map((e, r) => Placing(e, r))

    test("the design's worked example: 2 points a place, 1 a draw, and a 50% bonus for first") {
        val alone =
            Ranking.points(placed(1L -> Some(1), 2L -> Some(2), 3L -> Some(3), 4L -> Some(4)), 2, 1, bonus = true)
        assertEquals(alone, Map(1L -> 9.0, 2L -> 4.0, 3L -> 2.0, 4L -> 0.0))
        val tied =
            Ranking.points(placed(1L -> Some(1), 2L -> Some(1), 3L -> Some(3), 4L -> Some(3)), 2, 1, bonus = true)
        assertEquals(tied, Map(1L -> 7.5, 2L -> 7.5, 3L -> 1.0, 4L -> 1.0))
    }

    test("a seat with no rank scores nothing, and counts below every ranked seat") {
        val pts = Ranking.points(placed(1L -> Some(1), 2L -> None), 2, 1, bonus = false)
        assertEquals(pts, Map(1L -> 2.0, 2L -> 0.0))
    }

    test("a score differential is a seat's score less its opponents' mean, or ranks where scores are missing") {
        val scored = List(Placing(1L, Some(1), Some(30)), Placing(2L, Some(2), Some(27)))
        assertEquals(Ranking.scoreDiff(scored, 1L), 3.0)
        assertEquals(Ranking.scoreDiff(scored.map(_.copy(score = None)), 1L), 1.0)
        assertEquals(Ranking.scoreDiff(scored.map(_.copy(score = None)), 2L), -1.0)
    }

    private val seeds = Map(1L -> 1, 2L -> 2, 3L -> 3)
    private def m(a: Long, ra: Int, b: Long, rb: Int, sa: Double = 0, sb: Double = 0) =
        List(Placing(a, Some(ra), Some(sa)), Placing(b, Some(rb), Some(sb)))

    test("a pool is ranked by points, then score differential, then total score") {
        // Everyone beats one other: level on points; 3's wins were bigger.
        val matches = List(m(1, 1, 2, 2, 11, 10), m(2, 1, 3, 2, 11, 10), m(3, 1, 1, 2, 20, 10))
        val ranking = Ranking.rankPool(seeds, matches, Map.empty, StageDesign(), twoPlayer)
        assertEquals(ranking.ranks(3L), 1)
        assert(ranking.ranks(1L) > 1 && ranking.ranks(2L) > 1)
    }

    test("a tie nothing breaks goes to the weaker seed in a pool of two, and stands in a larger pool (Q6)") {
        val draw = List(List(Placing(1L, Some(1), Some(5)), Placing(2L, Some(1), Some(5))))
        val pair = Map(1L -> 1, 2L -> 2)
        val strict = Ranking.rankPool(pair, draw, Map.empty, StageDesign(), twoPlayer)
        assertEquals(strict.ranks, Map(2L -> 1, 1L -> 2))
        // A third member who has played nothing yet: the two level on a point each stay level.
        val loose = Ranking.rankPool(pair + (3L -> 3), draw, Map.empty, StageDesign(), twoPlayer)
        assertEquals(loose.ranks.view.filterKeys(Set(1L, 2L)).toMap, Map(1L -> 1, 2L -> 1))
    }

    test("a rubber is asked for to break a pair's tie in a two-role game, and settles it once played (Q7)") {
        val draw = List(List(Placing(1L, Some(1)), Placing(2L, Some(1))))
        val pair = Map(1L -> 1, 2L -> 2)
        val rubber = StageDesign(hasRubberMatch = true)
        assertEquals(Ranking.rankPool(pair, draw, Map.empty, rubber, twoPlayer).rubbers, List((1L, 2L)))
        val played = Map(Set(1L, 2L) -> List(Placing(1L, Some(2)), Placing(2L, Some(1))))
        assertEquals(Ranking.rankPool(pair, draw, played, rubber, twoPlayer).ranks, Map(2L -> 1, 1L -> 2))
        // In a game of three roles a rubber is not used: scores decide, and level scores stand.
        assertEquals(Ranking.rankPool(pair, draw, Map.empty, rubber, game(roles = 3)).rubbers, Nil)
    }

    test("ranks are shared by ties, the next skipping past them") {
        assertEquals(
          Ranking.competition(List(List("a"), List("b", "c"), List("d"))),
          Map("a" -> 1, "b" -> 2, "c" -> 2, "d" -> 4)
        )
    }

    test("a tournament ranks its final run first, by stratum then pool rank, and those eliminated earlier below") {
        val first = CompletedRun(
          Map(
            1L -> RunPlacing(0, 1, 0, isEliminated = false),
            2L -> RunPlacing(0, 2, -1, isEliminated = true),
            3L -> RunPlacing(0, 1, 0, isEliminated = false),
            4L -> RunPlacing(0, 2, -1, isEliminated = true)
          )
        )
        val last = CompletedRun(
          Map(
            1L -> RunPlacing(0, 1, 0, isEliminated = false),
            3L -> RunPlacing(0, 2, -1, isEliminated = true)
          )
        )
        assertEquals(Ranking.rankNode(List(first, last), Set.empty), Map(1L -> 1, 3L -> 2, 2L -> 3, 4L -> 3))
    }

    test("a higher stratum ranks above a lower one, and a tie-breaker run settles a tie") {
        val heats = CompletedRun(Map(1L -> RunPlacing(0, 2, 0, false), 2L -> RunPlacing(0, 1, 0, false)))
        val finals = CompletedRun(
          Map(
            1L -> RunPlacing(0, 1, 0, false),
            2L -> RunPlacing(0, 1, 0, false),
            3L -> RunPlacing(-1, 1, -1, false)
          )
        )
        assertEquals(Ranking.rankNode(List(heats, finals), Set.empty), Map(1L -> 1, 2L -> 1, 3L -> 3))
        assertEquals(Ranking.rankNode(List(heats, finals), Set(0)), Map(2L -> 1, 1L -> 2, 3L -> 3))
    }
}
