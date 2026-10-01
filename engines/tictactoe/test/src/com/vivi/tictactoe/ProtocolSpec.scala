package com.vivi.tictactoe

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{EngineJson, GameStatusResponse => MmGameStatusResponse}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId

/** What this engine actually sends matchmaker, read back with matchmaker's own classes.
  *
  * The wire types themselves are compared in `com.vivi.engine.ProtocolSpec`, with messages built by hand; this suite
  * checks the messages this engine builds from a real match.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match — and, if a real
  * engine is already deployed, versioning the change rather than making it.
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
        // No moves yet, so nothing to report — and matchmaker reads the absence the same way.
        assertEquals(asMatchmaker.turns, Nil)
    }

    test("the turns in a status answer read as matchmaker's EngineTurn, timestamps and all") {
        val store = InMemoryMatchStore()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        engine.createGame(create)
        engine.move("m-1", "sub-alice", 0)

        val status = engine.status("m-1").toOption.get
        val asMatchmaker = read[MmGameStatusResponse](write(status))

        assertEquals(asMatchmaker.turns.map(_.participantId), List(11L))
        assertEquals(asMatchmaker.turns.head.takenAt, status.turns.head.takenAt)
        assertEquals(asMatchmaker.turns.head.startedAt, status.turns.head.startedAt)
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
