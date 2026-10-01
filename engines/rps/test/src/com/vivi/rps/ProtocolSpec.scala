package com.vivi.rps

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{EngineJson, GameStatusResponse => MmGameStatusResponse}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId

/** What this engine actually sends matchmaker, read back with matchmaker's own classes.
  *
  * The wire types themselves are compared in `com.vivi.engine.ProtocolSpec`, with messages built by hand; this suite
  * checks the messages this engine builds from a real match. What it adds to tic-tac-toe's version is the simultaneous
  * case: two pending seats in one status answer. That is a shape of the protocol matchmaker has always allowed and
  * nothing else exercised.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match — and, if a real
  * engine is already deployed, versioning the change rather than making it.
  */
class ProtocolSpec extends FunSuite {

    import Protocol.given
    import EngineJson.given

    private val create = Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "rock-paper-scissors",
      isPublic = true,
      parameters = Map("throws" -> "3"),
      settings = """{"variant":"standard"}""",
      timeLimitSeconds = Some(600L),
      players = List(
        Protocol.EnginePlayer("sub-alice", 11L, Some("One"), None, None),
        Protocol.EnginePlayer("sub-bob", 22L, Some("Two"), Some(7L), Some("{}"))
      ),
      moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
      resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
    )

    private def played(throws: (String, Shape)*): Engine = {
        val store = InMemoryMatchStore()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        engine.createGame(create)
        throws.foreach((player, shape) => engine.move("m-1", player, shape))
        engine
    }

    test("a status answer with both seats pending reads as matchmaker's, which is the point of this engine") {
        val status = played().status("m-1").toOption.get

        val asMatchmaker = read[MmGameStatusResponse](write(status))
        assertEquals(asMatchmaker.completed, false)
        assertEquals(asMatchmaker.participants.map(_.participantId), List(11L, 22L))
        // Two seats on the clock at once, which matchmaker records as two participants pending and
        // summarises as a `whoseTurn` of two names.
        assertEquals(asMatchmaker.participants.map(_.pending), List(true, true))
        assert(asMatchmaker.participants.forall(_.prevMoveAt.isDefined))
        // No throws yet, so nothing to report — and matchmaker reads the absence the same way.
        assertEquals(asMatchmaker.turns, Nil)
    }

    test("the turns in a status answer read as matchmaker's EngineTurn, timestamps and all") {
        val status = played("sub-alice" -> Shape.Rock).status("m-1").toOption.get
        val asMatchmaker = read[MmGameStatusResponse](write(status))

        assertEquals(asMatchmaker.turns.map(_.participantId), List(11L))
        assertEquals(asMatchmaker.turns.head.takenAt, status.turns.head.takenAt)
        assertEquals(asMatchmaker.turns.head.startedAt, status.turns.head.startedAt)
    }

    test("the engine's results callback reads as matchmaker's MatchResults, scores and all") {
        val engine = played("sub-alice" -> Shape.Rock, "sub-bob" -> Shape.Scissors)
        val results = engine.resultsOf(engine.read("m-1").toOption.get)
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)

        val winner = asMatchmaker.results.find(_.isWinner).get
        assertEquals(winner.participantId, ParticipantId(11L))
        assertEquals(winner.rank, 1)
        assertEquals(winner.scores("outcome").str, "win")
        // The throws travel in the open `scores` map, which is matchmaker's way of storing whatever
        // a game thinks worth keeping about a seat.
        assertEquals(winner.scores("throw").str, "Rock")
        assertEquals(asMatchmaker.results.filterNot(_.isWinner).map(_.rank), List(2))
    }

    test("a drawn match reads as two winners of nothing: rank 1 each, isWinner false") {
        val engine = played("sub-alice" -> Shape.Paper, "sub-bob" -> Shape.Paper)
        val results = engine.resultsOf(engine.read("m-1").toOption.get)
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)

        assertEquals(asMatchmaker.results.map(_.rank), List(1, 1))
        assert(asMatchmaker.results.forall(!_.isWinner))
        assert(asMatchmaker.results.forall(_.scores("outcome").str == "draw"))
    }
}
