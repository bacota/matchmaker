package com.vivi.engine

import upickle.default.write
import Protocol.given

/** The calls an engine makes *back* to matchmaker: steps 2 and 3 of `interaction-design.txt`, and, for a game whose
  * seats are characters, keeping what the game knows about one as the character's state.
  *
  * An interface because the tests must be able to see what the engine would have sent without a matchmaker to send it
  * to — [[RecordingMatchmaker]] is what every test drives.
  */
trait Matchmaker {
    def recordMove(url: String, notification: Protocol.MoveNotification): Unit
    def recordResults(url: String, results: Protocol.MatchResults): Unit

    /** `PUT {matchmakerUrl}/characters/{characterId}/state`. Unlike the two above this one is not best-effort, and an
      * engine that calls it should let it throw: state that matchmaker never heard about has to be made again at the
      * character's next match, and the player making it is there to be told.
      */
    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit
}

/** Posts the callbacks over HTTP, to the urls matchmaker itself supplied when it created the game.
  *
  * Authentication differs by deployment, and both are supported because both are real:
  *
  *   - Deployed, matchmaker's callback routes take the API key this engine and matchmaker share. Matchmaker holds one
  *     key per engine, so the key is also what says *which* engine is calling: it is filed under the game's
  *     `external_id`, and that is the identity the callback is attributed to.
  *   - Locally, matchmaker runs with `AUTH_MODE=header` and takes the caller from `X-External-Id`, so the engine sends
  *     the game's external id there instead.
  *
  * Both may be set: a keyed request that also carries the header is what a local engine pointed at a deployed
  * matchmaker would send, and matchmaker ignores whichever its mode does not use.
  */
class HttpMatchmaker(http: SignedHttp, apiKey: Option[String], externalId: Option[String]) extends Matchmaker {

    private def headers =
        Map("content-type" -> "application/json") ++
            apiKey.map("x-api-key" -> _) ++
            externalId.map("x-external-id" -> _)

    def recordMove(url: String, notification: Protocol.MoveNotification): Unit =
        http.post(url, write(notification), "execute-api", headers)

    def recordResults(url: String, results: Protocol.MatchResults): Unit =
        http.post(url, write(results), "execute-api", headers)

    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit =
        http.send(
          "PUT",
          s"${matchmakerUrl.stripSuffix("/")}/characters/$characterId/state",
          Some(write(Protocol.UpdateStateRequest(state))),
          "execute-api",
          headers
        )
}

/** Keeps the callbacks instead of sending them.
  *
  * Used by the tests, and by the local server when it is started with no matchmaker to call, so that the engine can be
  * played through on its own — the board still works, and the callbacks it would have made are printed.
  */
class RecordingMatchmaker(log: String => Unit = _ => ()) extends Matchmaker {

    private val movesBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MoveNotification)]()
    private val resultsBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MatchResults)]()
    private val statesBuffer = scala.collection.mutable.ListBuffer[(Long, String)]()

    /** While set, [[saveCharacterState]] fails as an unreachable matchmaker would, and records nothing. */
    @volatile var failStateSaves: Boolean = false

    /** While set, [[recordMove]] and [[recordResults]] fail as an unreachable matchmaker would, after recording the
      * attempt — so a test can see that a call was made and that its failure went no further.
      */
    @volatile var failCallbacks: Boolean = false

    def recordMove(url: String, notification: Protocol.MoveNotification): Unit = synchronized {
        movesBuffer += (url -> notification)
        log(s"POST $url ${write(notification)}")
        if (failCallbacks) throw AwsError(s"POST $url failed: unreachable")
    }

    def recordResults(url: String, results: Protocol.MatchResults): Unit = synchronized {
        resultsBuffer += (url -> results)
        log(s"POST $url ${write(results)}")
        if (failCallbacks) throw AwsError(s"POST $url failed: unreachable")
    }

    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit = synchronized {
        if (failStateSaves) throw AwsError(s"PUT $matchmakerUrl/characters/$characterId/state failed: unreachable")
        statesBuffer += (characterId -> state)
        log(s"PUT $matchmakerUrl/characters/$characterId/state $state")
    }

    def moves: List[(String, Protocol.MoveNotification)] = synchronized(movesBuffer.toList)
    def results: List[(String, Protocol.MatchResults)] = synchronized(resultsBuffer.toList)

    /** Each character's state as saved, by character id, oldest first. */
    def characterStates: List[(Long, String)] = synchronized(statesBuffer.toList)
}
