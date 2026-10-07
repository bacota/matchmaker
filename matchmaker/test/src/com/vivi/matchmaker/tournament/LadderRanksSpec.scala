package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** How a ladder's ranks move with each match. */
class LadderRanksSpec extends ScalaCheckSuite {

    test("of two, the winner goes up one and the loser down one; a draw moves nobody") {
        assertEquals(LadderRanks.moves(Map("a" -> Some(1), "b" -> Some(2))), Map("a" -> 1, "b" -> -1))
        assertEquals(LadderRanks.moves(Map("a" -> Some(1), "b" -> Some(1))), Map("a" -> 0, "b" -> 0))
    }

    test("of more, the lone winner goes up, everybody last goes down, and the middle stays") {
        assertEquals(
          LadderRanks.moves(Map("a" -> Some(1), "b" -> Some(2), "c" -> Some(3))),
          Map("a" -> 1, "b" -> 0, "c" -> -1)
        )
        // Two tied first have not won; the two tied last have both lost.
        assertEquals(
          LadderRanks.moves(Map("a" -> Some(1), "b" -> Some(1), "c" -> Some(3), "d" -> Some(3))),
          Map("a" -> 0, "b" -> 0, "c" -> -1, "d" -> -1)
        )
    }

    test("a seat with no rank does not move, and an unranked match moves nobody") {
        assertEquals(LadderRanks.moves(Map("a" -> Some(1), "b" -> None)), Map("a" -> 0, "b" -> 0))
        assertEquals(LadderRanks.moves(Map("a" -> None, "b" -> None)), Map("a" -> 0, "b" -> 0))
    }

    test("a rank is the sum of a member's moves, from 0, and a member who played nothing is at 0") {
        val played = List(Map("a" -> Some(1), "b" -> Some(2)), Map("a" -> Some(1), "b" -> Some(2)))
        assertEquals(LadderRanks.of(List("a", "b", "c"), played), Map("a" -> 2, "b" -> -2, "c" -> 0))
    }

    property("every move is one step at most, and nobody rises unless somebody falls") {
        val seats = Gen.choose(2, 5).flatMap(n => Gen.listOfN(n, Gen.option(Gen.choose(1, 4))))
        forAll(seats) { ranks =>
            val moves = LadderRanks.moves(ranks.zipWithIndex.map((r, i) => i -> r).toMap)
            moves.values.forall(m => m >= -1 && m <= 1) &&
            (moves.values.sum <= 0 || moves.values.exists(_ < 0))
        }
    }
}
