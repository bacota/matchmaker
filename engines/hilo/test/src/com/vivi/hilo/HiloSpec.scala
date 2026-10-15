package com.vivi.hilo

import java.time.Instant
import munit.FunSuite
import com.vivi.engine.{InMemoryMatchStore, QuietTests, RecordingMatchmaker, Refusal}

/** The rules, and whole matches played through the engine: what each player is shown, and what matchmaker is told. */
class HiloSpec extends FunSuite with QuietTests {

    private val created = Instant.parse("2026-01-01T00:00:00Z")
    private val names = List("Ann", "Bob", "Cat", "Dan", "Eve")

    private def request(n: Int) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "hilo",
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
                nickname = Some(names(i - 1))
              )
          ),
          moveCallbackUrl = Some("http://matchmaker.test/moves"),
          resultsCallbackUrl = Some("http://matchmaker.test/results")
        )

    private def fixture(n: Int = 3) = {
        val store = InMemoryMatchStore[HiloMatch]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store = store, matchmaker = recorder, baseUrl = "http://engine.test", now = () => created)
        assert(engine.createGame(request(n)).isRight)
        (engine, recorder, store)
    }

    private def pick(engine: Engine, who: Int, number: Int) = engine.move("m-1", s"sub-$who", number)

    /** One round: each player's number, in seat order. */
    private def round(engine: Engine, numbers: Int*): Unit =
        numbers.zipWithIndex.foreach((n, i) => assert(pick(engine, i + 1, n).isRight, s"player ${i + 1} picking $n"))

    private def p(id: Long, n: Int) = Pick(participantId = id, number = n, takenAt = created, startedAt = created)

    // ---- the rules ---------------------------------------------------------------------------

    test("the highest number picked by one player alone wins, not the highest number") {
        assertEquals(Rules.winnerOf(List(p(1, 3), p(2, 3), p(3, 2), p(4, 1))).map(_.participantId), Some(3L))
        assertEquals(Rules.winnerOf(List(p(1, 1), p(2, 2))).map(_.participantId), Some(2L))
    }

    test("a round with every number picked twice or more has no winner") {
        assertEquals(Rules.winnerOf(List(p(1, 2), p(2, 2))), None)
        assertEquals(Rules.winnerOf(List(p(1, 1), p(2, 1), p(3, 3), p(4, 3))), None)
    }

    // ---- playing through the engine -------------------------------------------------------------

    test("hilo needs two players, each a different one") {
        val engine = Engine(store = InMemoryMatchStore[HiloMatch](), matchmaker = RecordingMatchmaker(), baseUrl = "x")
        assert(engine.createGame(request(1)).isLeft)
        val twice = request(2)
        assert(engine.createGame(twice.copy(players = twice.players.map(_.copy(cognitoId = "sub-1")))).isLeft)
    }

    test("everyone is pending at once, and a round resolves on the last pick") {
        val (engine, recorder, store) = fixture()
        assertEquals(store.get("m-1").get.pending.map(_.participantId), List(10L, 20L, 30L))
        assert(pick(engine, 2, 1).isRight)
        assertEquals(store.get("m-1").get.pending.map(_.participantId), List(10L, 30L))
        assert(pick(engine, 1, 2).isRight)
        assert(pick(engine, 3, 3).isRight)
        val m = store.get("m-1").get
        assert(m.isCompleted)
        assertEquals(m.winners.map(_.participantId), List(30L))
        assertEquals(recorder.moves.size, 3)
    }

    test("a number must be from 1 to the number of players, and is picked once a round") {
        val (engine, _, _) = fixture()
        assertEquals(pick(engine, 1, 0).left.toOption, Some(Refusal.Invalid("pick a number from 1 to 3")))
        assertEquals(pick(engine, 1, 4).left.toOption, Some(Refusal.Invalid("pick a number from 1 to 3")))
        assert(pick(engine, 1, 3).isRight)
        assertEquals(
          pick(engine, 1, 2).left.toOption,
          Some(Refusal.Invalid("you have already picked this round; a pick cannot be taken back"))
        )
    }

    test("the picks of the round being played are hidden, except the viewer's own") {
        val (engine, _, store) = fixture()
        assert(pick(engine, 1, 2).isRight)
        val m = store.get("m-1").get
        val bob = engine.stateOf(m, m.players.find(_.participantId == 20L))
        assertEquals(bob.yourPick, None)
        assertEquals(bob.players.map(_.hasPicked), List(true, false, false))
        assert(!upickle.default.write(bob).contains("\"number\""), upickle.default.write(bob))
        assertEquals(engine.stateOf(m, m.players.headOption).yourPick, Some(2))
    }

    test("a round nobody wins is played again, and is shown with every pick") {
        val (engine, recorder, store) = fixture()
        round(engine, 3, 3, 3)
        val again = store.get("m-1").get
        assert(!again.isOver)
        assertEquals(again.pending.size, 3)
        val seen = engine.stateOf(again, None)
        assertEquals(seen.round, 2)
        assertEquals(seen.rounds.map(r => (r.picks.map(_.number), r.winner)), List((List(3, 3, 3), None)))
        round(engine, 1, 1, 2)
        val won = store.get("m-1").get
        assert(won.isCompleted)
        assertEquals(won.winners.map(_.participantId), List(30L))
        val sent = recorder.results.map(_._2).head
        assertEquals(sent.results.map(r => r.participantId -> r.rank).toMap, Map(10L -> 2, 20L -> 2, 30L -> 1))
        assert(
          sent.summary.exists(s => s.contains("Cat") && s.contains("with 2") && s.contains("2 rounds")),
          sent.summary
        )
    }

    test("with five players the highest unique number wins even when a higher one is shared") {
        val (engine, recorder, store) = fixture(5)
        round(engine, 5, 5, 4, 1, 1)
        assertEquals(store.get("m-1").get.winners.map(_.participantId), List(30L))
        assertEquals(recorder.results.map(_._2).head.results.find(_.isWinner).map(_.participantId), Some(30L))
    }
}
