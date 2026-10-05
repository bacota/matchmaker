package com.vivi.boxing

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import com.vivi.engine.QuietTests
import com.vivi.engine.{InMemoryMatchStore, RecordingMatchmaker, Refusal}

/** Fights whole bouts through the engine, checking both what a player is told and what matchmaker is told.
  *
  * Two fighters do most of the work. [[average]] is five across the board, so its workrate is 5 and every one of its
  * numbers is its plan plus ten. [[slugger]] is all strength and workrate and no chin, and knocks an average fighter
  * out in one round with everything on power.
  */
class EngineSpec extends FunSuite with QuietTests {

    private val moveUrl = "http://matchmaker.test/games/1/matches/m-1/moves"
    private val resultsUrl = "http://matchmaker.test/games/1/matches/m-1/results"
    private val created = Instant.parse("2026-01-01T00:00:00Z")

    private val average = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)
    private val slugger = Fighter(strength = 10, speed = 1, agility = 1, workrate = 10, chin = 3)

    private val alice = "sub-alice"
    private val bob = "sub-bob"

    private def createRequest(
        red: Option[Fighter] = Some(average),
        blue: Option[Fighter] = Some(average),
        roles: List[Option[String]] = List(Some("Red"), Some("Blue")),
        parameters: Map[String, String] = Map("rounds" -> "3"),
        settings: String = "{}",
        isPublic: Boolean = false
    ) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "boxing",
          isPublic = isPublic,
          parameters = parameters,
          settings = settings,
          timeLimitSeconds = Some(600),
          players = List(
            Protocol.EnginePlayer(
              alice,
              11L,
              roles.head,
              Some(101L),
              Some(red.map(Fighter.toState).getOrElse("")),
              Some("Alice"),
              Some("Iron Mike")
            ),
            Protocol.EnginePlayer(
              bob,
              22L,
              roles(1),
              Some(202L),
              Some(blue.map(Fighter.toState).getOrElse("")),
              Some("Bob"),
              Some("Sugar Ray")
            )
          ),
          moveCallbackUrl = Some(moveUrl),
          resultsCallbackUrl = Some(resultsUrl)
        )

    /** A clock the test moves by hand, so that when a round began and when a plan was made are both known. */
    private class Clock(start: Instant) {
        private val at = AtomicReference(start)
        def now: Instant = at.get
        def advance(seconds: Long): Instant = at.updateAndGet(_.plusSeconds(seconds))
    }

    private def fixture(request: Protocol.CreateGameRequest = createRequest()) = {
        val store = InMemoryMatchStore[Bout]()
        val recorder = RecordingMatchmaker()
        val clock = Clock(created)
        val engine = Engine(store, recorder, "http://engine.test", () => clock.now)
        val response = engine.createGame(request)
        (engine, recorder, store, clock, response)
    }

    private def bout(store: InMemoryMatchStore[Bout]) = store.get("m-1").get

    // ---------------------------------------------------------------------------
    // Creating a bout
    // ---------------------------------------------------------------------------

    test("creating a bout seats both fighters in the corners their roles name, and hands back the urls") {
        val (_, _, store, _, response) =
            fixture(createRequest(roles = List(Some("Blue"), Some("Red")), isPublic = true))
        val created = response.toOption.get

        assertEquals(created.statusUrl, "http://engine.test/matches/m-1/status")
        assertEquals(created.playUrl, "http://engine.test/matches/m-1/play")
        assertEquals(created.publicUrl, Some("http://engine.test/matches/m-1/board"))
        assertEquals(bout(store).cornerOf(Side.Blue).map(_.participantId), Some(11L))
        assertEquals(bout(store).cornerOf(Side.Red).map(_.characterId), Some(202L))
    }

    test("each corner carries its player's nickname, as matchmaker sent it, for the page to show") {
        val (engine, _, store, _, _) = fixture()
        val corners = engine.stateOf(bout(store), None).corners

        assertEquals(corners.map(c => c.side -> c.nickname), List("Red" -> Some("Alice"), "Blue" -> Some("Bob")))
    }

    test("each corner carries its fighter's name, as matchmaker sent it, for the page to show to both players") {
        val (engine, _, store, _, _) = fixture()
        // Seen from either corner, and by a spectator: a name is not a characteristic, and is no secret.
        for (viewer <- None :: bout(store).corners.map(Some(_))) {
            val corners = engine.stateOf(bout(store), viewer).corners
            assertEquals(
              corners.map(c => c.side -> c.fighterName),
              List("Red" -> Some("Iron Mike"), "Blue" -> Some("Sugar Ray"))
            )
        }
    }

    test("the number of rounds comes from the challenge's settings, then the game's parameter, then the default") {
        def rounds(request: Protocol.CreateGameRequest) = Bout.scheduledRounds(request)

        assertEquals(rounds(createRequest(parameters = Map.empty)), Right(Bout.DefaultRounds))
        assertEquals(rounds(createRequest(parameters = Map("Rounds" -> "12"))), Right(12))
        assertEquals(rounds(createRequest(parameters = Map("rounds" -> "12"), settings = """{"rounds":4}""")), Right(4))
        // Settings that are not JSON are not the challenge saying anything about rounds.
        assertEquals(rounds(createRequest(parameters = Map("rounds" -> "6"), settings = "")), Right(6))
    }

    test("a bout scheduled for fewer than 3 or more than 25 rounds is refused") {
        val (_, _, _, _, tooShort) = fixture(createRequest(parameters = Map("rounds" -> "2")))
        assertEquals(tooShort, Left(Refusal.Invalid("a bout is scheduled for 3 to 25 rounds; '2' is not that")))
        val (_, _, _, _, tooLong) = fixture(createRequest(parameters = Map("rounds" -> "26")))
        assert(tooLong.isLeft)
        val (_, _, _, _, longest) = fixture(createRequest(parameters = Map("rounds" -> "25")))
        assert(longest.isRight)
    }

    test("a corner with no character is refused: boxing is a character game") {
        val plain = createRequest()
        val (_, _, _, _, response) = fixture(plain.copy(players = plain.players.map(_.copy(characterId = None))))
        assertEquals(
          response,
          Left(Refusal.Invalid("every corner needs a fighter; register boxing in matchmaker as a character game"))
        )
    }

    // ---------------------------------------------------------------------------
    // Fighters arrive built
    // ---------------------------------------------------------------------------

    test("both fighters arrive with the characteristics their characters were built with") {
        val (_, _, store, _, _) = fixture(createRequest(blue = Some(slugger)))
        assertEquals(bout(store).cornerOf(Side.Red).flatMap(_.fighter), Some(average))
        assertEquals(bout(store).cornerOf(Side.Blue).flatMap(_.fighter), Some(slugger))
    }

    test("a character that is not a built fighter refuses the bout: building one is not part of a bout") {
        val (_, recorder, store, _, unbuilt) = fixture(createRequest(blue = None))
        assertEquals(
          unbuilt,
          Left(Refusal.Invalid("every fighter must be built before a bout; character(s) 202 are not"))
        )
        assertEquals(store.get("m-1"), None)
        assertEquals(recorder.characterStates, Nil)

        // State this engine could not have built is not a fighter either.
        val (_, _, _, _, overBudget) = fixture(createRequest(red = Some(Fighter(10, 10, 10, 10, 10))))
        assertEquals(
          overBudget,
          Left(Refusal.Invalid("every fighter must be built before a bout; character(s) 101 are not"))
        )
    }

    // ---------------------------------------------------------------------------
    // Fighting rounds
    // ---------------------------------------------------------------------------

    test("both corners are pending from the start of every round, on a clock that started with the round") {
        val (engine, _, _, clock, _) = fixture()

        val opening = engine.status("m-1").toOption.get
        assertEquals(opening.participants.filter(_.pending).map(_.participantId), List(11L, 22L))
        assert(opening.participants.forall(_.prevMoveAt.contains(created)))

        clock.advance(60)
        engine.plan("m-1", alice, Allocation(5, 0, 0))
        val roundOver = clock.advance(30)
        engine.plan("m-1", bob, Allocation(0, 5, 0))

        val second = engine.status("m-1").toOption.get
        assertEquals(second.participants.filter(_.pending).map(_.participantId), List(11L, 22L))
        assert(second.participants.forall(_.prevMoveAt.contains(roundOver)))
    }

    test("the first plan of a round names nobody next; the second names both, since a new round starts") {
        val (engine, recorder, _, clock, _) = fixture()

        val first = clock.advance(60)
        engine.plan("m-1", bob, Allocation(0, 5, 0))
        val second = clock.advance(30)
        engine.plan("m-1", alice, Allocation(5, 0, 0))

        val notifications = recorder.moves.map(_._2)
        assertEquals(notifications.map(_.participantId), List(22L, 11L))
        assertEquals(notifications.map(_.next), List(Nil, List(11L, 22L)))
        assertEquals(notifications.map(_.takenAt), List(first, second))
        // Both clocks started with the round, however late either corner planned.
        assertEquals(notifications.map(_.startedAt), List(created, created))
        assertEquals(recorder.moves.map(_._1).distinct, List(moveUrl))
    }

    test("a corner cannot plan a round twice, and a plan must spend exactly the workrate") {
        val (engine, _, _, _, _) = fixture()

        assertEquals(
          engine.plan("m-1", alice, Allocation(2, 2, 2)),
          Left(Refusal.Invalid("a round is planned with exactly your workrate of 5; these add up to 6"))
        )
        assert(engine.plan("m-1", alice, Allocation(5, 0, 0)).isRight)
        assertEquals(
          engine.plan("m-1", alice, Allocation(0, 0, 5)),
          Left(Refusal.Invalid("you have already planned round 1; a plan cannot be changed"))
        )
    }

    test("neither corner, nor the public, sees a plan until the round resolves") {
        val (engine, _, store, _, _) = fixture(createRequest(isPublic = true))
        engine.plan("m-1", alice, Allocation(5, 0, 0))

        val m = bout(store)
        val asAlice = engine.stateOf(m, m.cornerOf(Side.Red))
        val asBob = engine.stateOf(m, m.cornerOf(Side.Blue))
        val asPublic = engine.stateOf(m, None)

        assertEquals(asAlice.yourPlan, Some(Protocol.PlanRequest(5, 0, 0)))
        assertEquals(asBob.yourPlan, None)
        assertEquals(asPublic.yourPlan, None)
        // What everyone may know is that Red has planned.
        assertEquals(asBob.corners.map(_.planned), List(true, false))
        assertEquals(asBob.waitingFor, List("Blue"))
        assert(List(asAlice, asBob, asPublic).forall(_.rounds.isEmpty))
        assert(!upickle.default.write(asBob)(using Protocol.given_ReadWriter_StateResponse).contains("\"offense\":5"))

        engine.plan("m-1", bob, Allocation(0, 5, 0))
        val resolved = engine.stateOf(bout(store), None)
        assertEquals(resolved.round, 2)
    }

    test("once a round resolves, its totals are everyone's but each corner sees only its own tactics") {
        val (engine, _, store, _, _) = fixture(createRequest(blue = Some(Fighter(8, 4, 3, 5, 5)), isPublic = true))
        // All three rounds, so what is checked is a finished bout: finishing it gives nothing more away.
        (1 to 3).foreach { _ =>
            engine.plan("m-1", alice, Allocation(5, 0, 0))
            engine.plan("m-1", bob, Allocation(0, 2, 3))
        }

        val m = bout(store)
        assert(m.isOver)
        val views = List(m.cornerOf(Side.Red), m.cornerOf(Side.Blue), None).map(engine.stateOf(m, _))
        // Alice is five across the board; Bob's speed 4, agility 3 and strength 8 are doubled onto his plan.
        views.foreach(v =>
            assertEquals(
              v.rounds.map(r => (r.redNumbers, r.blueNumbers)),
              List.fill(3)(Protocol.Numbers(15, 10, 10) -> Protocol.Numbers(8, 8, 19))
            )
        )
        val List(asAlice, asBob, asPublic) = views: @unchecked
        assertEquals(
          asAlice.rounds.map(r => (r.red, r.blue)),
          List.fill(3)(Some(Protocol.PlanRequest(5, 0, 0)) -> None)
        )
        assertEquals(asBob.rounds.map(r => (r.red, r.blue)), List.fill(3)(None -> Some(Protocol.PlanRequest(0, 2, 3))))
        assertEquals(asPublic.rounds.map(r => (r.red, r.blue)), List.fill(3)(None -> None))
        // Bob's plan is nowhere in what Alice is sent.
        assert(!upickle.default.write(asAlice)(using Protocol.given_ReadWriter_StateResponse).contains("\"power\":3"))
    }

    // ---------------------------------------------------------------------------
    // What a corner can see of the other
    // ---------------------------------------------------------------------------

    test("an opponent is seen by the words for their highest and lowest characteristics, never by the numbers") {
        // Strength alone is highest, agility alone lowest.
        assertEquals(Fighter(8, 4, 3, 5, 5).impression, List("powerful", "short"))
        // Strength alone is lowest of the four; chin's equal value plays no part.
        assertEquals(Fighter(3, 5, 8, 6, 3).impression, List("tall", "not very muscular"))
        assertEquals(Fighter(4, 3, 4, 9, 5).impression, List("fit", "sluggish"))
        assertEquals(Fighter(6, 6, 6, 2, 5).impression, List("flabby"))
    }

    test("two characteristics tied at an end are both described; three or more, and that end says nothing") {
        assertEquals(Fighter(7, 7, 3, 4, 4).impression, List("powerful", "fast", "short"))
        assertEquals(Fighter(2, 9, 2, 6, 6).impression, List("fast", "not very muscular", "short"))
        // Three share the lowest, so only the highest is described.
        assertEquals(Fighter(10, 3, 3, 3, 6).impression, List("powerful"))
        // Everything the same: nothing stands out at either end.
        assertEquals(Fighter(5, 5, 5, 5, 5).impression, Nil)
    }

    test("chin is left out: the highest and lowest are found among the other four") {
        // Chin is the highest of all five, and strength the highest of the four.
        assertEquals(Fighter(5, 4, 4, 2, 10).impression, List("powerful", "flabby"))
        // Chin is the lowest of all five, and workrate the lowest of the four.
        assertEquals(Fighter(9, 5, 5, 4, 2).impression, List("powerful", "flabby"))
        // Chin tied with speed at the top of all five does not make a tie among the four.
        assertEquals(Fighter(3, 8, 3, 3, 8).impression, List("fast"))
    }

    test("each corner sees its own fighter's numbers and only an impression of the other's; the public, neither") {
        val (engine, _, store, _, _) = fixture(createRequest(blue = Some(Fighter(8, 4, 3, 5, 5)), isPublic = true))
        engine.plan("m-1", alice, Allocation(5, 0, 0))
        engine.plan("m-1", bob, Allocation(0, 5, 0))

        val m = bout(store)
        val asAlice = engine.stateOf(m, m.cornerOf(Side.Red))
        val asBob = engine.stateOf(m, m.cornerOf(Side.Blue))
        val asPublic = engine.stateOf(m, None)

        assertEquals(asAlice.corners.map(c => (c.side, c.fighter.isDefined)), List("Red" -> true, "Blue" -> false))
        assertEquals(asAlice.corners.map(_.impression), List(Nil, List("powerful", "short")))
        assertEquals(asBob.corners.map(c => (c.side, c.fighter.isDefined)), List("Red" -> false, "Blue" -> true))
        // Alice's fighter is five across the board, so nothing about it stands out.
        assertEquals(asBob.corners.map(_.impression), List(Nil, Nil))
        assert(asPublic.corners.forall(_.fighter.isEmpty))
        assertEquals(asPublic.corners.map(_.impression), List(Nil, List("powerful", "short")))
    }

    test("a bout that goes the distance is won on points, and matchmaker is sent the results") {
        val (engine, recorder, store, _, _) = fixture()

        // Every round: Red all offense (15 against Blue's 10), Blue all defense. Nothing lands on
        // either side, so Red takes each round 10–9 on activity.
        (1 to 3).foreach { _ =>
            engine.plan("m-1", alice, Allocation(5, 0, 0))
            engine.plan("m-1", bob, Allocation(0, 5, 0))
        }

        val m = bout(store)
        assert(m.isOver)
        assert(m.completed)
        assertEquals(m.rounds.map(_.outcome.decision), List.fill(3)(Decision.Activity))
        assertEquals((m.points(Side.Red), m.points(Side.Blue)), (30, 27))
        assertEquals(m.winner.map(_.side), Some(Side.Red))

        val (url, results) = recorder.results.head
        assertEquals(url, resultsUrl)
        val red = results.results.find(_.participantId == 11L).get
        val blue = results.results.find(_.participantId == 22L).get
        assertEquals((red.rank, red.isWinner), (1, true))
        assertEquals((blue.rank, blue.isWinner), (2, false))
        assertEquals(red.scores("method").str, "points")
        assertEquals(red.scores("points").num, 30.0)
        assertEquals(blue.scores("points").num, 27.0)
        // The scheduled length is the game's `rounds` parameter, which matchmaker lists under the
        // match itself; the result says only how many were fought.
        assert(results.results.forall(!_.scores.contains("scheduledRounds")))
        // And the line matchmaker shows the finished bout with, by the fighters' names it sent.
        assertEquals(
          results.summary,
          Some("<strong>Iron Mike</strong> beat <strong>Sugar Ray</strong> on points, 30–27 after 3 rounds.")
        )
        // The last plan ends the bout, so it names nobody next.
        assertEquals(recorder.moves.last._2.next, Nil)

        assertEquals(
          engine.plan("m-1", alice, Allocation(5, 0, 0)),
          Left(Refusal.Invalid("this bout is already over"))
        )
    }

    test("a knockout ends the bout in the round it lands, whatever was scheduled") {
        val (engine, recorder, store, _, _) =
            fixture(createRequest(red = Some(slugger), parameters = Map("rounds" -> "12")))

        // Red's power is 10 + 20 = 30, over Blue's effective chin of 10 + 15 = 25.
        engine.plan("m-1", alice, Allocation(0, 0, 10))
        engine.plan("m-1", bob, Allocation(5, 0, 0))

        val m = bout(store)
        assert(m.isOver)
        assertEquals(m.rounds.size, 1)
        assertEquals(m.knockout.flatMap(_.outcome.winner), Some(Side.Red))
        assertEquals(engine.stateOf(m, None).method, Some("knockout"))

        val results = recorder.results.head._2.results
        assertEquals(results.find(_.isWinner).map(_.participantId), Some(11L))
        assert(results.forall(_.scores("method").str == "knockout"))
        assertEquals(
          recorder.results.head._2.summary,
          Some("<strong>Iron Mike</strong> knocked out <strong>Sugar Ray</strong> in round 1.")
        )
        assertEquals(engine.status("m-1").toOption.get.completed, true)
    }

    test("a bout level on points after the last round is a draw, and both corners rank first") {
        val (engine, recorder, store, _, _) = fixture()
        (1 to 3).foreach { _ =>
            engine.plan("m-1", alice, Allocation(2, 2, 1))
            engine.plan("m-1", bob, Allocation(2, 2, 1))
        }

        val m = bout(store)
        assert(m.isDraw)
        assertEquals((m.points(Side.Red), m.points(Side.Blue)), (30, 30))
        val results = recorder.results.head._2.results
        assertEquals(results.map(_.rank), List(1, 1))
        assert(results.forall(r => !r.isWinner && r.scores("outcome").str == "draw"))
        assertEquals(
          recorder.results.head._2.summary,
          Some("<strong>Iron Mike</strong> and <strong>Sugar Ray</strong> fought to a draw, 30–30 after 3 rounds.")
        )
    }

    test("a bout stored before fighters' names were kept is summed up by the players' nicknames") {
        val (engine, _, store, _, _) = fixture()
        (1 to 3).foreach { _ =>
            engine.plan("m-1", alice, Allocation(2, 2, 1))
            engine.plan("m-1", bob, Allocation(2, 2, 1))
        }
        val unnamed = bout(store).copy(corners = bout(store).corners.map(_.copy(fighterName = None)))
        assertEquals(
          Bout.summary(unnamed),
          Some("<strong>Alice</strong> and <strong>Bob</strong> fought to a draw, 30–30 after 3 rounds.")
        )
    }

    test("knockdowns are scored 10–8 and counted in the results") {
        val (engine, recorder, store, _, _) = fixture(createRequest(red = Some(slugger)))

        // Red's power 2 * 10 + 5 = 25 is over Blue's knockdown line of 10 + 5 = 15 but not its
        // effective chin of 25. Blue's power of 10 does beat Red's defense of 2 + 5 = 7, but a telling
        // blow is only looked at when there was no knockdown.
        (1 to 3).foreach { _ =>
            engine.plan("m-1", alice, Allocation(0, 5, 5))
            engine.plan("m-1", bob, Allocation(5, 0, 0))
        }

        val m = bout(store)
        assertEquals(m.rounds.map(_.outcome.decision), List.fill(3)(Decision.Knockdown))
        assertEquals((m.points(Side.Red), m.points(Side.Blue)), (30, 24))
        val red = recorder.results.head._2.results.find(_.participantId == 11L).get
        assertEquals(red.scores("knockdowns").num, 3.0)
    }

    test("status reports every plan after `since`, with when its round began") {
        val (engine, _, _, clock, _) = fixture()
        val a = clock.advance(10)
        engine.plan("m-1", alice, Allocation(5, 0, 0))
        val b = clock.advance(10)
        engine.plan("m-1", bob, Allocation(0, 5, 0))
        val c = clock.advance(10)
        engine.plan("m-1", bob, Allocation(0, 5, 0))

        val all = engine.status("m-1").toOption.get.turns
        assertEquals(
          all.map(t => (t.participantId, t.takenAt, t.startedAt)),
          List((11L, a, Some(created)), (22L, b, Some(created)), (22L, c, Some(b)))
        )
        assertEquals(engine.status("m-1", Some(b)).toOption.get.turns.map(_.takenAt), List(c))
    }

    test("every callback numbers its plan and states the whole of who is to plan now") {
        val (engine, recorder, _, clock, _) = fixture()
        engine.plan("m-1", alice, Allocation(5, 0, 0))
        val roundTwo = clock.advance(30)
        engine.plan("m-1", bob, Allocation(0, 5, 0))

        val states = recorder.moves.map(_._2.state.get)
        assertEquals(states.map(_.sequence), List(1L, 2L))
        // After Red's plan only Blue is to plan, since round one began; after Blue's, both are, since
        // round two did.
        assertEquals(states.head.pending, List(Protocol.PendingSeat(22L, created)))
        assertEquals(states(1).pending, List(Protocol.PendingSeat(11L, roundTwo), Protocol.PendingSeat(22L, roundTwo)))
        assertEquals(engine.status("m-1").toOption.get.sequence, Some(2L))
    }

    test("the plan that ends the bout leaves nobody to plan") {
        val (engine, recorder, _, _, _) = fixture(createRequest(red = Some(slugger)))
        engine.plan("m-1", alice, Allocation(0, 0, 10))
        engine.plan("m-1", bob, Allocation(5, 0, 0))
        assertEquals(recorder.moves.last._2.state.map(_.pending), Some(Nil))
    }

    test("a callback that fails does not fail the plan, which stands, and the results are still sent".tag(Quiet)) {
        val (engine, recorder, store, _, _) = fixture(createRequest(red = Some(slugger)))
        recorder.failCallbacks = true

        assert(engine.plan("m-1", alice, Allocation(0, 0, 10)).isRight)
        // The knockout: its move callback fails, and the results are attempted all the same.
        val finishing = engine.plan("m-1", bob, Allocation(5, 0, 0))
        assert(finishing.isRight, s"the plan was committed, so it must not be reported as failing: $finishing")
        assert(bout(store).isOver)
        assertEquals(recorder.moves.size, 2)
        assertEquals(recorder.results.size, 1)
    }

    test("the results carry every plan of the bout as a turn, with when its round began") {
        val (engine, recorder, _, clock, _) =
            fixture(createRequest(red = Some(slugger), parameters = Map("rounds" -> "3")))
        val first = clock.advance(10)
        engine.plan("m-1", alice, Allocation(0, 5, 5))
        val second = clock.advance(10)
        engine.plan("m-1", bob, Allocation(5, 0, 0))
        val third = clock.advance(10)
        engine.plan("m-1", bob, Allocation(5, 0, 0))
        val last = clock.advance(10)
        engine.plan("m-1", alice, Allocation(0, 0, 10))

        val turns = recorder.results.head._2.turns.get
        assertEquals(
          turns.map(t => (t.participantId, t.takenAt, t.startedAt)),
          List(
            (11L, first, Some(created)),
            (22L, second, Some(created)),
            (22L, third, Some(second)),
            (11L, last, Some(second))
          )
        )
    }
}
