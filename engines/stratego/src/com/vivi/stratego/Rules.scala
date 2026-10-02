package com.vivi.stratego

/** One piece's move, as the two-square rule remembers it. */
case class Step(pieceId: Int, from: Int, to: Int)

/** The rules that need more than the position: what a side's history forbids it.
  *
  * Only the two-square rule is enforced. The more-squares rule — that a player may not chase one piece endlessly with
  * another across many squares — needs a judgement of what a chase is that the ISF rules state only by example, and is
  * left out; the move cap ends a match that would otherwise never finish.
  */
object Rules {

    /** How many consecutive moves a piece may make between the same two squares. */
    val shuttleLimit = 3

    /** Whether `proposed` would be one move too many between the same two squares.
      *
      * `history` is the moving side's own moves, oldest first. A fourth consecutive move of the same piece between the
      * same two squares is refused — the player has to do something else first, which is what stops a lone piece
      * fleeing back and forth for ever.
      */
    def shuttles(history: List[Step], proposed: Step): Boolean = {
        val recent = history.takeRight(shuttleLimit)
        val squares = Set(proposed.from, proposed.to)
        recent.sizeIs == shuttleLimit &&
        recent.forall(s => s.pieceId == proposed.pieceId && Set(s.from, s.to) == squares)
    }

    /** Every move `side` may make now: what the board allows, less what the two-square rule forbids. */
    def legalMoves(board: Board, side: Side, history: List[Step]): List[(Int, Int)] =
        board.moves(side).filterNot { (from, to) =>
            board(from).exists(p => shuttles(history, Step(p.id, from, to)))
        }
}
