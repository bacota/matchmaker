package com.vivi.matchmaker.engine

import cats.effect.IO
import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** One seat in a game the engine is being asked to create.
  *
  * The engine is integrated with Cognito and identifies a player by their Cognito subject, which
  * is what matchmaker stores as `player.external_id` — it has no notion of matchmaker's own
  * player ids. `participantId` travels the other way: it is matchmaker's key for this seat, and
  * the engine quotes it back in its callbacks so a move or a result lands on the right row
  * without the engine having to know anything else about matchmaker's model.
  */
case class EnginePlayer(
    cognitoId: String,
    participantId: Long,
    role: Option[String],
    characterId: Option[Long],
    characterState: Option[String]
)

/** The request of step 1: create a game, given its parameters, its players and their roles, and
  * whether it is public.
  */
case class CreateGameRequest(
    matchId: String,
    gameName: String,
    isPublic: Boolean,
    parameters: Map[String, String],
    settings: String,
    timeLimitSeconds: Option[Long],
    players: List[EnginePlayer],
    /** Where the engine posts the callbacks of steps 2 and 3. Empty when matchmaker has not been
      * configured with its own public base url, in which case the engine is expected to know
      * where to call — but being explicit costs one field and removes the assumption.
      */
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String]
)

/** The response of step 1: where matchmaker checks status, where a player plays, and — only for
  * a public game — where anyone can watch.
  */
case class CreateGameResponse(statusUrl: String, playUrl: String, publicUrl: Option[String])

/** One participant's state in the engine's answer to a status call (step 4).
  *
  * `prevMoveAt` is when the move before this participant's was made — the moment their clock
  * started. The deadline is not the engine's to state: matchmaker derives it from this and the
  * match's own `timeLimit`, so a match with no time limit has no deadline no matter what the
  * engine reports.
  */
case class EngineParticipantStatus(
    participantId: Long,
    pending: Boolean,
    completed: Boolean,
    prevMoveAt: Option[Instant]
)

case class GameStatusResponse(completed: Boolean, participants: List[EngineParticipantStatus])

object EngineJson {
  given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
  given ReadWriter[EnginePlayer] = macroRW
  given ReadWriter[CreateGameRequest] = macroRW
  given ReadWriter[CreateGameResponse] = macroRW
  given ReadWriter[EngineParticipantStatus] = macroRW
  given ReadWriter[GameStatusResponse] = macroRW
}

/** Matchmaker's half of the two APIs described in `interaction-design.txt`: the calls matchmaker
  * makes *to* a game engine. The calls a game engine makes back are ordinary routes on
  * matchmaker's own API, handled by `GameEngineService`.
  *
  * An interface rather than a concrete client because a game engine is a remote system that
  * tests cannot stand up: every service test drives a stub implementation, and
  * [[HttpGameEngineClient]] is the one that actually goes over the network.
  */
trait GameEngineClient {

  /** Creates a game at `gameUrl`, which is the `url` recorded on the [[com.vivi.matchmaker.model.Game]]. */
  def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse]

  /** Asks the engine how a match is going, at the `statusUrl` it returned when the game was
    * created.
    */
  def status(statusUrl: String): IO[GameStatusResponse]
}

/** Raised when the game engine cannot be reached or answers with something other than success.
  *
  * Deliberately not a `ServiceError`: those are the caller's fault and are mapped to 4xx, while
  * this is a failure of a system behind matchmaker and should surface as a 500, with the detail
  * going to the log rather than to the caller.
  */
class GameEngineError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
