package com.vivi.tictactoe

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** The wire format between matchmaker and a game engine, restated from the engine's side.
  *
  * These are deliberately *not* matchmaker's classes, even though the repository happens to hold
  * both. A game engine is a separate system that matchmaker reaches over HTTP, and depending on
  * its classes would hide exactly the failure this engine exists to catch: a field renamed on one
  * side and not the other would keep compiling. Restating them means the two agree only if the
  * JSON really matches, which is what `ProtocolSpec` checks against matchmaker's own definitions.
  *
  * The shapes come from `matchmaker/src/com/vivi/matchmaker/engine/GameEngineClient.scala` (the
  * calls in) and `shared/src/com/vivi/matchmaker/api/Json.scala` (the callbacks out).
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

  /** One move, as reported to a status call. `startedAt` is when that player's clock started
    * for it — this engine knows it exactly (it is the move before) so it says so rather than
    * leaving matchmaker to infer it.
    */
  case class EngineTurn(participantId: Long, takenAt: Instant, startedAt: Option[Instant] = None)

  /** `turns` are the moves made after the `since` the status call asked from, oldest first. */
  case class GameStatusResponse(
      completed: Boolean,
      participants: List[EngineParticipantStatus],
      turns: List[EngineTurn] = Nil
  )

  // ---- what the engine calls back with --------------------------------------------------

  /** Step 2. `next` is who may move now; empty on the move that ends the game. `prevMoveAt` is
    * when this move was made, which is when the next player's clock starts — matchmaker turns
    * that into a deadline using the match's own time limit, so the engine does not send one.
    */
  case class MoveNotification(
      participantId: Long,
      next: List[Long] = Nil,
      prevMoveAt: Option[Instant] = None
  )

  /** Step 3. `scores` is an open map — matchmaker stores whatever the game puts there. This
    * engine reports `outcome` (win/loss/draw) and `moves` (how many marks the seat placed).
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

  /** A move as the board page submits it: which cell to mark. Whose move it is comes from the
    * seat token in the url, not from the body — a player may not name someone else's seat.
    */
  case class MoveRequest(cell: Int)

  /** The state the board page renders, and what a scripted client polls.
    *
    * `you` is the mark belonging to the seat that asked; absent on the public view, which belongs
    * to nobody.
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
