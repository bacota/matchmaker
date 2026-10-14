package com.vivi.matchmaker.model.tournament

import munit.FunSuite
import Fixtures.*

class RankingSpec extends FunSuite {

    private def placed(ranks: (Long, Option[Int])*) = ranks.toList.map((e, r) => Placing(entrant = e, rank = r))

    test("the design's worked example: 2 points a place, 1 a draw, and a 50% bonus for first") {
        val alone =
            Ranking.points(
              placings = placed(1L -> Some(1), 2L -> Some(2), 3L -> Some(3), 4L -> Some(4)),
              points = 2,
              draw = 1,
              bonus = true
            )
        assertEquals(alone, Map(1L -> 9.0, 2L -> 4.0, 3L -> 2.0, 4L -> 0.0))
        val tied =
            Ranking.points(
              placings = placed(1L -> Some(1), 2L -> Some(1), 3L -> Some(3), 4L -> Some(3)),
              points = 2,
              draw = 1,
              bonus = true
            )
        assertEquals(tied, Map(1L -> 7.5, 2L -> 7.5, 3L -> 1.0, 4L -> 1.0))
    }

    test("a seat with no rank scores nothing, and counts below every ranked seat") {
        val pts = Ranking.points(placings = placed(1L -> Some(1), 2L -> None), points = 2, draw = 1, bonus = false)
        assertEquals(pts, Map(1L -> 2.0, 2L -> 0.0))
    }

    test("a score differential is a seat's score less its opponents' mean, or ranks where scores are missing") {
        val scored = List(
          Placing(entrant = 1L, rank = Some(1), score = Some(30)),
          Placing(entrant = 2L, rank = Some(2), score = Some(27))
        )
        assertEquals(Ranking.scoreDiff(scored, 1L), 3.0)
        assertEquals(Ranking.scoreDiff(scored.map(_.copy(score = None)), 1L), 1.0)
        assertEquals(Ranking.scoreDiff(scored.map(_.copy(score = None)), 2L), -1.0)
    }

    private val seeds = Map(1L -> 1, 2L -> 2, 3L -> 3)
    private def m(a: Long, ra: Int, b: Long, rb: Int, sa: Double = 0, sb: Double = 0) =
        List(
          Placing(entrant = a, rank = Some(ra), score = Some(sa)),
          Placing(entrant = b, rank = Some(rb), score = Some(sb))
        )

    test("a pool is ranked by points, then score differential, then total score") {
        // Everyone beats one other: level on points; 3's wins were bigger.
        val matches = List(
          m(a = 1, ra = 1, b = 2, rb = 2, sa = 11, sb = 10),
          m(a = 2, ra = 1, b = 3, rb = 2, sa = 11, sb = 10),
          m(a = 3, ra = 1, b = 1, rb = 2, sa = 20, sb = 10)
        )
        val ranking = Ranking.rankPool(
          seedIn = seeds,
          matches = matches,
          rubbers = Map.empty,
          stage = StageDesign(),
          game = twoPlayer
        )
        assertEquals(ranking.ranks(3L), 1)
        assert(ranking.ranks(1L) > 1 && ranking.ranks(2L) > 1)
    }

    test("a tie nothing breaks goes to the weaker seed in a pool of two, and stands in a larger pool (Q6)") {
        val draw = List(
          List(
            Placing(entrant = 1L, rank = Some(1), score = Some(5)),
            Placing(entrant = 2L, rank = Some(1), score = Some(5))
          )
        )
        val pair = Map(1L -> 1, 2L -> 2)
        val strict = Ranking.rankPool(
          seedIn = pair,
          matches = draw,
          rubbers = Map.empty,
          stage = StageDesign(),
          game = twoPlayer
        )
        assertEquals(strict.ranks, Map(2L -> 1, 1L -> 2))
        // A third member who has played nothing yet: the two level on a point each stay level.
        val loose = Ranking.rankPool(
          seedIn = pair + (3L -> 3),
          matches = draw,
          rubbers = Map.empty,
          stage = StageDesign(),
          game = twoPlayer
        )
        assertEquals(loose.ranks.view.filterKeys(Set(1L, 2L)).toMap, Map(1L -> 1, 2L -> 1))
    }

    test("a rubber is asked for to break a pair's tie in a two-role game, and settles it once played (Q7)") {
        val draw = List(List(Placing(entrant = 1L, rank = Some(1)), Placing(entrant = 2L, rank = Some(1))))
        val pair = Map(1L -> 1, 2L -> 2)
        val rubber = StageDesign(hasRubberMatch = true)
        assertEquals(
          Ranking
              .rankPool(seedIn = pair, matches = draw, rubbers = Map.empty, stage = rubber, game = twoPlayer)
              .rubbers,
          List((1L, 2L))
        )
        val played =
            Map(Set(1L, 2L) -> List(Placing(entrant = 1L, rank = Some(2)), Placing(entrant = 2L, rank = Some(1))))
        assertEquals(
          Ranking.rankPool(seedIn = pair, matches = draw, rubbers = played, stage = rubber, game = twoPlayer).ranks,
          Map(2L -> 1, 1L -> 2)
        )
        // In a game of three roles a rubber is not used: scores decide, and level scores stand.
        assertEquals(
          Ranking
              .rankPool(seedIn = pair, matches = draw, rubbers = Map.empty, stage = rubber, game = game(roles = 3))
              .rubbers,
          Nil
        )
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
            1L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false),
            2L -> RunPlacing(poolStratum = 0, rank = 2, stratumOut = -1, isEliminated = true),
            3L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false),
            4L -> RunPlacing(poolStratum = 0, rank = 2, stratumOut = -1, isEliminated = true)
          )
        )
        val last = CompletedRun(
          Map(
            1L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false),
            3L -> RunPlacing(poolStratum = 0, rank = 2, stratumOut = -1, isEliminated = true)
          )
        )
        assertEquals(Ranking.rankNode(List(first, last), Set.empty), Map(1L -> 1, 3L -> 2, 2L -> 3, 4L -> 3))
    }

    test("a higher stratum ranks above a lower one, and a tie-breaker run settles a tie") {
        val heats = CompletedRun(
          Map(
            1L -> RunPlacing(poolStratum = 0, rank = 2, stratumOut = 0, isEliminated = false),
            2L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false)
          )
        )
        val finals = CompletedRun(
          Map(
            1L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false),
            2L -> RunPlacing(poolStratum = 0, rank = 1, stratumOut = 0, isEliminated = false),
            3L -> RunPlacing(poolStratum = -1, rank = 1, stratumOut = -1, isEliminated = false)
          )
        )
        assertEquals(Ranking.rankNode(List(heats, finals), Set.empty), Map(1L -> 1, 2L -> 1, 3L -> 3))
        assertEquals(Ranking.rankNode(List(heats, finals), Set(0)), Map(2L -> 1, 1L -> 2, 3L -> 3))
    }
}
