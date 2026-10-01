package com.vivi.boxing

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** The wire format between matchmaker and a game engine, restated from the engine's side.
  *
  * These are deliberately *not* matchmaker's classes, even though the repository happens to hold both. A game engine is
  * a separate system that matchmaker reaches over HTTP, and depending on its classes would hide exactly the failure
  * this engine exists to catch: a field renamed on one side and not the other would keep compiling. Restating them
  * means the two agree only if the JSON really matches, which is what `ProtocolSpec` checks against matchmaker's own
  * definitions.
  *
  * The shapes come from `matchmaker/src/com/vivi/matchmaker/engine/GameEngineClient.scala` (the calls in) and
  * `shared/src/com/vivi/matchmaker/api/Json.scala` (the callbacks out).
  */
object Protocol {

    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)

    // ---- what matchmaker sends the engine -------------------------------------------------

    case class EnginePlayer(
        cognitoId: String,
        participantId: Long,
        role: Option[String],
        characterId: Option[Long],
        characterState: Option[String]
    )

    case class CreateGameRequest(
        matchId: String,
        gameName: String,
        isPublic: Boolean,
        parameters: Map[String, String],
        settings: String,
        timeLimitSeconds: Option[Long],
        players: List[EnginePlayer],
        moveCallbackUrl: Option[String],
        resultsCallbackUrl: Option[String]
    )

    case class CreateGameResponse(statusUrl: String, playUrl: String, publicUrl: Option[String])

    case class EngineParticipantStatus(
        participantId: Long,
        pending: Boolean,
        completed: Boolean,
        prevMoveAt: Option[Instant]
    )

    /** One move, as reported to a status call. `startedAt` is when that player's clock started for it — this engine
      * knows it exactly (it is the move before) so it says so rather than leaving matchmaker to infer it.
      */
    case class EngineTurn(participantId: Long, takenAt: Instant, startedAt: Option[Instant] = None)

    /** `turns` are the moves made after the `since` the status call asked from, oldest first. */
    case class GameStatusResponse(
        completed: Boolean,
        participants: List[EngineParticipantStatus],
        turns: List[EngineTurn] = Nil,
        /** The number of moves this answer reflects: the same numbering as a move callback's `state.sequence`, so that
          * matchmaker can tell an answer a callback has since overtaken.
          */
        sequence: Option[Long] = None
    )

    // ---- what the engine calls back with --------------------------------------------------

    /** Step 2. `next` is who may move now; empty on the move that ends the game.
      *
      * `takenAt` is when this move was made, which is when the clock starts for anyone in `next`; `startedAt` is when
      * the mover's own clock started for it. Matchmaker takes both as stated.
      *
      * A move here is one corner's plan for one round. The first plan of a round names nobody: the other corner has
      * been pending since the round began and stays so by not being named. The plan that resolves a round names both
      * corners, the mover included, because that is a new round starting — both clocks begin again at `takenAt` —
      * unless the round ended the bout, when it names nobody and the results follow.
      *
      * `startedAt` is when the round began, for both corners, however late either of them plans.
      */
    case class MoveNotification(
        participantId: Long,
        next: List[Long] = Nil,
        takenAt: Instant,
        startedAt: Instant,
        state: Option[MoveState] = None
    )

    /** Where the match stands after the move: `sequence` is the move's number, one more than the move before it, and
      * `pending` is every seat that is to move now with when its clock started — a seat left out is not pending.
      *
      * Callbacks are sent after each move commits, from whichever request made it, so two can reach matchmaker in the
      * opposite order to the moves. The number is how matchmaker recognises the late one, and the whole pending list is
      * what lets it ignore the late one safely. With this present, matchmaker ignores `next`, which is still sent for a
      * matchmaker that predates it.
      */
    case class MoveState(sequence: Long, pending: List[PendingSeat])

    case class PendingSeat(participantId: Long, since: Instant)

    /** Step 3. `scores` is an open map — matchmaker stores whatever the game puts there. */
    case class ResultEntry(participantId: Long, rank: Int, scores: Map[String, ujson.Value], isWinner: Boolean)

    /** `turns` is every turn of the match, in the same shape as a status answer's. Matchmaker records them in the
      * transaction that completes the match, so a turn whose move callback was lost is not lost with it — once a match
      * is over, matchmaker does not ask the engine again.
      */
    case class MatchResults(results: List[ResultEntry], turns: Option[List[EngineTurn]] = None)

    /** What the engine writes to matchmaker's `PUT /characters/{id}/state` once a fighter is built. `state` is the
      * fighter as JSON, carried as a string: matchmaker stores a character's state without reading it.
      */
    case class UpdateStateRequest(state: String)

    given ReadWriter[EnginePlayer] = macroRW
    given ReadWriter[CreateGameRequest] = macroRW
    given ReadWriter[CreateGameResponse] = macroRW
    given ReadWriter[EngineParticipantStatus] = macroRW
    given ReadWriter[EngineTurn] = macroRW
    given ReadWriter[GameStatusResponse] = macroRW
    given ReadWriter[PendingSeat] = macroRW
    given ReadWriter[MoveState] = macroRW
    given ReadWriter[MoveNotification] = macroRW
    given ReadWriter[ResultEntry] = macroRW
    given ReadWriter[MatchResults] = macroRW
    given ReadWriter[UpdateStateRequest] = macroRW

    // ---- the engine's own play API --------------------------------------------------------

    /** A round plan as the play page submits it. Whose it is comes from who signed in, not from the body. */
    case class PlanRequest(offense: Int, defense: Int, power: Int)

    /** A fighter as the build form submits it. */
    case class BuildRequest(strength: Int, speed: Int, agility: Int, workrate: Int, chin: Int)

    /** The limits the build form enforces, sent with the state so the page and the engine cannot disagree. */
    case class BuildRules(budget: Int, min: Int, max: Int)

    /** The state the play page renders, and what a scripted client polls.
      *
      * `you` is the viewer's corner, absent on the public board. `yourPlan` is the viewer's own plan for the round
      * being fought, which they may see; the other corner's plan for that round is in nobody's answer until the round
      * resolves, and then it is in `rounds` for everyone.
      *
      * `waitingFor` is who has yet to plan the current round, by corner — plural, and empty once the bout is over.
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
        /** "knockout" or "points", once the bout is over. */
        method: Option[String],
        buildRules: BuildRules,
        corners: List[CornerView],
        rounds: List[RoundView]
    )

    /** One corner as a viewer may see it. `fighter` is the fighter's characteristics once built — public, as a
      * fighter's record is; `planned` says only that the corner has planned the current round, not how.
      */
    case class CornerView(
        side: String,
        cognitoId: String,
        participantId: Long,
        characterId: Long,
        fighter: Option[BuildRequest],
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
    given ReadWriter[BuildRequest] = macroRW
    given ReadWriter[BuildRules] = macroRW
    given ReadWriter[CornerView] = macroRW
    given ReadWriter[Numbers] = macroRW
    given ReadWriter[RoundView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
