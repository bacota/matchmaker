package com.vivi.matchmaker.engine

import cats.effect.IO
import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** One seat in a game the engine is being asked to create.
  *
  * The engine is integrated with Cognito and identifies a player by their Cognito subject, which is what matchmaker
  * stores as `player.external_id` — it has no notion of matchmaker's own player ids. `participantId` travels the other
  * way: it is matchmaker's key for this seat, and the engine quotes it back in its callbacks so a move or a result
  * lands on the right row without the engine having to know anything else about matchmaker's model.
  *
  * `nickname` is for the engine to show players by, as it stood when the match started: a rename afterwards does not
  * reach a match already under way. Optional, so an engine that predates it reads the request as it always did.
  * `characterName` is the same for the character a seat is played by, and absent for a seat with none.
  */
case class EnginePlayer(
    cognitoId: String,
    participantId: Long,
    role: Option[String],
    characterId: Option[Long],
    characterState: Option[String],
    nickname: Option[String] = None,
    characterName: Option[String] = None
)

/** The request of step 1: create a game, given its parameters, its players and their roles, and whether it is public.
  */
case class CreateGameRequest(
    matchId: String,
    gameName: String,
    isPublic: Boolean,
    parameters: Map[String, String],
    settings: String,
    timeLimitSeconds: Option[Long],
    players: List[EnginePlayer],
    /** Where the engine posts the callbacks of steps 2 and 3. Empty when matchmaker has not been configured with its
      * own public base url, in which case the engine is expected to know where to call — but being explicit costs one
      * field and removes the assumption.
      */
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    /** Present for a live match, and only then: what tells the engine the match is live, and the clock it is played
      * against. Absent otherwise — so an engine that predates live matches is sent exactly what it always was.
      */
    live: Option[LiveTerms] = None,
    /** What players call the game, and the match's own message, for the engine to title its pages with — never the
      * match id, which means nothing to anybody. Absent from an engine's view of a matchmaker that predates them.
      */
    gameDisplayName: Option[String] = None,
    description: Option[String] = None
)

/** What makes a match live, as the engine is told it: the engine runs the turns and their clock, sends no move
  * callbacks, and ends a match whose clock has run out by forfeit — reporting that, as any other ending, through the
  * results callback.
  *
  * `timeLimitSeconds` is the match's time limit, and `kind` the `TimeLimitKind` code saying what it limits: each turn
  * (`PER_TURN`), or each player's whole match like a chess clock (`TOTAL`).
  */
case class LiveTerms(timeLimitSeconds: Long, kind: String)

/** The response of step 1: where matchmaker checks status, where a player plays, and — only for a public game — where
  * anyone can watch.
  */
case class CreateGameResponse(
    statusUrl: String,
    playUrl: String,
    publicUrl: Option[String],
    /** Where to tell the engine the match has been cancelled, so that it can drop it. Absent from an engine that offers
      * no such route, which is then not told: its board stays up, and matchmaker stops listening to it.
      */
    cancelUrl: Option[String] = None
)

/** One participant's state in the engine's answer to a status call (step 4).
  *
  * `prevMoveAt` is when the move before this participant's was made — the moment their clock started. The deadline is
  * not the engine's to state: matchmaker derives it from this and the match's own `timeLimit`, so a match with no time
  * limit has no deadline no matter what the engine reports.
  */
case class EngineParticipantStatus(
    participantId: Long,
    pending: Boolean,
    completed: Boolean,
    prevMoveAt: Option[Instant]
)

/** One turn the engine reports as having been taken, in answer to a status call.
  *
  * `takenAt` is when the move was made. `startedAt` is when that player's clock started for it, which the engine may
  * know better than matchmaker can infer — it is optional because most engines do not track it, and matchmaker then
  * takes the previous turn in the match (or the match's start) as the moment the clock began.
  */
case class EngineTurn(participantId: Long, takenAt: Instant, startedAt: Option[Instant] = None)

/** The engine's answer to a status call.
  *
  * `turns` are the moves made since the `since` the call asked from, oldest first — empty when nothing has happened,
  * and empty from an engine that does not report turns at all, which is why it is defaulted. Matchmaker records them,
  * and their durations are what a total (chess clock) time limit is spent against.
  */
case class GameStatusResponse(
    completed: Boolean,
    participants: List[EngineParticipantStatus],
    turns: List[EngineTurn] = Nil,
    /** The number of the latest move this answer reflects, from an engine that numbers its moves — the same numbering
      * as a move callback's `state.sequence`. It is how matchmaker tells an answer that a callback has since overtaken:
      * the engine is asked outside any transaction, and a move committed while it was answering must not be undone by
      * the answer.
      */
    sequence: Option[Long] = None
)

object EngineJson {
    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[EnginePlayer] = macroRW
    given ReadWriter[LiveTerms] = macroRW
    given ReadWriter[CreateGameRequest] = macroRW
    given ReadWriter[CreateGameResponse] = macroRW
    given ReadWriter[EngineParticipantStatus] = macroRW
    given ReadWriter[EngineTurn] = macroRW
    given ReadWriter[GameStatusResponse] = macroRW
}

/** Matchmaker's half of the two APIs described in `interaction-design.txt`: the calls matchmaker makes *to* a game
  * engine. The calls a game engine makes back are ordinary routes on matchmaker's own API, handled by
  * `GameEngineService`.
  *
  * An interface rather than a concrete client because a game engine is a remote system that tests cannot stand up:
  * every service test drives a stub implementation, and [[HttpGameEngineClient]] is the one that actually goes over the
  * network.
  */
trait GameEngineClient {

    /** Creates a game at `gameUrl`, which is the `url` recorded on the [[com.vivi.matchmaker.model.Game]].
      *
      * `apiKey` is that game's stored key (V34), presented to the engine — given by the caller, which has the game in
      * hand, rather than looked up here, so that two games on one engine host each present their own.
      */
    def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse]

    /** Asks the engine how a match is going, at the `statusUrl` it returned when the game was created.
      *
      * `since` is the most recent turn matchmaker already has recorded; the engine answers with the turns taken after
      * it, so the reply carries what was missed rather than the whole game every time. `None` asks for all of them,
      * which is what a match with no turns recorded wants. `apiKey` is the match's game's stored key, as for
      * `createGame`.
      */
    def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse]

    /** Tells the engine its match has been cancelled, at the `cancelUrl` it gave when the game was created. Refused by
      * a client that cannot, which is every test stub that has no reason to.
      */
    def cancel(cancelUrl: String, apiKey: Option[String]): IO[Unit] =
        IO.raiseError(GameEngineError(s"this engine client cannot cancel a match ($cancelUrl)"))
}

/** Raised when the game engine cannot be reached or answers with something other than success.
  *
  * Deliberately not a `ServiceError`: those are the caller's fault and are mapped to 4xx, while this is a failure of a
  * system behind matchmaker and should surface as a 500, with the detail going to the log rather than to the caller.
  */
class GameEngineError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
