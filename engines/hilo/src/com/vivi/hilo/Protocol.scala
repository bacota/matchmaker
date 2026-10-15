package com.vivi.hilo

import upickle.default.{ReadWriter, macroRW}
import com.vivi.engine.ClockView

/** This engine's messages: matchmaker's wire format, stated once in [[com.vivi.engine.Protocol]] and exported here, and
  * the play API between the engine and its play page.
  */
object Protocol {

    export com.vivi.engine.Protocol.{given, *}

    // ---- this engine's play API --------------------------------------------------------

    /** A pick, as the play page sends it. Whose it is comes from who signed in. */
    case class MoveRequest(number: Int)

    /** One player as anyone watching may see them: who they are, and whether they have picked in the round being played
      * — never what, until the round is over.
      */
    case class PlayerView(participantId: Long, name: String, hasPicked: Boolean)

    case class PickView(participantId: Long, number: Int)

    /** A round every player picked in, with every pick and who won it, if anybody did. */
    case class RoundView(round: Int, picks: List[PickView], winner: Option[Long])

    /** The state the play page renders. `you` is the viewer's participant id, as a string as every engine names the
      * viewer's seat, and absent on the public board; `yourPick` is what they picked in the round being played.
      * `highest` is the highest number anyone may pick. `rounds` are the rounds played, the latest first.
      */
    case class StateResponse(
        matchId: String,
        round: Int,
        highest: Int,
        you: Option[String],
        yourPick: Option[Int],
        isCompleted: Boolean,
        winners: List[Long],
        waitingFor: List[Long],
        players: List[PlayerView],
        rounds: List[RoundView],
        clock: Option[ClockView] = None
    )

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[PlayerView] = macroRW
    given ReadWriter[PickView] = macroRW
    given ReadWriter[RoundView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
