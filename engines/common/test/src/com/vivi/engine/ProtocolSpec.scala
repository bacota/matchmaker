package com.vivi.engine

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

/** The one place the engines' wire format and matchmaker's are compared directly.
  *
  * `Protocol` restates matchmaker's wire types rather than importing them, so that a rename on one side cannot be
  * hidden by the compiler. That only helps if something checks the two still agree, and this is it: every message is
  * written by one side and read by the other.
  *
  * The messages here are built by hand. What each engine actually sends — a status answer from a real match, results
  * with its own scores — is checked by that engine's own `ProtocolSpec`.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match — and, if a real
  * engine is already deployed, versioning the change rather than making it.
  */
class ProtocolSpec extends FunSuite {

    import Protocol.given
    import EngineJson.given

    private val create = Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "boxing",
      isPublic = true,
      parameters = Map("rounds" -> "3"),
      settings = """{"variant":"standard"}""",
      timeLimitSeconds = Some(600L),
      players = List(
        Protocol.EnginePlayer("sub-alice", 11L, Some("Red"), Some(101L), Some("""{"strength":5}""")),
        // A seat with no character, as in a game that has none.
        Protocol.EnginePlayer("sub-bob", 22L, None, None, None)
      ),
      moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
      resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
    )

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

    test("a move callback naming the next seat reads as matchmaker's MoveNotification") {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val notification = Protocol.MoveNotification(11L, List(22L), at, at.minusSeconds(60))
        val asMatchmaker =
            read[Json.MoveNotification](write(notification))(using Json.given_ReadWriter_MoveNotification)

        assertEquals(asMatchmaker.participantId, ParticipantId(11L))
        assertEquals(asMatchmaker.next, List(ParticipantId(22L)))
        assertEquals(asMatchmaker.takenAt, notification.takenAt)
        // What the move cost the player who made it, which matchmaker would otherwise have to infer.
        assertEquals(asMatchmaker.startedAt, notification.startedAt)
    }

    test("a move callback naming the mover among the next reads as matchmaker's MoveNotification") {
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

    test("a numbered move callback reads as matchmaker's MoveNotification, state and all") {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val notification =
            Protocol.MoveNotification(
              11L,
              Nil,
              at,
              at.minusSeconds(90),
              Some(Protocol.MoveState(3L, List(Protocol.PendingSeat(22L, at))))
            )
        val asMatchmaker =
            read[Json.MoveNotification](write(notification))(using Json.given_ReadWriter_MoveNotification)

        assertEquals(asMatchmaker.state.map(_.sequence), Some(3L))
        assertEquals(
          asMatchmaker.state.map(_.pending.map(p => (p.participantId, p.since))),
          Some(List((ParticipantId(22L), at)))
        )
    }

    test("a status answer's move number reads as matchmaker's") {
        val status = Protocol.GameStatusResponse(completed = false, participants = Nil, sequence = Some(4L))
        assertEquals(read[MmGameStatusResponse](write(status)).sequence, Some(4L))
    }

    test("results carrying the match's turns read as matchmaker's MatchResults, turns and all") {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val results = Protocol.MatchResults(
          Nil,
          Some(
            List(
              Protocol.EngineTurn(11L, at.plusSeconds(10), Some(at)),
              Protocol.EngineTurn(22L, at.plusSeconds(20), None)
            )
          )
        )
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)
        assertEquals(
          asMatchmaker.turns.map(_.map(t => (t.participantId, t.takenAt, t.startedAt))),
          Some(List((ParticipantId(11L), at.plusSeconds(10), Some(at)), (ParticipantId(22L), at.plusSeconds(20), None)))
        )
    }

    test("matchmaker's create request for a live match reads as the engine's, turn timeout and all") {
        val fromMatchmaker = MmCreateGameRequest(
          matchId = "m-2",
          gameName = "tic-tac-toe",
          isPublic = false,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = Some(30L),
          players = Nil,
          moveCallbackUrl = None,
          resultsCallbackUrl = None,
          live = Some(com.vivi.matchmaker.engine.LiveTerms(30L))
        )
        assertEquals(read[Protocol.CreateGameRequest](write(fromMatchmaker)).live, Some(Protocol.LiveTerms(30L)))
    }

    test("results ending a live match by forfeit read as matchmaker's, forfeit and all") {
        val results = Protocol.MatchResults(
          List(
            Protocol.ResultEntry(11L, 2, Map("outcome" -> ujson.Str("loss")), isWinner = false, forfeit = true),
            Protocol.ResultEntry(22L, 1, Map("outcome" -> ujson.Str("win")), isWinner = true, forfeit = true)
          )
        )
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)
        assertEquals(
          asMatchmaker.results.map(r => (r.participantId, r.isWinner, r.forfeit)),
          List(
            (ParticipantId(11L), false, true),
            (ParticipantId(22L), true, true)
          )
        )
    }

    test("a character's state as an engine saves it reads as matchmaker's character-state request") {
        val request = Protocol.UpdateStateRequest("""{"strength":4,"speed":6}""")
        val asMatchmaker =
            read[Json.UpdateStateRequest](write(request))(using Json.given_ReadWriter_UpdateStateRequest)

        // Carried as a string and not read: matchmaker hands back exactly this in a later create request.
        assertEquals(asMatchmaker.state, request.state)
    }
}
