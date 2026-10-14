package com.vivi.matchmaker.model

import munit.FunSuite

/** What one match adds to one seat's win-loss-draw record. */
class MatchRecordSpec extends FunSuite {

    test("first alone is a win, below first a loss, and a forfeit is counted as one too") {
        assertEquals(MatchRecord.of(rank = 1, ranks = Seq(1, 2), forfeit = false), MatchRecord(wins = 1))
        assertEquals(MatchRecord.of(rank = 2, ranks = Seq(1, 2), forfeit = false), MatchRecord(losses = 1))
        assertEquals(
          MatchRecord.of(rank = 1, ranks = Seq(1, 2), forfeit = true),
          MatchRecord(wins = 1, forfeitWins = 1)
        )
        assertEquals(
          MatchRecord.of(rank = 2, ranks = Seq(1, 2), forfeit = true),
          MatchRecord(losses = 1, forfeitLosses = 1)
        )
    }

    test("sharing first is a draw, and with more seats everybody under first lost") {
        assertEquals(MatchRecord.of(rank = 1, ranks = Seq(1, 1), forfeit = false), MatchRecord(draws = 1))
        assertEquals(MatchRecord.of(rank = 1, ranks = Seq(1, 1, 3), forfeit = false), MatchRecord(draws = 1))
        assertEquals(MatchRecord.of(rank = 3, ranks = Seq(1, 1, 3), forfeit = false), MatchRecord(losses = 1))
    }

    test("a record taken back is the one given, the other way") {
        val earned = MatchRecord(wins = 3, losses = 1, draws = 2, forfeitWins = 1)
        assertEquals(earned + -earned, MatchRecord())
    }
}
