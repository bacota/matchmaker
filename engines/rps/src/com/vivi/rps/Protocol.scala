package com.vivi.rps

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

    /** A move as the play page submits it: what to throw, by name ("rock") or initial ("r"). Whose move it is comes
      * from who signed in, not from the body — a player may not name someone else's seat.
      */
    case class MoveRequest(shape: String)

    /** The state the play page renders, and what a scripted client polls.
      *
      * `you` is the side belonging to the seat that asked, and `yourThrow` what that seat has already thrown; both are
      * absent on the public view, which belongs to nobody.
      *
      * What is deliberately *not* here is the other player's throw, until the match is over. A seat view carries
      * `isThrown` — whether that seat has moved, which both players and any watcher may know — and `shape` only once
      * there is nothing left to decide. Hiding it in the page rather than in the answer would be no hiding at all: the
      * page is served to the player, and the player can read the response.
      *
      * `waitingFor` is who has yet to throw, by side, which is this game's answer to "whose turn is it" — plural, and
      * empty once the match is over.
      *
      * `clock` is a live match's turn clock, and absent from any other.
      *
      * `tiedRounds` is what was thrown in each round that tied before this one, in a match that does not allow ties — a
      * tie is the same shape twice, so one shape each. Both throws of a tied round are already known to both players,
      * so there is nothing here to hide. Empty in any other match.
      */
    case class StateResponse(
        matchId: String,
        waitingFor: List[String],
        you: Option[String],
        yourThrow: Option[String],
        isCompleted: Boolean,
        winner: Option[String],
        isDrawn: Boolean,
        players: List[SeatView],
        clock: Option[ClockView] = None,
        tiedRounds: List[String] = Nil
    )

    /** One seat as a viewer may see it. `shape` is `None` until the match is over, whoever is asking — including of the
      * viewer's own seat, which is what `yourThrow` is for. `nickname` is what the page shows the seat as; a match
      * created before matchmaker sent nicknames has none.
      */
    case class SeatView(
        side: String,
        participantId: Long,
        isThrown: Boolean,
        shape: Option[String],
        nickname: Option[String] = None
    )

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[SeatView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
