package com.vivi.rps

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
        turns: List[EngineTurn] = Nil
    )

    // ---- what the engine calls back with --------------------------------------------------

    /** Step 2. `next` is who may move now; empty on the move that ends the game.
      *
      * `takenAt` is when this move was made, which is when the clock starts for anyone in `next`; `startedAt` is when
      * the mover's own clock started for it, so the pair is what the move cost them. Matchmaker takes both as stated
      * rather than inferring either: it derives only the deadline, from the match's own time limit, which came from the
      * challenge and is not the engine's to know.
      *
      * This engine only ever sends `next` empty. Both seats are pending from the moment the match is created, and
      * matchmaker leaves a participant named in neither list alone — so the seat that has yet to throw stays pending
      * without being named, and the seat that just threw is cleared by being the mover. Naming the waiting seat as
      * `next` would restart its clock at the other player's throw, which is precisely what this game does not do.
      *
      * `startedAt` is the match's creation for both seats, however late either of them throws. It is the field that
      * makes a simultaneous game chargeable at all: the move before says nothing about when a player who was never
      * waiting began to think.
      */
    case class MoveNotification(
        participantId: Long,
        next: List[Long] = Nil,
        takenAt: Instant,
        startedAt: Instant
    )

    /** Step 3. `scores` is an open map — matchmaker stores whatever the game puts there. This engine reports `outcome`
      * (win/loss/draw) and `throw` (what the seat threw, now that the match is over and there is nothing left to hide).
      */
    case class ResultEntry(participantId: Long, rank: Int, scores: Map[String, ujson.Value], isWinner: Boolean)

    case class MatchResults(results: List[ResultEntry])

    given ReadWriter[EnginePlayer] = macroRW
    given ReadWriter[CreateGameRequest] = macroRW
    given ReadWriter[CreateGameResponse] = macroRW
    given ReadWriter[EngineParticipantStatus] = macroRW
    given ReadWriter[EngineTurn] = macroRW
    given ReadWriter[GameStatusResponse] = macroRW
    given ReadWriter[MoveNotification] = macroRW
    given ReadWriter[ResultEntry] = macroRW
    given ReadWriter[MatchResults] = macroRW

    // ---- the engine's own play API --------------------------------------------------------

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
      * `thrown` — whether that seat has moved, which both players and any watcher may know — and `shape` only once
      * there is nothing left to decide. Hiding it in the page rather than in the answer would be no hiding at all: the
      * page is served to the player, and the player can read the response.
      *
      * `waitingFor` is who has yet to throw, by side, which is this game's answer to "whose turn is it" — plural, and
      * empty once the match is over.
      */
    case class StateResponse(
        matchId: String,
        waitingFor: List[String],
        you: Option[String],
        yourThrow: Option[String],
        completed: Boolean,
        winner: Option[String],
        draw: Boolean,
        players: List[SeatView]
    )

    /** One seat as a viewer may see it. `shape` is `None` until the match is over, whoever is asking — including of the
      * viewer's own seat, which is what `yourThrow` is for.
      */
    case class SeatView(side: String, cognitoId: String, participantId: Long, thrown: Boolean, shape: Option[String])

    given ReadWriter[MoveRequest] = macroRW
    given ReadWriter[SeatView] = macroRW
    given ReadWriter[StateResponse] = macroRW
}
