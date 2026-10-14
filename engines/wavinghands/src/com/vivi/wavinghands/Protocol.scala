package com.vivi.wavinghands

import upickle.default.{ReadWriter, macroRW}
import com.vivi.engine.ClockView

/** This engine's messages: matchmaker's wire format, stated once in [[com.vivi.engine.Protocol]] and exported here, and
  * the play API between the engine and its play page.
  */
object Protocol {

    export com.vivi.engine.Protocol.{given, *}

    // ---- this engine's play API --------------------------------------------------------

    /** A wizard's orders for the turn, as the play page sends them. Whose they are comes from who signed in.
      *
      * `left` and `right` are gesture codes (F, P, S, W, D, C, stab, or - for nothing). `casting` picks among the ways
      * to cast what they complete, by the key the state offered it under; left out, the biggest is cast. The maps are
      * by slot — left, right or both — for each spell cast: `targets` its target, `hands` the hand a paralysis freezes,
      * and `summons` what a summoned monster attacks on the turn it appears. `stab` is whom a stab is aimed at, and
      * `monsters` what each of the wizard's monsters attacks, by monster number. A target is `wizard:<participantId>`,
      * `monster:<number>` or `nobody`.
      */
    case class MoveRequest(
        left: String,
        right: String,
        casting: Option[String] = None,
        targets: Map[String, String] = Map.empty,
        hands: Map[String, String] = Map.empty,
        summons: Map[String, String] = Map.empty,
        stab: Option[String] = None,
        monsters: Map[String, String] = Map.empty
    )

    /** One wizard as anyone watching may see them. `left` and `right` are their gestures turn by turn — public, since
      * they are made in plain sight; what is hidden is the turn being played, of which a watcher learns only
      * `hasOrdered`.
      */
    case class WizardView(
        participantId: Long,
        name: String,
        damage: Int,
        isDead: Boolean,
        isSurrendered: Boolean,
        hasOrdered: Boolean,
        effects: List[String],
        left: List[String],
        right: List[String]
    )

    case class MonsterView(id: Int, kind: String, controller: Long, damage: Int, strength: Int, isDead: Boolean)

    case class ReportView(turn: Int, events: List[String])

    /** One spell of a casting, as the orders form offers it: the slot it is cast from, and how its target is chosen. */
    case class CastView(
        slot: String,
        spell: String,
        name: String,
        defaultTarget: String,
        isParalysis: Boolean,
        isSummons: Boolean
    )

    case class CastingView(key: String, spells: List[CastView])

    case class TargetView(code: String, label: String)

    /** What the viewer may order this turn. `left` and `right` are the gestures each hand may make — one, when it is
      * forced — and `castings` what each pair of them would cast, keyed `<left>,<right>`. `notes` say why a hand is
      * held or limited.
      */
    case class YourTurn(
        isOrdered: Boolean,
        left: List[String],
        right: List[String],
        notes: List[String],
        castings: Map[String, List[CastingView]],
        targets: List[TargetView],
        monsters: List[Int],
        defaultEnemy: String
    )

    case class SpellView(name: String, series: List[String], about: String)

    /** The state the play page renders. `you` is the viewer's participant id, as a string as every engine names the
      * viewer's seat, and absent on the public board; `yourTurn` is present while the viewer is still fighting.
      */
    case class StateResponse(
        matchId: String,
        turn: Int,
        you: Option[String],
        isCompleted: Boolean,
        isDrawn: Boolean,
        winners: List[Long],
        waitingFor: List[Long],
        wizards: List[WizardView],
        monsters: List[MonsterView],
        reports: List[ReportView],
        yourTurn: Option[YourTurn],
        spells: List[SpellView],
        clock: Option[ClockView] = None
    )

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[WizardView] = macroRW
    given ReadWriter[MonsterView] = macroRW
    given ReadWriter[ReportView] = macroRW
    given ReadWriter[CastView] = macroRW
    given ReadWriter[CastingView] = macroRW
    given ReadWriter[TargetView] = macroRW
    given ReadWriter[YourTurn] = macroRW
    given ReadWriter[SpellView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
