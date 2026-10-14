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
          isCompleted = false,
          createdAt = at,
          moveCallbackUrl = None,
          resultsCallbackUrl = None,
          maxMoves = StrategoMatch.defaultMaxMoves
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

    /** A piece move by `side` that fought a battle — where it went does not matter to the score, only who took what. */
    private def fight(side: Side, battle: Battle): MoveRecord =
        MoveRecord(
          if (side == Side.Red) 11L else 22L,
          side,
          at,
          at,
          step = Some(Step(0, 0, 1)),
          battle = Some(battle)
        )

    /** Neither side can move — only the flags are left — after `battles`. Three of them leave Blue to move. */
    private def stuckAfter(battles: List[MoveRecord], tiesAllowed: Boolean): StrategoMatch =
        inPlay(0 -> (Side.Red, Rank.Flag), 99 -> (Side.Blue, Rank.Flag))
            .copy(isTiesAllowed = tiesAllowed)
            .pipe(m => m.copy(turns = m.turns ++ battles))

    extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

    private val battles = List(
      fight(Side.Blue, Battle(Rank.Captain, Rank.Marshal, Result.DefenderWins)),
      fight(Side.Red, Battle(Rank.Miner, Rank.Bomb, Result.AttackerWins)),
      fight(Side.Blue, Battle(Rank.Major, Rank.Scout, Result.AttackerWins))
    )

    test("captures are worth each piece's strength, and a bomb or the spy is worth 1") {
        val m = stuckAfter(battles, tiesAllowed = true)
        // Blue attacked with a Captain and lost it to Red's Marshal: that is Red taking a Captain (6).
        // Red's Miner took a bomb (1); Blue's Major took a Scout (2).
        assertEquals(m.taken(Side.Red), List(Rank.Captain, Rank.Bomb))
        assertEquals(m.capturePoints(Side.Red), 7)
        assertEquals(m.capturePoints(Side.Blue), 2)
        assertEquals(StrategoMatch.pointsFor(Rank.Spy), 1)
        assertEquals(StrategoMatch.pointsFor(Rank.Marshal), 10)
        // A match that allows ties is drawn when neither side can move, whatever the captures.
        assert(m.isDraw)
        assertEquals(StrategoMatch.scores(m, m.seats.head)("score").num, 7.0)
    }

    test("when ties are not allowed, a match neither side can move in goes to the side ahead on captures") {
        val m = stuckAfter(battles, tiesAllowed = false)
        assertEquals(m.ending, Some(Ending.NoMoves))
        assertEquals(m.winner, Some(Side.Red))
        assert(!m.isDraw)
        assert(m.decidedOnPoints)
        assertEquals(
          StrategoMatch.summary(m),
          Some("Neither side could move. <strong>Red</strong> wins on captures, 7–2.")
        )
    }

    test("when ties are not allowed, the move cap goes to the side ahead on captures, and level captures still draw") {
        // Both sides can still move, so it is the cap that ends it.
        val capped = inPlay(
          0 -> (Side.Red, Rank.Flag),
          10 -> (Side.Red, Rank.Miner),
          99 -> (Side.Blue, Rank.Flag),
          50 -> (Side.Blue, Rank.Scout)
        )
            .copy(isTiesAllowed = false, maxMoves = 1)
        val ahead = capped.copy(turns = capped.turns :+ battles(2))
        assertEquals(ahead.ending, Some(Ending.MoveCap))
        assertEquals(ahead.winner, Some(Side.Blue))

        val level =
            capped.copy(turns = capped.turns :+ fight(Side.Red, Battle(Rank.Miner, Rank.Miner, Result.BothLost)))
        assertEquals(level.capturePoints(Side.Red), level.capturePoints(Side.Blue))
        assert(level.isDraw)
    }

    test("a side that cannot move still simply loses when ties are not allowed: points decide only a draw") {
        val m = inPlay(
          0 -> (Side.Red, Rank.Flag),
          1 -> (Side.Red, Rank.Bomb),
          99 -> (Side.Blue, Rank.Flag),
          50 -> (Side.Blue, Rank.Scout)
        ).copy(isTiesAllowed = false)
        assertEquals(m.winner, Some(Side.Blue))
        assert(!m.decidedOnPoints)
    }
}
