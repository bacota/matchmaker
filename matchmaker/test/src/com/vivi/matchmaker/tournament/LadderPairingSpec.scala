package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** A ladder round's matches. */
class LadderPairingSpec extends ScalaCheckSuite {

    import LadderPairing.Rung

    test("within a rank, the lowest-rated plays the highest, and the top rank goes first") {
        val players = List(
          Rung("a", 1, 1500),
          Rung("b", 1, 1700),
          Rung("c", 1, 1600),
          Rung("d", 1, 1400),
          Rung("e", 0, 1550),
          Rung("f", 0, 1450)
        )
        assertEquals(LadderPairing.matches(players, 2), List(List("d", "b"), List("a", "c"), List("f", "e")))
    }

    test("a rank that does not divide borrows the lowest-rated of the rank below") {
        val players = List(Rung("a", 2, 1500), Rung("b", 1, 1700), Rung("c", 1, 1300), Rung("d", 1, 1600))
        // a borrows c, the lowest of rank 1; b and d play.
        assertEquals(LadderPairing.matches(players, 2), List(List("c", "a"), List("d", "b")))
    }

    property("every match is full, nobody plays twice, and at most a match's worth sit out") {
        val gen = Gen.listOf(Gen.zip(Gen.choose(-3, 3), Gen.choose(1200, 1800)))
        forAll(gen, Gen.choose(2, 4)) { (rungs, seats) =>
            val players = rungs.zipWithIndex.map { case ((rank, rating), i) => Rung(i, rank, rating) }
            val matches = LadderPairing.matches(players, seats)
            val playing = matches.flatten
            matches.forall(_.sizeIs == seats) &&
            playing.distinct.size == playing.size &&
            players.size - playing.size < seats
        }
    }
}
