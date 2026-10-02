package com.vivi.stratego

import java.time.Instant
import munit.FunSuite

/** The endings the engine cannot easily be played into: a side left with nothing it may move. */
class EndingSpec extends FunSuite {

    private val at = Instant.parse("2026-01-01T00:00:00Z")

    /** A match in play — both setups recorded — on a board holding only the pieces named. */
    private def inPlay(placed: (Int, (Side, Rank))*): StrategoMatch = {
        val board = Board(placed.zipWithIndex.foldLeft(Board.empty.cells) {
            case (cells, ((square, (side, rank)), id)) =>
                cells.updated(square, Some(Piece(id, side, rank)))
        })
        StrategoMatch(
          matchId = "m-1",
          seats = List(Seat(Side.Red, "sub-alice", 11L), Seat(Side.Blue, "sub-bob", 22L)),
          board = board,
          turns = List(MoveRecord(11L, Side.Red, at, at), MoveRecord(22L, Side.Blue, at, at)),
          isPublic = false,
          completed = false,
          createdAt = at,
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )
    }

    test("a side with nothing it may move loses, on its turn") {
        val m = inPlay(
          0 -> (Side.Red, Rank.Flag),
          1 -> (Side.Red, Rank.Bomb),
          99 -> (Side.Blue, Rank.Flag),
          50 -> (Side.Blue, Rank.Scout)
        )
        assertEquals(m.ending, Some(Ending.NoMoves))
        assertEquals(m.winner, Some(Side.Blue))
        assert(!m.isDraw)
        assertEquals(StrategoMatch.pending(m), Nil)
    }

    test("when neither side can move, it is a draw") {
        val m = inPlay(0 -> (Side.Red, Rank.Flag), 99 -> (Side.Blue, Rank.Flag))
        assertEquals(m.ending, Some(Ending.NoMoves))
        assertEquals(m.winner, None)
        assert(m.isDraw)
    }

    test("a match is not over while both sides can move and both flags stand") {
        val m = inPlay(0 -> (Side.Red, Rank.Flag), 10 -> (Side.Red, Rank.Miner), 99 -> (Side.Blue, Rank.Flag))
        assertEquals(m.ending, None)
        assertEquals(StrategoMatch.pending(m).map(_.side), List(Side.Red))
    }
}
