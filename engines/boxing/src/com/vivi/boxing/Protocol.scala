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

    /** A new fighter as the build page submits it. Whose it is comes from who signed in, not from the body. */
    case class BuildRequest(
        name: String,
        description: String,
        strength: Int,
        speed: Int,
        agility: Int,
        workrate: Int,
        chin: Int
    )

    /** A fighter just built and registered: the character id matchmaker gave it, and what it was built as. */
    case class BuiltFighter(characterId: Long, name: String, fighter: FighterView)

    /** One of the signed-in player's fighters, as the fighters page lists them. `fighter` is absent for a character
      * whose state is not a fighter this engine could have built.
      */
    case class MyFighter(characterId: Long, name: String, description: String, fighter: Option[FighterView])

    /** A fighter's new name and description, as the fighters page submits them. */
    case class EditRequest(name: String, description: String)

    /** A fighter edited. */
    case class Edited(characterId: Long, name: String, description: String)

    /** Who a fighter is given to, by matchmaker nickname, as the fighters page submits it. */
    case class GiveRequest(toNickname: String)

    /** A fighter given away. */
    case class Given(characterId: Long, toNickname: String)

    /** The rules a build is checked against, so the page can say them rather than restate them. */
    case class BuildRules(budget: Int, min: Int, max: Int)

    /** A fighter's characteristics, as a corner shows them. */
    case class FighterView(strength: Int, speed: Int, agility: Int, workrate: Int, chin: Int)

    /** The state the play page renders, and what a scripted client polls.
      *
      * `you` is the viewer's corner, absent on the public board. `yourPlan` is the viewer's own plan for the round
      * being fought, which they may see; the other corner's plan is in nobody's answer, then or after the round
      * resolves, when `rounds` carries only the viewer's own.
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

    /** One corner as a viewer may see it. `fighter` is the fighter's characteristics, and only in the viewer's own
      * corner: nobody sees an opponent's numbers, on the public board least of all, only its `impression`. `planned`
      * says only that the corner has planned the current round, not how.
      */
    case class CornerView(
        side: String,
        cognitoId: String,
        participantId: Long,
        characterId: Long,
        fighter: Option[FighterView],
        planned: Boolean,
        points: Int,
        // What can be seen of a fighter whose characteristics are not the viewer's to know: see
        // `Fighter.impression`. Empty for the viewer's own corner, whose `fighter` says it all.
        impression: List[String] = Nil,
        // Who is in the corner, by matchmaker nickname; absent for a bout created before nicknames were kept.
        nickname: Option[String] = None,
        // The fighter's name, the matchmaker character's; absent for a bout created before it was kept.
        fighterName: Option[String] = None
    )

    /** A corner's totals for a round: what it spent plus what its characteristics add. Not effective chin, which would
      * give the opponent's chin away.
      */
    case class Numbers(offense: Int, defense: Int, power: Int)

    /** A resolved round: both corners' totals, the viewer's own plan, and how it was scored.
      *
      * The totals are what happened in the ring, and everyone is shown them. A plan is not: a corner's totals less its
      * plan are its characteristics, so each corner sees only the plan it made (`red` / `blue`), the other corner never
      * sees it, and the public board sees neither.
      */
    case class RoundView(
        number: Int,
        red: Option[PlanRequest],
        blue: Option[PlanRequest],
        redNumbers: Numbers,
        blueNumbers: Numbers,
        decision: String,
        winner: Option[String],
        redPoints: Option[Int],
        bluePoints: Option[Int]
    )

    given ReadWriter[PlanRequest] = macroRW
    given ReadWriter[FighterView] = macroRW
    given ReadWriter[BuildRequest] = macroRW
    given ReadWriter[BuiltFighter] = macroRW
    given ReadWriter[BuildRules] = macroRW
    given ReadWriter[MyFighter] = macroRW
    given ReadWriter[EditRequest] = macroRW
    given ReadWriter[Edited] = macroRW
    given ReadWriter[GiveRequest] = macroRW
    given ReadWriter[Given] = macroRW
    given ReadWriter[CornerView] = macroRW
    given ReadWriter[Numbers] = macroRW
    given ReadWriter[RoundView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
