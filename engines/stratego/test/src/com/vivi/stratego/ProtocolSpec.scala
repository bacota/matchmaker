package com.vivi.stratego

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{EngineJson, GameStatusResponse => MmGameStatusResponse}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId
import com.vivi.engine.{InMemoryMatchStore, RecordingMatchmaker}
import Armies.{names, setup}

/** What this engine actually sends matchmaker, read back with matchmaker's own classes.
  *
  * The wire types themselves are compared in `com.vivi.engine.ProtocolSpec`, with messages built by hand; this suite
  * checks the messages this engine builds from a real match — including the two setups, which are turns like any other.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match — and, if a real
  * engine is already deployed, versioning the change rather than making it.
  */
class ProtocolSpec extends FunSuite {

    import Protocol.given
    import EngineJson.given

    private val create = Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "stratego",
      isPublic = true,
      parameters = Map("maxMoves" -> "500"),
      settings = "{}",
      timeLimitSeconds = Some(600L),
      players = List(
        Protocol.EnginePlayer("sub-alice", 11L, Some("Red"), None, None),
        Protocol.EnginePlayer("sub-bob", 22L, Some("Blue"), None, None)
      ),
      moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
      resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
    )

    private def engine() = {
        val store = InMemoryMatchStore[StrategoMatch]()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
        engine.createGame(create)
        (engine, store)
    }

    test("during setup both seats read as pending to matchmaker") {
        val (engine, _) = this.engine()
        val asMatchmaker = read[MmGameStatusResponse](write(engine.status("m-1").toOption.get))
        assertEquals(asMatchmaker.completed, false)
        assertEquals(asMatchmaker.participants.map(_.pending), List(true, true))
        assertEquals(asMatchmaker.turns, Nil)
    }

    test("setups and moves read as matchmaker's turns, timestamps and all") {
        val (engine, _) = this.engine()
        engine.deploy("m-1", "sub-alice", names(setup(Side.Red, Map(30 -> Rank.Scout))))
        engine.deploy("m-1", "sub-bob", names(setup(Side.Blue)))
        engine.move("m-1", "sub-alice", 30, 40)

        val status = engine.status("m-1").toOption.get
        val asMatchmaker = read[MmGameStatusResponse](write(status))
        assertEquals(asMatchmaker.turns.map(_.participantId), List(11L, 22L, 11L))
        assertEquals(asMatchmaker.turns.map(_.startedAt), status.turns.map(_.startedAt))
        assertEquals(asMatchmaker.participants.map(_.pending), List(false, true))
    }

    test("the engine's results callback reads as matchmaker's MatchResults, scores and all") {
        val (engine, store) = this.engine()
        engine.deploy("m-1", "sub-alice", names(setup(Side.Red, Map(30 -> Rank.Scout))))
        engine.deploy("m-1", "sub-bob", names(setup(Side.Blue, Map(60 -> Rank.Flag))))
        engine.move("m-1", "sub-alice", 30, 60)

        val results = engine.resultsOf(store.get("m-1").get)
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)
        val winner = asMatchmaker.results.find(_.isWinner).get
        assertEquals(winner.participantId, ParticipantId(11L))
        assertEquals(winner.scores("outcome").str, "win")
        assertEquals(winner.scores("ending").str, "flag")
        assertEquals(asMatchmaker.results.filterNot(_.isWinner).map(_.rank), List(2))
    }

    test("a move body is either a setup or a pair of squares, each without the other's fields") {
        assertEquals(
          read[Protocol.MoveRequest]("""{"from":30,"to":40}"""),
          Protocol.MoveRequest(None, Some(30), Some(40))
        )
        assertEquals(read[Protocol.MoveRequest]("""{"setup":["Flag"]}""").setup, Some(List("Flag")))
        // A hidden rank is a null, which is how the page tells hidden from shown — and nothing else of it is sent.
        val hidden = write(Protocol.PieceView(60, "Blue", None, false, true))
        assertEquals(hidden, """{"square":60,"side":"Blue","rank":null,"revealed":false,"moved":true}""")
    }
}
