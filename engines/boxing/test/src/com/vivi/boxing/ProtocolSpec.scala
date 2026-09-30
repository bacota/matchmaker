package com.vivi.boxing

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

/** The one place the engine and matchmaker are compared directly: every message is written by one side and read by the
  * other.
  *
  * What this engine adds to the other two engines' versions of this suite is the character: a create request whose
  * seats carry a character id and state, and the state write the engine makes once a fighter is built.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match.
  */
class ProtocolSpec extends FunSuite {

    import Protocol.given
    import EngineJson.given

    private val average = Fighter(5, 5, 5, 5, 5)

    private val create = Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "boxing",
      isPublic = true,
      parameters = Map("rounds" -> "3"),
      settings = "{}",
      timeLimitSeconds = Some(600L),
      players = List(
        Protocol.EnginePlayer("sub-alice", 11L, Some("Red"), Some(101L), Some(Fighter.toState(average))),
        Protocol.EnginePlayer("sub-bob", 22L, Some("Blue"), Some(202L), Some(Fighter.toState(average)))
      ),
      moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
      resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
    )

    private def fought(plans: (String, Allocation)*): Engine = {
        val engine = Engine(InMemoryMatchStore(), RecordingMatchmaker(), "http://engine.test")
        engine.createGame(create)
        plans.foreach((player, a) => engine.plan("m-1", player, a))
        engine
    }

    test("matchmaker's create request, characters and all, reads as the engine's") {
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

    test("a status answer mid-round reads as matchmaker's, turns and all") {
        val status = fought("sub-alice" -> Allocation(5, 0, 0)).status("m-1").toOption.get
        val asMatchmaker = read[MmGameStatusResponse](write(status))

        assertEquals(asMatchmaker.completed, false)
        assertEquals(asMatchmaker.participants.map(p => p.participantId -> p.pending), List(11L -> false, 22L -> true))
        assertEquals(asMatchmaker.turns.map(_.participantId), List(11L))
        assertEquals(asMatchmaker.turns.head.startedAt, status.turns.head.startedAt)
    }

    test("a move callback that starts a new round, naming both corners, reads as matchmaker's MoveNotification") {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val notification = Protocol.MoveNotification(11L, List(11L, 22L), at, at.minusSeconds(90))
        val asMatchmaker =
            read[Json.MoveNotification](write(notification))(using Json.given_ReadWriter_MoveNotification)

        assertEquals(asMatchmaker.participantId, ParticipantId(11L))
        // The mover among them: matchmaker clears the mover first and then makes `next` pending, so
        // naming it is how its clock restarts for the next round.
        assertEquals(asMatchmaker.next, List(ParticipantId(11L), ParticipantId(22L)))
        assertEquals(asMatchmaker.startedAt, notification.startedAt)
    }

    test("the engine's results callback reads as matchmaker's MatchResults, scores and all") {
        val engine =
            fought((1 to 3).flatMap(_ => List("sub-alice" -> Allocation(5, 0, 0), "sub-bob" -> Allocation(0, 5, 0)))*)
        val results = engine.resultsOf(engine.read("m-1").toOption.get)
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)

        val winner = asMatchmaker.results.find(_.isWinner).get
        assertEquals(winner.participantId, ParticipantId(11L))
        assertEquals(winner.rank, 1)
        assertEquals(winner.scores("outcome").str, "win")
        assertEquals(winner.scores("method").str, "points")
        assertEquals(asMatchmaker.results.filterNot(_.isWinner).map(_.rank), List(2))
    }

    test("the fighter the engine saves reads as matchmaker's character-state request") {
        val request = Protocol.UpdateStateRequest(Fighter.toState(Fighter(4, 6, 5, 6, 4)))
        val asMatchmaker =
            read[Json.UpdateStateRequest](write(request))(using Json.given_ReadWriter_UpdateStateRequest)

        assertEquals(asMatchmaker.state, request.state)
        // And what matchmaker then hands back in a later create request is a fighter again.
        assertEquals(Fighter.fromState(asMatchmaker.state), Some(Fighter(4, 6, 5, 6, 4)))
    }
}
