package com.vivi.rps

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{
    EngineJson,
    CreateGameRequest => MmCreateGameRequest,
    CreateGameResponse => MmCreateGameResponse,
    GameStatusResponse => MmGameStatusResponse
}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId

/** The one place the engine and matchmaker are compared directly.
  *
  * `Protocol` restates matchmaker's wire types rather than importing them, so that a rename on one side cannot be
  * hidden by the compiler. That only helps if something checks the two still agree, and this is it: every message is
  * written by one side and read by the other.
  *
  * What this engine adds to tic-tac-toe's version of the same suite is the simultaneous case: two pending seats in one
  * status answer, and a move callback that names nobody as next. Those are shapes of the protocol matchmaker has always
  * allowed and nothing exercised.
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

    test("matchmaker's create request reads as the engine's") {
        val fromMatchmaker = MmCreateGameRequest(
          matchId = create.matchId,
          gameName = create.gameName,
          isPublic = create.isPublic,
          parameters = create.parameters,
          settings = create.settings,
          timeLimitSeconds = create.timeLimitSeconds,
          players = create.players.map(p =>
              com.vivi.matchmaker.engine
                  .EnginePlayer(p.cognitoId, p.participantId, p.role, p.characterId, p.characterState)
          ),
          moveCallbackUrl = create.moveCallbackUrl,
          resultsCallbackUrl = create.resultsCallbackUrl
        )

        assertEquals(read[Protocol.CreateGameRequest](write(fromMatchmaker)), create)
    }

    test("the engine's create response reads as matchmaker's") {
        val response =
            Protocol.CreateGameResponse("http://engine/status", "http://engine/play", Some("http://engine/board"))
        val asMatchmaker = read[MmCreateGameResponse](write(response))

        assertEquals(asMatchmaker.statusUrl, response.statusUrl)
        assertEquals(asMatchmaker.playUrl, response.playUrl)
        assertEquals(asMatchmaker.publicUrl, response.publicUrl)
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

    test("a move callback naming nobody as next reads as matchmaker's MoveNotification") {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val notification = Protocol.MoveNotification(11L, Nil, at, at.minusSeconds(90))
        val asMatchmaker =
            read[Json.MoveNotification](write(notification))(using Json.given_ReadWriter_MoveNotification)

        assertEquals(asMatchmaker.participantId, ParticipantId(11L))
        // The match's own start, which is the only way matchmaker can charge a simultaneous turn:
        // inferring it from the move before would bill this player for the other one's thinking.
        assertEquals(asMatchmaker.startedAt, notification.startedAt)
        // An empty `next` is how the seat still to throw stays pending: matchmaker clears the mover
        // and leaves every participant it was told nothing about alone.
        assertEquals(asMatchmaker.next, Nil)
        assertEquals(asMatchmaker.takenAt, notification.takenAt)
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
