package com.vivi.engine

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** The wire format between matchmaker and a game engine, restated from the engines' side.
  *
  * These are deliberately *not* matchmaker's classes, even though the repository happens to hold both. A game engine is
  * a separate system that matchmaker reaches over HTTP, and depending on its classes would hide exactly the failure the
  * engines exist to catch: a field renamed on one side and not the other would keep compiling. Restating them means the
  * two agree only if the JSON really matches, which is what `ProtocolSpec` checks against matchmaker's own definitions.
  *
  * Stated once for every engine, since what matchmaker sends and accepts does not depend on the game. Each engine's own
  * `Protocol` exports these beside its play API.
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

    /** `live` makes the match a live one: see [[LiveTerms]]. Absent from a matchmaker that predates live matches, and
      * for every match that is not one.
      */
    case class CreateGameRequest(
        matchId: String,
        gameName: String,
        isPublic: Boolean,
        parameters: Map[String, String],
        settings: String,
        timeLimitSeconds: Option[Long],
        players: List[EnginePlayer],
        moveCallbackUrl: Option[String],
        resultsCallbackUrl: Option[String],
        live: Option[LiveTerms] = None
    )

    /** The terms of a live match, which the engine keeps rather than matchmaker.
      *
      * In a live match the engine runs the turns and their clock itself: it sends no move callbacks, and when a player
      * runs out of time it ends the match by forfeit and reports the result. Matchmaker hears about the match when it
      * is over, and not before.
      *
      * `timeLimitSeconds` is required — a live match is one played against a clock — and `kind` says what it limits, in
      * matchmaker's own codes: `PER_TURN`, each turn afresh, or `TOTAL`, each player's budget for the whole match, like
      * a chess clock.
      */
    case class LiveTerms(timeLimitSeconds: Long, kind: String = "PER_TURN")

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
      * the mover's own clock started for it, so the pair is what the move cost them. Matchmaker takes both as stated
      * rather than inferring either: it derives only the deadline, from the match's own time limit, which came from the
      * challenge and is not the engine's to know.
      *
      * Matchmaker clears the mover, makes everyone in `next` pending from `takenAt`, and leaves a participant named in
      * neither alone. So a seat that was already waiting keeps its clock by not being named, and naming one restarts
      * it. Which seats an engine names is its game's business, and each engine's `notify` says why it names the ones it
      * does.
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

    /** Step 3. `scores` is an open map — matchmaker stores whatever the game puts there.
      *
      * `forfeit` says the match was ended by a turn's clock rather than by play, which only happens in a live match: it
      * is on every seat's entry, as matchmaker's own forfeits are, and `isWinner` tells "won by forfeit" from
      * "forfeited".
      */
    case class ResultEntry(
        participantId: Long,
        rank: Int,
        scores: Map[String, ujson.Value],
        isWinner: Boolean,
        forfeit: Boolean = false
    )

    /** `turns` is every turn of the match, in the same shape as a status answer's. Matchmaker records them in the
      * transaction that completes the match, so a turn whose move callback was lost is not lost with it — once a match
      * is over, matchmaker does not ask the engine again.
      */
    case class MatchResults(results: List[ResultEntry], turns: Option[List[EngineTurn]] = None)

    /** What an engine writes to matchmaker's `PUT /characters/{id}/state`, for a game whose seats are characters.
      * `state` is whatever the game keeps about the character, carried as a string: matchmaker stores a character's
      * state without reading it, and hands it back in the `characterState` of each later create request.
      */
    case class UpdateStateRequest(state: String)

    /** What an engine sends matchmaker's `POST /characters` once a player has made a character in it. Characters are
      * made in their engine, so this is how matchmaker learns one exists: from then on it can be offered in challenges
      * and seated in matches, and its `state` comes back in each create request's `characterState`. Which game it is in
      * is not said: matchmaker takes it from the engine's own identity, as it does for every callback.
      *
      * `ownerExternalId` is the `sub` of the player who made it — the same id matchmaker sends as `cognitoId`.
      */
    case class RegisterCharacterRequest(name: String, description: String, ownerExternalId: String, state: String)

    /** The part of matchmaker's answer to a registration an engine needs: the id the character is known by, which is
      * the `characterId` it will be seated with.
      */
    case class RegisteredCharacter(characterId: Long)

    /** What an engine sends matchmaker's `PUT /characters/{characterId}` when a player changes a character's name or
      * description in it. `ownerExternalId` is who signed in to ask; matchmaker changes the character only if that
      * player owns it, and answers a character they do not own as though there were none.
      */
    case class EditCharacterRequest(name: String, description: String, ownerExternalId: String)

    /** What an engine sends matchmaker's `PUT /characters/{characterId}/owner` when a player gives a character to
      * another, named by their matchmaker nickname. Checked against `ownerExternalId` as an edit is.
      */
    case class TransferCharacterRequest(toNickname: String, ownerExternalId: String)

    /** One of a player's characters, as matchmaker's `GET /characters?owner=` lists them for the engine to show — the
      * engine keeps no characters of its own. Matchmaker's answer carries more fields; these are the ones read.
      */
    case class OwnedCharacter(characterId: Long, name: String, description: String, state: String)

    given ReadWriter[EnginePlayer] = macroRW
    given ReadWriter[LiveTerms] = macroRW
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
    given ReadWriter[RegisterCharacterRequest] = macroRW
    given ReadWriter[RegisteredCharacter] = macroRW
    given ReadWriter[EditCharacterRequest] = macroRW
    given ReadWriter[TransferCharacterRequest] = macroRW
    given ReadWriter[OwnedCharacter] = macroRW
}
