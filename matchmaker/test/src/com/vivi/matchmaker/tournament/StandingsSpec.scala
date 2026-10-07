package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** A pool's standings: points, then the tiebreaker, then seed. */
class StandingsSpec extends ScalaCheckSuite {

    private def played(ranks: (String, Int)*): PlayedMatch[String] = PlayedMatch(
      ranks.map((k, r) => k -> Option(r)).toMap
    )

    private val seeds = Map("a" -> 1, "b" -> 2, "c" -> 3)

    test("standings are by points: a win three, a draw one") {
        val standings = Standings.of(
          seeds,
          List(played("a" -> 2, "b" -> 1), played("a" -> 1, "c" -> 1), played("b" -> 1, "c" -> 2)),
          byScore = true
        )
        assertEquals(standings.map(s => s.member -> s.points), List("b" -> 6, "a" -> 1, "c" -> 1))
    }

    test("SCORE separates players level on points by differential, and seed separates what it cannot") {
        val scored = List(
          PlayedMatch(Map("a" -> Some(1), "b" -> Some(2)), Map("a" -> 3.0, "b" -> 1.0)),
          PlayedMatch(Map("b" -> Some(1), "c" -> Some(2)), Map("b" -> 2.0, "c" -> 1.0)),
          PlayedMatch(Map("c" -> Some(1), "a" -> Some(2)), Map("c" -> 5.0, "a" -> 0.0))
        )
        // Three points each: a +2 -5, b -2 +1, c -1 +5.
        assertEquals(Standings.of(seeds, scored, byScore = true).map(_.member), List("c", "b", "a"))
        // Under REMATCH, with no rematch played yet, seed decides.
        assertEquals(Standings.of(seeds, scored, byScore = false).map(_.member), List("a", "b", "c"))
    }

    test("REMATCH asks for a tie-break only where a tie straddles the cut, and not once it is broken") {
        val level = List(played("a" -> 1, "b" -> 1), played("a" -> 1, "c" -> 2), played("b" -> 1, "c" -> 2))
        // a and b are level on four points, ahead of c: a tie for first, which matters when one goes through.
        assertEquals(Standings.tiesToBreak(seeds, level, cutoff = 1), List(List("a", "b")))
        // With two going through, both a and b do, and nobody needs separating.
        assertEquals(Standings.tiesToBreak(seeds, level, cutoff = 2), Nil)
        val rematch = List(played("a" -> 2, "b" -> 1))
        assertEquals(Standings.tiesToBreak(seeds, level, cutoff = 1, rematch), Nil)
        assertEquals(Standings.of(seeds, level, byScore = false, rematch).map(_.member), List("b", "a", "c"))
    }

    test("a member who played nothing is still in the standings, with nothing") {
        val standings = Standings.of(seeds, List(played("a" -> 1, "b" -> 2)), byScore = true)
        // c played nothing and is level with b on points, but b's one match was a loss.
        assertEquals(standings.map(s => s.member -> s.points), List("a" -> 3, "c" -> 0, "b" -> 0))
    }

    property("the standings are always every member once, in non-increasing points") {
        val gen = Gen.listOf(for {
            pair <- Gen.oneOf(("a", "b"), ("a", "c"), ("b", "c"))
            rx <- Gen.choose(1, 2)
            ry <- Gen.choose(1, 2)
        } yield played(pair._1 -> rx, pair._2 -> ry))
        forAll(gen) { matches =>
            val standings = Standings.of(seeds, matches, byScore = true)
            standings.map(_.member).sorted == List("a", "b", "c") &&
            standings.map(_.points).zip(standings.map(_.points).drop(1)).forall((x, y) => x >= y)
        }
    }
}
