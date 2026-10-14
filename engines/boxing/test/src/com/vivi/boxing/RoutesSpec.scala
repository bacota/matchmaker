package com.vivi.boxing

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{EngineRequest, InMemoryMatchStore, PlayAuth, RecordingMatchmaker}
import Protocol.given

/** Drives the engine the way the outside world does: as requests.
  *
  * The two servers are thin enough that if these pass, both do — which is the point of `Routes` being
  * transport-independent.
  */
class RoutesSpec extends FunSuite {

    private val average = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)

    /** Trusted play auth, the local zero-setup mode: a caller names themselves with `?as=`. */
    private def fixture(isPublic: Boolean = true, blue: Option[Fighter] = Some(average)) = {
        val store = InMemoryMatchStore[Bout]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store = store, matchmaker = recorder, baseUrl = "http://engine.test")
        val routes = Routes(engine = engine, playAuth = PlayAuth.Trusted, matchmakerKey = None)
        val created = routes(
          EngineRequest(
            method = "POST",
            path = "/games",
            query = Map.empty,
            body = write(createRequest(isPublic, blue = blue))
          )
        )
        (routes, store, created, recorder)
    }

    private def createRequest(isPublic: Boolean, blue: Option[Fighter]) =
        Protocol.CreateGameRequest(
          matchId = "m-9",
          gameName = "boxing",
          isPublic = isPublic,
          parameters = Map("rounds" -> "3"),
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("Red"),
              characterId = Some(101L),
              characterState = Some(Fighter.toState(average))
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Blue"),
              characterId = Some(202L),
              characterState = blue.map(Fighter.toState).orElse(Some(""))
            )
          ),
          moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/moves"),
          resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/results")
        )

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest(method = "GET", path = path, query = query))

    private def as(player: String) = Map("as" -> player)

    private def planning(
        routes: Routes,
        player: String,
        offense: Int,
        defense: Int,
        power: Int,
        matchId: String = "m-9"
    ) =
        routes(
          EngineRequest(
            method = "POST",
            path = s"/matches/$matchId/moves",
            query = as(player),
            body = write(Protocol.PlanRequest(offense = offense, defense = defense, power = power))
          )
        )

    test("GET /matches/:id/status answers matchmaker's status call, with both corners pending") {
        val (routes, _, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes = routes, path = "/matches/m-9/status").body)
        assertEquals(status.isCompleted, false)
        assertEquals(status.participants.filter(_.isPending).map(_.participantId), List(1L, 2L))
    }

    test("the play page tells a corner whose fighter is built which round to plan") {
        val (routes, _, _, _) = fixture()
        assert(get(routes = routes, path = "/matches/m-9/play", query = as("sub-alice")).body.contains("plan round 1"))
    }

    // Building a fighter is not part of a bout, so there is nowhere in one to build it.
    test("a bout has no route for building a fighter") {
        val (routes, _, _, _) = fixture()
        val body = """{"strength":5,"speed":5,"agility":5,"workrate":5,"chin":5}"""
        assertEquals(
          routes(
            EngineRequest(method = "POST", path = "/matches/m-9/fighter", query = as("sub-alice"), body = body)
          ).status,
          404
        )
    }

    test("a bout between characters that are not built fighters is refused at creation") {
        val (_, store, created, _) = fixture(blue = None)
        assertEquals(created.status, 400)
        assertEquals(store.get("m-9"), None)
    }

    test("a plan posted by a player is recorded and answered with the new state") {
        val (routes, store, _, _) = fixture()

        val answer = planning(routes = routes, player = "sub-alice", offense = 3, defense = 1, power = 1)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.you, Some("Red"))
        assertEquals(state.yourPlan, Some(Protocol.PlanRequest(offense = 3, defense = 1, power = 1)))
        assertEquals(state.waitingFor, List("Blue"))
        assertEquals(
          store.get("m-9").get.plans.map(_.allocation),
          List(Allocation(offense = 3, defense = 1, power = 1))
        )
    }

    test("a refused plan answers 400 with the reason and leaves the bout alone") {
        val (routes, store, _, _) = fixture()

        val over = planning(routes = routes, player = "sub-alice", offense = 3, defense = 3, power = 3)
        assertEquals(over.status, 400)
        assertEquals(
          ujson.read(over.body)("error").str,
          "a round is planned with exactly your workrate of 5; these add up to 9"
        )
        assertEquals(planning(routes = routes, player = "sub-alice", offense = 5, defense = 0, power = 0).status, 200)
        assertEquals(planning(routes = routes, player = "sub-alice", offense = 0, defense = 5, power = 0).status, 400)
        assertEquals(store.get("m-9").get.plans.size, 1)
    }

    test("a plan whose parts overflow an Int is a 400 over the wire, and changes nothing") {
        val (routes, store, _, _) = fixture()
        assertEquals(
          planning(
            routes = routes,
            player = "sub-alice",
            offense = Int.MaxValue,
            defense = Int.MaxValue,
            power = 7
          ).status,
          400
        )
        assertEquals(store.get("m-9").get.plans, Nil)
    }

    test("the second plan resolves the round over the wire, and tells both totals but only the mover's own plan") {
        val (routes, _, _, _) = fixture()
        planning(routes = routes, player = "sub-alice", offense = 5, defense = 0, power = 0)

        val state = read[Protocol.StateResponse](
          planning(routes = routes, player = "sub-bob", offense = 0, defense = 5, power = 0).body
        )
        assertEquals(state.round, 2)
        assertEquals(state.yourPlan, None)
        val round = state.rounds.head
        assertEquals((round.red, round.blue), (None, Some(Protocol.PlanRequest(offense = 0, defense = 5, power = 0))))
        assertEquals(
          (round.redNumbers, round.blueNumbers),
          (
            Protocol.Numbers(offense = 15, defense = 10, power = 10),
            Protocol.Numbers(offense = 10, defense = 15, power = 10)
          )
        )
        assertEquals((round.redPoints, round.bluePoints), (Some(10), Some(9)))
        assertEquals(round.decision, "outworked")
    }

    test("the public board shows that a corner has planned, and not what") {
        val (routes, _, _, _) = fixture(isPublic = true)
        planning(routes = routes, player = "sub-alice", offense = 4, defense = 0, power = 1)

        val watching = read[Protocol.StateResponse](get(routes = routes, path = "/matches/m-9/board/state").body)
        assertEquals(watching.you, None)
        assertEquals(watching.corners.map(_.isPlanned), List(true, false))
        assertEquals(watching.yourPlan, None)
        assert(!get(routes = routes, path = "/matches/m-9/board").body.contains("\"offense\":4"))
        assertEquals(get(routes = routes, path = "/matches/m-9/board").status, 200)
    }
}
