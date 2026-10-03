package com.vivi.stratego

import upickle.default.{ReadWriter, macroRW}
import com.vivi.engine.ClockView

/** This engine's messages: matchmaker's wire format, which is the same for every engine and is stated once in
  * [[com.vivi.engine.Protocol]], and the play API between the engine and its own play page, which is this game's.
  *
  * The former is exported here so that `Protocol.X` names either, and `import Protocol.given` brings both sets of
  * codecs.
  */
object Protocol {

    export com.vivi.engine.Protocol.{given, *}

    // ---- this engine's play API --------------------------------------------------------

    /** What the play page submits, which is one of three things: a deployment, `{"setup":[...40 ranks]}`, in the order
      * of the side's home squares, ascending; a move, `{"from":n,"to":n}`; or giving the match up, `{"concede":true}`.
      * Whose it is comes from the caller's token, not from the body — a player may not name someone else's seat.
      */
    case class MoveRequest(
        setup: Option[List[String]] = None,
        from: Option[Int] = None,
        to: Option[Int] = None,
        concede: Option[Boolean] = None
    )

    /** The state the play page renders, and what a scripted client polls.
      *
      * `phase` is `setup`, `play` or `over`. `you` is the side belonging to the seat that asked; absent on the public
      * board, which belongs to nobody. `pieces` is every piece the viewer may know is there — and, of each, the rank
      * only where the viewer may know it: their own pieces, revealed ones, and every piece once the match is over.
      * During setup an opponent's army is not on the board at all; `deployed` says who has put theirs down.
      *
      * `legalMoves` is the viewer's moves as `[from, to]` pairs, and only when it is their turn. `lost` is what each
      * side has lost, every piece of which was revealed by the battle that took it.
      */
    case class StateResponse(
        matchId: String,
        phase: String,
        you: Option[String],
        deployed: List[String],
        turn: Option[String],
        pieces: List[PieceView],
        legalMoves: List[List[Int]],
        lastMove: Option[LastMove],
        lost: List[LostView],
        completed: Boolean,
        winner: Option[String],
        draw: Boolean,
        ending: Option[String],
        moveCount: Int,
        maxMoves: Int,
        players: List[SeatView],
        clock: Option[ClockView] = None
    )

    case class PieceView(square: Int, side: String, rank: Option[String], revealed: Boolean, moved: Boolean)

    case class BattleView(attacker: String, defender: String, result: String)

    case class LastMove(side: String, from: Int, to: Int, battle: Option[BattleView])

    case class LostView(side: String, ranks: List[String])

    case class SeatView(
        side: String,
        cognitoId: String,
        participantId: Long,
        moves: Int,
        captured: Int,
        nickname: Option[String] = None
    )

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[PieceView] = macroRW
    given ReadWriter[BattleView] = macroRW
    given ReadWriter[LastMove] = macroRW
    given ReadWriter[LostView] = macroRW
    given ReadWriter[SeatView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
