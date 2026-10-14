package com.vivi.boxing

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.engine.{EngineJson, GameStatusResponse => MmGameStatusResponse}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.model.ParticipantId
import com.vivi.engine.{InMemoryMatchStore, RecordingMatchmaker}

/** What this engine actually sends matchmaker, read back with matchmaker's own classes: a status answer from a bout in
  * progress, and results with a bout's scores.
  *
  * The wire types themselves are compared in `com.vivi.engine.ProtocolSpec`, with messages built by hand — the
  * characters a create request carries and the state write the engine makes once a fighter is built among them.
  *
  * A failure here means the wire format has changed. Fixing it means changing `Protocol` to match.
  */
class ProtocolSpec extends FunSuite {

    import Protocol.given
    import EngineJson.given

    private val average = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)

    private val create = Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "boxing",
      isPublic = true,
      parameters = Map("rounds" -> "3"),
      settings = "{}",
      timeLimitSeconds = Some(600L),
      players = List(
        Protocol.EnginePlayer(
          cognitoId = "sub-alice",
          participantId = 11L,
          role = Some("Red"),
          characterId = Some(101L),
          characterState = Some(Fighter.toState(average))
        ),
        Protocol.EnginePlayer(
          cognitoId = "sub-bob",
          participantId = 22L,
          role = Some("Blue"),
          characterId = Some(202L),
          characterState = Some(Fighter.toState(average))
        )
      ),
      moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
      resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
    )

    private def fought(plans: (String, Allocation)*): Engine = {
        val engine =
            Engine(store = InMemoryMatchStore(), matchmaker = RecordingMatchmaker(), baseUrl = "http://engine.test")
        engine.createGame(create)
        plans.foreach((player, a) => engine.plan(matchId = "m-1", cognitoId = player, allocation = a))
        engine
    }

    test("a status answer mid-round reads as matchmaker's, turns and all") {
        val status = fought("sub-alice" -> Allocation(offense = 5, defense = 0, power = 0)).status("m-1").toOption.get
        val asMatchmaker = read[MmGameStatusResponse](write(status))

        assertEquals(asMatchmaker.isCompleted, false)
        assertEquals(
          asMatchmaker.participants.map(p => p.participantId -> p.isPending),
          List(11L -> false, 22L -> true)
        )
        assertEquals(asMatchmaker.turns.map(_.participantId), List(11L))
        assertEquals(asMatchmaker.turns.head.startedAt, status.turns.head.startedAt)
    }

    test("the engine's results callback reads as matchmaker's MatchResults, scores and all") {
        val engine =
            fought(
              (1 to 3).flatMap(_ =>
                  List(
                    "sub-alice" -> Allocation(offense = 5, defense = 0, power = 0),
                    "sub-bob" -> Allocation(offense = 0, defense = 5, power = 0)
                  )
              )*
            )
        val results = engine.resultsOf(engine.read("m-1").toOption.get)
        val asMatchmaker = read[Json.MatchResults](write(results))(using Json.given_ReadWriter_MatchResults)

        val winner = asMatchmaker.results.find(_.isWinner).get
        assertEquals(winner.participantId, ParticipantId(11L))
        assertEquals(winner.rank, 1)
        assertEquals(winner.scores("outcome").str, "win")
        assertEquals(winner.scores("method").str, "points")
        assertEquals(asMatchmaker.results.filterNot(_.isWinner).map(_.rank), List(2))
    }
}
