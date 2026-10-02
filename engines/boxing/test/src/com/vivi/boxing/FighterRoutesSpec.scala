package com.vivi.boxing

import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{EngineRequest, InMemoryMatchStore, MatchmakerRefusal, PlayAuth, RecordingMatchmaker}
import Protocol.given

/** Building a fighter: made here, checked against the rules, and reported to matchmaker as a character — which is how a
  * fighter comes to exist at all, since matchmaker no longer makes characters itself.
  */
class FighterRoutesSpec extends FunSuite {

    private val matchmakerUrl = "http://matchmaker.test"

    /** Trusted play auth, the local zero-setup mode: a caller names themselves with `?as=`. */
    private def fixture(
        recorder: RecordingMatchmaker = RecordingMatchmaker(),
        url: Option[String] = Some(matchmakerUrl)
    ) = {
        val engine = Engine(InMemoryMatchStore[Bout](), recorder, "http://engine.test", matchmakerUrl = url)
        (Routes(engine, PlayAuth.Trusted, None), recorder)
    }

    private def build(routes: Routes, body: Protocol.BuildRequest, as: Option[String] = Some("sub-alice")) =
        routes(EngineRequest("POST", "/fighters", as.map("as" -> _).toMap, write(body)))

    private val slugger = Protocol.BuildRequest("Iron Mike", "hits hard", 8, 4, 3, 5, 5)

    test("a fighter built by a signed-in player is registered with matchmaker as theirs, and answered with its id") {
        val (routes, recorder) = fixture()
        val response = build(routes, slugger)

        assertEquals(response.status, 201)
        val built = read[Protocol.BuiltFighter](response.body)
        assertEquals(built.name, "Iron Mike")
        assertEquals(built.fighter, Protocol.FighterView(8, 4, 3, 5, 5))

        val List((id, registered)) = recorder.registrations: @unchecked
        assertEquals(built.characterId, id)
        assertEquals(registered.ownerExternalId, "sub-alice")
        assertEquals((registered.name, registered.description), ("Iron Mike", "hits hard"))
        assertEquals(Fighter.fromState(registered.state), Some(Fighter(8, 4, 3, 5, 5)))
    }

    // The point of the state: matchmaker hands it back when it seats the character, and a bout
    // must accept what the build page made.
    test("the state a fighter is registered with is a fighter a bout will seat") {
        val (routes, recorder) = fixture()
        build(routes, slugger)
        val List((_, registered)) = recorder.registrations: @unchecked
        val state = registered.state
        val bout = Bout.create(
          Protocol.CreateGameRequest(
            matchId = "m-1",
            gameName = "boxing",
            isPublic = false,
            parameters = Map.empty,
            settings = "{}",
            timeLimitSeconds = None,
            players = List(
              Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), Some(1L), Some(state)),
              Protocol
                  .EnginePlayer("sub-bob", 2L, Some("Blue"), Some(2L), Some(Fighter.toState(Fighter(5, 5, 5, 5, 5))))
            ),
            moveCallbackUrl = None,
            resultsCallbackUrl = None
          ),
          java.time.Instant.EPOCH
        )
        assertEquals(bout.map(_.corners.head.fighter), Right(Some(Fighter(8, 4, 3, 5, 5))))
    }

    test("a fighter that breaks the build rules is refused and nothing is registered") {
        val (routes, recorder) = fixture()
        assertEquals(build(routes, slugger.copy(chin = 6)).status, 400) // 26 points
        assertEquals(build(routes, slugger.copy(strength = 11, speed = 1)).status, 400) // over the maximum
        assertEquals(recorder.registrations, Nil)
    }

    test("a fighter needs a name") {
        val (routes, recorder) = fixture()
        val response = build(routes, slugger.copy(name = "   "))
        assertEquals(response.status, 400)
        assertEquals(recorder.registrations, Nil)
    }

    test("nobody signed in cannot build a fighter") {
        val (routes, recorder) = fixture()
        assertEquals(build(routes, slugger, as = None).status, 401)
        assertEquals(recorder.registrations, Nil)
    }

    test("an engine with no matchmaker to register with refuses the build rather than making a fighter nobody has") {
        val (routes, recorder) = fixture(url = None)
        assertEquals(build(routes, slugger).status, 502)
        assertEquals(recorder.registrations, Nil)
    }

    test("a matchmaker that cannot be reached is a 502 the player can try again after") {
        val recorder = RecordingMatchmaker()
        recorder.failRegistrations = true
        val (routes, _) = fixture(recorder)
        assertEquals(build(routes, slugger).status, 502)
    }

    test("a player matchmaker does not know is told to register with matchmaker first") {
        val unknown = new RecordingMatchmaker() {
            override def registerCharacter(url: String, request: Protocol.RegisterCharacterRequest): Long =
                throw MatchmakerRefusal(404, s"no player with externalId '${request.ownerExternalId}'")
        }
        val (routes, _) = fixture(unknown)
        val response = build(routes, slugger)
        assertEquals(response.status, 400)
        assert(response.body.contains("sign in to matchmaker"), response.body)
    }

    test("the build page is served to anyone, with the rules it builds to") {
        val (routes, _) = fixture()
        val page = routes(EngineRequest("GET", "/fighters/new"))
        assertEquals(page.status, 200)
        assert(page.body.contains("Spread 25 points"), page.body.take(200))
        assert(page.body.contains("""<label for="f-name">"""))
    }
}
