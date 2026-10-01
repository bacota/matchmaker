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

    private val average = Fighter(5, 5, 5, 5, 5)

    /** Trusted play auth, the local zero-setup mode: a caller names themselves with `?as=`. */
    private def fixture(isPublic: Boolean = true, blue: Option[Fighter] = Some(average)) = {
        val store = InMemoryMatchStore[Bout]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test")
        val routes = Routes(engine, PlayAuth.Trusted, None)
        val created = routes(EngineRequest("POST", "/games", Map.empty, write(createRequest(isPublic, blue = blue))))
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
            Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), Some(101L), Some(Fighter.toState(average))),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Blue"), Some(202L), blue.map(Fighter.toState).orElse(Some("")))
          ),
          moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/moves"),
          resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-9/results")
        )

    private def get(routes: Routes, path: String, query: Map[String, String] = Map.empty) =
        routes(EngineRequest("GET", path, query))

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
            "POST",
            s"/matches/$matchId/moves",
            as(player),
            write(Protocol.PlanRequest(offense, defense, power))
          )
        )

    private def building(routes: Routes, player: String, f: Fighter) =
        routes(
          EngineRequest(
            "POST",
            "/matches/m-9/fighter",
            as(player),
            write(Protocol.BuildRequest(f.strength, f.speed, f.agility, f.workrate, f.chin))
          )
        )

    test("GET /matches/:id/status answers matchmaker's status call, with both corners pending") {
        val (routes, _, _, _) = fixture()
        val status = read[Protocol.GameStatusResponse](get(routes, "/matches/m-9/status").body)
        assertEquals(status.completed, false)
        assertEquals(status.participants.filter(_.pending).map(_.participantId), List(1L, 2L))
    }

    test("the play page tells a corner whose fighter is built which round to plan") {
        val (routes, _, _, _) = fixture()
        assert(get(routes, "/matches/m-9/play", as("sub-alice")).body.contains("plan round 1"))
    }

    test("a caller who is somebody, but not in this bout, cannot build a fighter in it") {
        val (routes, _, _, _) = fixture()
        assertEquals(building(routes, "sub-carol", average).status, 403)
    }

    test("a plan posted by a player is recorded and answered with the new state") {
        val (routes, store, _, _) = fixture()

        val answer = planning(routes, "sub-alice", 3, 1, 1)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(state.you, Some("Red"))
        assertEquals(state.yourPlan, Some(Protocol.PlanRequest(3, 1, 1)))
        assertEquals(state.waitingFor, List("Blue"))
        assertEquals(store.get("m-9").get.plans.map(_.allocation), List(Allocation(3, 1, 1)))
    }

    test("a refused plan answers 400 with the reason and leaves the bout alone") {
        val (routes, store, _, _) = fixture()

        val over = planning(routes, "sub-alice", 3, 3, 3)
        assertEquals(over.status, 400)
        assertEquals(
          ujson.read(over.body)("error").str,
          "a round is planned with exactly your workrate of 5; these add up to 9"
        )
        assertEquals(planning(routes, "sub-alice", 5, 0, 0).status, 200)
        assertEquals(planning(routes, "sub-alice", 0, 5, 0).status, 400)
        assertEquals(store.get("m-9").get.plans.size, 1)
    }

    test("a plan whose parts overflow an Int is a 400 over the wire, and changes nothing") {
        val (routes, store, _, _) = fixture()
        assertEquals(planning(routes, "sub-alice", Int.MaxValue, Int.MaxValue, 7).status, 400)
        assertEquals(store.get("m-9").get.plans, Nil)
    }

    test("a fighter is built over the wire, and saved to matchmaker") {
        val (routes, store, _, recorder) = fixture(blue = None)

        val page = get(routes, "/matches/m-9/play", as("sub-bob"))
        assert(page.body.contains("build your fighter"))

        val built = Fighter(3, 6, 6, 6, 4)
        val answer = building(routes, "sub-bob", built)
        assertEquals(answer.status, 200)
        val state = read[Protocol.StateResponse](answer.body)
        assertEquals(
          state.corners.find(_.side == "Blue").flatMap(_.fighter),
          Some(Protocol.BuildRequest(3, 6, 6, 6, 4))
        )
        assertEquals(recorder.characterStates, List(202L -> Fighter.toState(built)))
        assertEquals(store.get("m-9").get.cornerOf(Side.Blue).flatMap(_.fighter), Some(built))

        assertEquals(building(routes, "sub-bob", built).status, 400)
    }

    test("a build matchmaker could not save is a 502, which the player may retry") {
        val (routes, _, _, recorder) = fixture(blue = None)
        recorder.failStateSaves = true
        assertEquals(building(routes, "sub-bob", average).status, 502)
        recorder.failStateSaves = false
        assertEquals(building(routes, "sub-bob", average).status, 200)
    }

    test("the second plan resolves the round over the wire, and both plans are then told") {
        val (routes, _, _, _) = fixture()
        planning(routes, "sub-alice", 5, 0, 0)

        val state = read[Protocol.StateResponse](planning(routes, "sub-bob", 0, 5, 0).body)
        assertEquals(state.round, 2)
        assertEquals(state.yourPlan, None)
        val round = state.rounds.head
        assertEquals((round.red, round.blue), (Protocol.PlanRequest(5, 0, 0), Protocol.PlanRequest(0, 5, 0)))
        assertEquals((round.redPoints, round.bluePoints), (Some(10), Some(9)))
        assertEquals(round.decision, "outworked")
    }

    test("the public board shows that a corner has planned, and not what") {
        val (routes, _, _, _) = fixture(isPublic = true)
        planning(routes, "sub-alice", 4, 0, 1)

        val watching = read[Protocol.StateResponse](get(routes, "/matches/m-9/board/state").body)
        assertEquals(watching.you, None)
        assertEquals(watching.corners.map(_.planned), List(true, false))
        assertEquals(watching.yourPlan, None)
        assert(!get(routes, "/matches/m-9/board").body.contains("\"offense\":4"))
        assertEquals(get(routes, "/matches/m-9/board").status, 200)
    }
}
