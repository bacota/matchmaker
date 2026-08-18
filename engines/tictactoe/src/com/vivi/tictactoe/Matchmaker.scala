package com.vivi.tictactoe

import upickle.default.write
import Protocol.given

/** The calls the engine makes *back* to matchmaker: steps 2 and 3 of `interaction-design.txt`.
  *
  * An interface because the tests must be able to see what the engine would have sent without a
  * matchmaker to send it to — [[RecordingMatchmaker]] is what every test drives.
  */
trait Matchmaker {
  def recordMove(url: String, notification: Protocol.MoveNotification): Unit
  def recordResults(url: String, results: Protocol.MatchResults): Unit
}

/** Posts the callbacks over HTTP, to the urls matchmaker itself supplied when it created the game.
  *
  * Authentication differs by deployment, and both are supported because both are real:
  *
  *   - Deployed, matchmaker's callback routes are `AWS_IAM`, so the request is signed and the
  *     identity is this engine's execution role. What matchmaker compares it against is the
  *     game's `external_id`, which must therefore be the role's ARN.
  *   - Locally, matchmaker runs with `AUTH_MODE=header` and takes the caller from `X-External-Id`,
  *     so the engine sends the game's external id there instead.
  *
  * Both may be set: a signed request that also carries the header is what a local engine pointed
  * at a deployed matchmaker would send, and matchmaker ignores whichever its mode does not use.
  */
class HttpMatchmaker(http: SignedHttp, externalId: Option[String]) extends Matchmaker {

  private def headers = Map("content-type" -> "application/json") ++ externalId.map("x-external-id" -> _)

  def recordMove(url: String, notification: Protocol.MoveNotification): Unit =
    http.post(url, write(notification), "execute-api", headers)

  def recordResults(url: String, results: Protocol.MatchResults): Unit =
    http.post(url, write(results), "execute-api", headers)
}

/** Keeps the callbacks instead of sending them.
  *
  * Used by the tests, and by the local server when it is started with no matchmaker to call, so
  * that the engine can be played through on its own — the board still works, and the callbacks
  * it would have made are printed.
  */
class RecordingMatchmaker(log: String => Unit = _ => ()) extends Matchmaker {

  private val movesBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MoveNotification)]()
  private val resultsBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MatchResults)]()

  def recordMove(url: String, notification: Protocol.MoveNotification): Unit = synchronized {
    movesBuffer += (url -> notification)
    log(s"POST $url ${write(notification)}")
  }

  def recordResults(url: String, results: Protocol.MatchResults): Unit = synchronized {
    resultsBuffer += (url -> results)
    log(s"POST $url ${write(results)}")
  }

  def moves: List[(String, Protocol.MoveNotification)] = synchronized(movesBuffer.toList)
  def results: List[(String, Protocol.MatchResults)] = synchronized(resultsBuffer.toList)
}
