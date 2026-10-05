package com.vivi.matchmaker.model

import munit.FunSuite

/** How a finished match is marked on its row: the same reading as the win-loss-draw record it was counted in. */
class MatchOutcomeSpec extends FunSuite {

    test("first alone is won, first shared is drawn, below first is lost") {
        assertEquals(MatchOutcome.of(Some(1), Seq(1, 2)), Some(MatchOutcome.Won))
        assertEquals(MatchOutcome.of(Some(2), Seq(1, 2)), Some(MatchOutcome.Lost))
        assertEquals(MatchOutcome.of(Some(1), Seq(1, 1)), Some(MatchOutcome.Drew))
        assertEquals(MatchOutcome.of(Some(3), Seq(1, 1, 3)), Some(MatchOutcome.Lost))
    }

    test("a seat with no result has no outcome") {
        assertEquals(MatchOutcome.of(None, Seq(1)), None)
        assertEquals(MatchOutcome.of(None, Seq.empty), None)
    }

    test("agrees with the record for every placing") {
        for {
            ranks <- Seq(Seq(1, 2), Seq(1, 1), Seq(1, 2, 3), Seq(1, 1, 3), Seq(2, 2))
            rank <- ranks.distinct
        } {
            val record = MatchRecord.of(rank, ranks, forfeit = false)
            val expected =
                if (record.wins == 1) MatchOutcome.Won
                else if (record.draws == 1) MatchOutcome.Drew
                else MatchOutcome.Lost
            assertEquals(MatchOutcome.of(Some(rank), ranks), Some(expected), s"rank $rank of $ranks")
        }
    }
}
