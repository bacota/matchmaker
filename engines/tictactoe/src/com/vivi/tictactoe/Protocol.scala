package com.vivi.tictactoe

import upickle.default.{ReadWriter, macroRW}

/** This engine's messages: matchmaker's wire format, which is the same for every engine and is stated once in
  * [[com.vivi.engine.Protocol]], and the play API between the engine and its own play page, which is this game's.
  *
  * The former is exported here so that `Protocol.X` names either, and `import Protocol.given` brings both sets of
  * codecs.
  */
object Protocol {

    export com.vivi.engine.Protocol.{given, *}

    // ---- this engine's play API --------------------------------------------------------

    /** A move as the board page submits it: which cell to mark. Whose move it is comes from the seat token in the url,
      * not from the body — a player may not name someone else's seat.
      */
    case class MoveRequest(cell: Int)

    /** The state the board page renders, and what a scripted client polls.
      *
      * `you` is the mark belonging to the seat that asked; absent on the public view, which belongs to nobody.
      */
    case class StateResponse(
        matchId: String,
        board: String,
        turn: Option[String],
        you: Option[String],
        completed: Boolean,
        winner: Option[String],
        draw: Boolean,
        winningLine: Option[Seq[Int]],
        players: List[SeatView]
    )

    case class SeatView(mark: String, cognitoId: String, participantId: Long, moves: Int)

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[SeatView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
