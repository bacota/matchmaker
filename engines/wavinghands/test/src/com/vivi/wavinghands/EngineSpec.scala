package com.vivi.wavinghands

import java.time.Instant
import munit.FunSuite
import com.vivi.engine.{InMemoryMatchStore, QuietTests, RecordingMatchmaker, Refusal}
import Protocol.MoveRequest

/** Whole duels played through the engine: what each wizard is shown, and what matchmaker is told. */
class EngineSpec extends FunSuite with QuietTests {

    private val created = Instant.parse("2026-01-01T00:00:00Z")
    private val results = "http://matchmaker.test/results"

    private def request(n: Int, canTie: Option[Boolean] = None) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "waving hands",
          isPublic = true,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = Some(600),
          players = (1 to n).toList.map(i =>
              Protocol.EnginePlayer(
                cognitoId = s"sub-$i",
                participantId = i * 10L,
                role = None,
                characterId = None,
                characterState = None,
                nickname = Some(List("Ann", "Bob", "Cat")(i - 1))
              )
          ),
          moveCallbackUrl = Some("http://matchmaker.test/moves"),
          resultsCallbackUrl = Some(results),
          canTie = canTie
        )

    private def fixture(n: Int = 2) = {
        val store = InMemoryMatchStore[Duel]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store = store, matchmaker = recorder, baseUrl = "http://engine.test", now = () => created)
        assert(engine.createGame(request(n)).isRight)
        (engine, recorder, store)
    }

    private def order(engine: Engine, who: Int, left: String, right: String, f: MoveRequest => MoveRequest = identity) =
        engine.move("m-1", s"sub-$who", f(MoveRequest(left = left, right = right)))

    test("a duel needs two wizards, each a different player") {
        val engine = Engine(
          store = InMemoryMatchStore[Duel](),
          matchmaker = RecordingMatchmaker(),
          baseUrl = "http://engine.test"
        )
        assert(engine.createGame(request(1)).isLeft)
        val twice = request(2)
        assert(engine.createGame(twice.copy(players = twice.players.map(_.copy(cognitoId = "sub-1")))).isLeft)
    }

    test("every wizard is pending at once, and a turn resolves on the last orders") {
        val (engine, recorder, store) = fixture(3)
        assertEquals(store.get("m-1").get.pending.map(_.participantId), List(10L, 20L, 30L))
        assert(order(engine, 2, "S", "-").isRight)
        assert(order(engine, 1, "W", "-").isRight)
        val half = store.get("m-1").get
        assertEquals(half.battle.turnsPlayed, 0)
        assertEquals(half.pending.map(_.participantId), List(30L))
        assert(order(engine, 3, "-", "-").isRight)
        val played = store.get("m-1").get
        assertEquals(played.battle.turnsPlayed, 1)
        assertEquals(played.pending.size, 3)
        assertEquals(recorder.moves.size, 3)
        // The last orders of a turn start everyone's clock again.
        assertEquals(recorder.moves.last._2.next.toSet, Set(10L, 20L, 30L))
    }

    test("orders given for the turn being played are hidden; only that they were given is shown") {
        val (engine, _, store) = fixture()
        assert(order(engine, 1, "S", "P").isRight)
        val m = store.get("m-1").get
        val seen = engine.stateOf(m, m.wizard(20L))
        assertEquals(seen.wizards.find(_.participantId == 10L).map(w => (w.hasOrdered, w.left)), Some((true, Nil)))
        assertEquals(seen.yourTurn.map(_.isOrdered), Some(false))
    }

    test("orders are given once a turn, with one knife, by a wizard still fighting") {
        val (engine, _, _) = fixture()
        assert(order(engine, 1, "S", "-").isRight)
        assertEquals(
          order(engine, 1, "D", "-").left.toOption,
          Some(Refusal.Invalid("you have already given your orders for this turn"))
        )
        assertEquals(
          order(engine, 2, "stab", "stab").left.toOption,
          Some(Refusal.Invalid("a wizard has only one knife, and cannot stab with both hands"))
        )
        assert(order(engine, 2, "x", "-").isLeft)
    }

    test("the offered castings match what each pair of gestures completes") {
        val (engine, _, store) = fixture()
        assert(order(engine, 1, "S", "-").isRight)
        assert(order(engine, 2, "-", "-").isRight)
        val m = store.get("m-1").get
        val yours = engine.stateOf(m, m.wizard(10L)).yourTurn.get
        assertEquals(yours.castings("D,-").flatMap(_.spells.map(_.spell)), List("missile"))
        assertEquals(yours.castings("P,P").flatMap(_.spells.map(_.spell)), List("shield", "shield"))
        assert(!yours.castings.contains("stab,stab"))
    }

    test("castings are offered with the one cast by default first, as the play page assumes") {
        val (engine, _, store) = fixture()
        Seq(("W", "-"), ("F", "-")).foreach { (l, r) =>
            assert(order(engine, 1, l, r).isRight)
            assert(order(engine, 2, "-", "-").isRight)
        }
        val m = store.get("m-1").get
        // W-F-P is cause light wounds, and the P alone a shield: the longer is the default.
        val offered = engine.stateOf(m, m.wizard(10L)).yourTurn.get.castings("P,-")
        assertEquals(offered.map(_.spells.map(_.spell)), List(List("cause-light-wounds"), List("shield")))
    }

    test("a surrender ends a duel of two; matchmaker gets the results, winner first") {
        val (engine, recorder, store) = fixture()
        assert(order(engine, 1, "P", "P").isRight)
        val applied = order(engine, 2, "-", "-").toOption.get
        assert(applied.isFinished)
        assert(store.get("m-1").get.isCompleted)
        val sent = recorder.results.map(_._2).head
        assertEquals(sent.results.map(r => r.participantId -> r.rank).toMap, Map(10L -> 2, 20L -> 1))
        assertEquals(sent.results.find(_.isWinner).map(_.participantId), Some(20L))
        assert(sent.summary.exists(_.contains("Bob")), sent.summary)
    }

    test("with three wizards the losers are ranked by how long they lasted") {
        val (engine, recorder, _) = fixture(3)
        assert(order(engine, 1, "P", "P").isRight)
        assert(order(engine, 2, "-", "-").isRight)
        assert(order(engine, 3, "-", "-").isRight)
        // Ann is out: only Bob and Cat give orders now.
        assert(order(engine, 1, "-", "-").isLeft)
        assert(order(engine, 2, "P", "P").isRight)
        assert(order(engine, 3, "-", "-").isRight)
        val sent = recorder.results.map(_._2).head
        assertEquals(sent.results.map(r => r.participantId -> r.rank).toMap, Map(30L -> 1, 20L -> 2, 10L -> 3))
    }
}
