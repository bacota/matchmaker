package com.vivi.matchmaker.tournament

import munit.FunSuite

/** Where everybody finished. */
class FinalRanksSpec extends FunSuite {

    private def line(k: String, seed: Int) = Standing(k, seed, 0, 0.0)

    test("a round robin ranks by its one pool's standings") {
        assertEquals(
          FinalRanks.roundRobin(List(line("c", 3), line("a", 1), line("b", 2))),
          Map("c" -> 1, "a" -> 2, "b" -> 3)
        )
    }

    test("single elimination: the final first, then the consolation pool, then by the round reached, ties shared") {
        val ranks = FinalRanks.singleElimination(
          finalPool = List(line("a", 1), line("b", 2)),
          consolation = List(line("d", 4), line("c", 3)),
          reached = Map("a" -> 3, "b" -> 3, "c" -> 3, "d" -> 3, "e" -> 2, "f" -> 2, "g" -> 1, "h" -> 1)
        )
        assertEquals(ranks, Map("a" -> 1, "b" -> 2, "d" -> 3, "c" -> 4, "e" -> 5, "f" -> 5, "g" -> 7, "h" -> 7))
    }
}
