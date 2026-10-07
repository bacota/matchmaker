package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop._

/** Points from ranks (D8), and the score differential the `SCORE` tiebreaker sums. */
class PointsSpec extends ScalaCheckSuite {

    private def points(ranks: Int*): List[Int] = {
        val keyed = ranks.zipWithIndex.map((r, i) => i -> Option(r)).toMap
        val scored = Points.of(keyed)
        ranks.indices.map(scored).toList
    }

    test("the worked table holds") {
        assertEquals(points(1, 2), List(3, 0))
        assertEquals(points(1, 1), List(1, 1))
        assertEquals(points(1, 2, 3), List(8, 3, 0))
        assertEquals(points(1, 1, 3), List(4, 4, 0))
        assertEquals(points(1, 2, 2), List(8, 1, 1))
        assertEquals(points(1, 1, 1), List(1, 1, 1))
    }

    test("for two players it is three for a win, one each for a draw, and nothing for a loss") {
        assertEquals(points(2, 1), List(0, 3))
        assertEquals(points(5, 5), List(1, 1))
    }

    test("a seat with no rank scores nothing, and is not counted among the seats") {
        val scored = Points.of(Map("a" -> Some(1), "b" -> Some(2), "c" -> None))
        assertEquals(scored, Map("a" -> 3, "b" -> 0, "c" -> 0))
        assertEquals(Points.of(Map("a" -> None, "b" -> None)), Map("a" -> 0, "b" -> 0))
    }

    private val ranksGen: Gen[List[Int]] =
        Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, Gen.choose(1, n)))

    property("a better rank never scores fewer points, and a strictly better one scores more") {
        forAll(ranksGen) { ranks =>
            val scored = points(ranks*)
            val pairs = for { i <- ranks.indices; j <- ranks.indices if ranks(i) < ranks(j) } yield (i, j)
            pairs.forall((i, j) => scored(i) > scored(j)) &&
            ranks.indices.forall(i => ranks.indices.forall(j => ranks(i) != ranks(j) || scored(i) == scored(j)))
        }
    }

    // Places come from the order of the ranks, so any convention for numbering ties scores the same.
    property("ranks that keep the same order score alike: 1, 1, 3 and 1, 1, 2 are the same result") {
        forAll(ranksGen) { ranks =>
            val dense = ranks.distinct.sorted.zipWithIndex.toMap.view.mapValues(_ + 1).toMap
            val stretched = ranks.map(r => r * 10 + 3)
            points(ranks*) == points(ranks.map(dense)*) && points(ranks*) == points(stretched*)
        }
    }

    test("the differential is the score less the opponents' mean, or ranks standing in for scores") {
        val ranks = Map("a" -> Some(1), "b" -> Some(2), "c" -> Some(3))
        assertEquals(
          Points.differential(ranks, Map("a" -> 10.0, "b" -> 4.0, "c" -> 2.0)),
          Map("a" -> 7.0, "b" -> -2.0, "c" -> -5.0)
        )
        // Without a score for every ranked seat: those ranked below less those ranked above.
        assertEquals(Points.differential(ranks, Map.empty[String, Double]), Map("a" -> 2.0, "b" -> 0.0, "c" -> -2.0))
        assertEquals(
          Points.differential(Map("a" -> Some(1), "b" -> None), Map.empty[String, Double]),
          Map("a" -> 0.0, "b" -> 0.0)
        )
    }

    property("differentials over scores sum to nothing across a match") {
        forAll(Gen.choose(2, 6).flatMap(n => Gen.listOfN(n, Gen.choose(0, 20)))) { scores =>
            val ranks = scores.indices.map(i => i -> Option(1 + scores.count(_ > scores(i)))).toMap
            val diff = Points.differential(ranks, scores.indices.map(i => i -> scores(i).toDouble).toMap)
            math.abs(diff.values.sum) < 1e-9
        }
    }
}
