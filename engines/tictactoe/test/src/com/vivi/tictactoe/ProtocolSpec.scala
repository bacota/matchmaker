package com.vivi.tictactoe

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{EngineJson, CreateGameRequest => MmCreateGameRequest, CreateGameResponse => MmCreateGameResponse, GameStatusResponse => MmGameStatusResponse}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId

/** The one place the engine and matchmaker are compared directly.
  *
  * `Protocol` restates matchmaker's wire types rather than importing them, so that a rename on
  * one side cannot be hidden by the compiler. That only helps if something checks the two still
  * agree, and this is it: every message is written by one side and read by the other.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to
  * match — and, if a real engine is already deployed, versioning the change rather than making it.
  */
class ProtocolSpec extends FunSuite {

  import Protocol.given
  import EngineJson.given

  private val create = Protocol.CreateGameRequest(
    matchId = "m-1",
    gameName = "tic-tac-toe",
    isPublic = true,
    parameters = Map("board" -> "3x3"),
    settings = """{"variant":"standard"}""",
    timeLimitSeconds = Some(600L),
    players = List(
      Protocol.EnginePlayer("sub-alice", 11L, Some("X"), None, None),
      Protocol.EnginePlayer("sub-bob", 22L, Some("O"), Some(7L), Some("{}"))
    ),
    moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
    resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
  )

  test("matchmaker's create request reads as the engine's") {
    val fromMatchmaker = MmCreateGameRequest(
      matchId = create.matchId,
      gameName = create.gameName,
      isPublic = create.isPublic,
      parameters = create.parameters,
      settings = create.settings,
      timeLimitSeconds = create.timeLimitSeconds,
      players = create.players.map(p =>
        com.vivi.matchmaker.engine.EnginePlayer(p.cognitoId, p.participantId, p.role, p.characterId, p.characterState)
      ),
      moveCallbackUrl = create.moveCallbackUrl,
      resultsCallbackUrl = create.resultsCallbackUrl
    )

    assertEquals(read[Protocol.CreateGameRequest](write(fromMatchmaker)), create)
  }

  test("the engine's create response reads as matchmaker's") {
    val response = Protocol.CreateGameResponse("http://engine/status", "http://engine/play?seat=t", Some("http://engine/board"))
    val asMatchmaker = read[MmCreateGameResponse](write(response))

    assertEquals(asMatchmaker.statusUrl, response.statusUrl)
    assertEquals(asMatchmaker.playUrl, response.playUrl)
    assertEquals(asMatchmaker.publicUrl, response.publicUrl)
  }

  test("the engine's status response reads as matchmaker's, prevMoveAt included") {
    val store = InMemoryMatchStore()
    val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
    engine.createGame(create)
    val status = engine.status("m-1").toOption.get

    val asMatchmaker = read[MmGameStatusResponse](write(status))
    assertEquals(asMatchmaker.completed, false)
    assertEquals(asMatchmaker.participants.map(_.participantId), List(11L, 22L))
    assertEquals(asMatchmaker.participants.map(_.pending), List(true, false))
    assertEquals(asMatchmaker.participants.head.prevMoveAt, status.participants.head.prevMoveAt)
  }

  test("the engine's move callback reads as matchmaker's MoveNotification") {
    val notification = Protocol.MoveNotification(11L, List(22L), Some(java.time.Instant.parse("2026-01-01T00:00:00Z")))
    val asMatchmaker = read[Json.MoveNotification](write(notification))(using Json.given_ReadWriter_MoveNotification)

    assertEquals(asMatchmaker.participantId, ParticipantId(11L))
    assertEquals(asMatchmaker.next, List(ParticipantId(22L)))
    assertEquals(asMatchmaker.prevMoveAt, notification.prevMoveAt)
  }

  test("the engine's results callback reads as matchmaker's MatchResults, scores and all") {
    val store = InMemoryMatchStore()
    val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
    engine.createGame(create)
    val m = store.get("m-1").get
    // X takes the top row.
    List((Mark.X, 0), (Mark.O, 3), (Mark.X, 1), (Mark.O, 4), (Mark.X, 2))
      .foreach((mark, cell) => engine.move("m-1", m.seatOf(mark).get.cognitoId, cell))

    val results = engine.resultsOf(store.get("m-1").get)
    val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)

    val winner = asMatchmaker.results.find(_.isWinner).get
    assertEquals(winner.participantId, ParticipantId(11L))
    assertEquals(winner.rank, 1)
    assertEquals(winner.scores("outcome").str, "win")
    assertEquals(winner.scores("moves").num, 3.0)
    assertEquals(asMatchmaker.results.filterNot(_.isWinner).map(_.rank), List(2))
  }
}
