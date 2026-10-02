package com.vivi.boxing

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

    /** A round plan as the play page submits it. Whose it is comes from who signed in, not from the body. */
    case class PlanRequest(offense: Int, defense: Int, power: Int)

    /** A fighter's characteristics, as a corner shows them. */
    case class FighterView(strength: Int, speed: Int, agility: Int, workrate: Int, chin: Int)

    /** The state the play page renders, and what a scripted client polls.
      *
      * `you` is the viewer's corner, absent on the public board. `yourPlan` is the viewer's own plan for the round
      * being fought, which they may see; the other corner's plan for that round is in nobody's answer until the round
      * resolves, and then it is in `rounds` for everyone.
      *
      * `waitingFor` is who has yet to plan the current round, by corner — plural, and empty once the bout is over.
      *
      * `clock` is a live bout's turn clock, and absent from any other.
      */
    case class StateResponse(
        matchId: String,
        scheduledRounds: Int,
        round: Int,
        waitingFor: List[String],
        you: Option[String],
        yourPlan: Option[PlanRequest],
        completed: Boolean,
        winner: Option[String],
        draw: Boolean,
        /** "knockout", "points" or "forfeit", once the bout is over. */
        method: Option[String],
        corners: List[CornerView],
        rounds: List[RoundView],
        clock: Option[ClockView] = None
    )

    /** One corner as a viewer may see it. `fighter` is the fighter's characteristics once built — public, as a
      * fighter's record is; `planned` says only that the corner has planned the current round, not how.
      */
    case class CornerView(
        side: String,
        cognitoId: String,
        participantId: Long,
        characterId: Long,
        fighter: Option[FighterView],
        planned: Boolean,
        points: Int
    )

    case class Numbers(offense: Int, defense: Int, power: Int, effectiveChin: Int)

    /** A resolved round: both plans, both fighters' numbers, and how it was scored. */
    case class RoundView(
        number: Int,
        red: PlanRequest,
        blue: PlanRequest,
        redNumbers: Numbers,
        blueNumbers: Numbers,
        decision: String,
        winner: Option[String],
        redPoints: Option[Int],
        bluePoints: Option[Int]
    )

    given ReadWriter[PlanRequest] = macroRW
    given ReadWriter[FighterView] = macroRW
    given ReadWriter[CornerView] = macroRW
    given ReadWriter[Numbers] = macroRW
    given ReadWriter[RoundView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
