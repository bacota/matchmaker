package com.vivi.rps

import java.time.Instant
import munit.FunSuite
import upickle.default.{read, write}
import Protocol.given

/** Plays whole matches through the engine, checking both what a player is told and what matchmaker is told — the
  * callbacks are half of the engine's job, and the only half matchmaker actually depends on.
  *
  * Most of what is checked here is the thing tic-tac-toe cannot check: that neither player waits for the other, that
  * the throws stay hidden until both are in, and that the match resolves on whichever throw happens to be second.
  */
class EngineSpec extends FunSuite {

    private val moveUrl = "http://matchmaker.test/games/1/matches/m-1/moves"
    private val resultsUrl = "http://matchmaker.test/games/1/matches/m-1/results"
    private val created = Instant.parse("2026-01-01T00:00:00Z")

    private def createRequest(isPublic: Boolean = false, roles: List[Option[String]] = List(None, None)) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "rock-paper-scissors",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = Some(600),
          players = List(
            Protocol.EnginePlayer("sub-alice", 11L, roles.head, None, None),
            Protocol.EnginePlayer("sub-bob", 22L, roles(1), None, None)
          ),
          moveCallbackUrl = Some(moveUrl),
          resultsCallbackUrl = Some(resultsUrl)
        )

    private def fixture(isPublic: Boolean = false, roles: List[Option[String]] = List(None, None)) = {
        val store = InMemoryMatchStore()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test", () => created)
        val response = engine.createGame(createRequest(isPublic, roles)).toOption.get
        (engine, recorder, store, response, store.get("m-1").get)
    }

    private val alice = "sub-alice"
    private val bob = "sub-bob"

    test("creating a game seats both players and hands back the urls matchmaker needs") {
        val (_, _, _, response, m) = fixture(isPublic = true)

        assertEquals(response.statusUrl, "http://engine.test/matches/m-1/status")
        // One url for both players: the engine works out whose seat it is from who signed in.
        assertEquals(response.playUrl, "http://engine.test/matches/m-1/play")
        assertEquals(response.publicUrl, Some("http://engine.test/matches/m-1/board"))
        assertEquals(m.seats.map(_.participantId), List(11L, 22L))
        assertEquals(m.seats.map(_.side), List(Side.One, Side.Two))
        assertEquals(m.seats.map(_.cognitoId), List(alice, bob))
    }

    test("a private game has no public url") {
        val (_, _, _, response, _) = fixture(isPublic = false)
        assertEquals(response.publicUrl, None)
    }

    test("the roles matchmaker sends decide which seat is which") {
        val (_, _, _, _, m) = fixture(roles = List(Some("Two"), Some("One")))
        assertEquals(m.seatOf(Side.Two).get.participantId, 11L)
        assertEquals(m.seatOf(Side.One).get.participantId, 22L)
    }

    test("a game for anything but two players is refused") {
        val engine = Engine(InMemoryMatchStore(), RecordingMatchmaker(), "http://engine.test")
        val solo = createRequest().copy(players = createRequest().players.take(1))
        assertEquals(
          engine.createGame(solo),
          Left(Refusal.Invalid("rock-paper-scissors is a two-player game; 1 player(s) were sent"))
        )
    }

    test("a match whose two seats are the same player is refused, since a seat is found by subject") {
        val engine = Engine(InMemoryMatchStore(), RecordingMatchmaker(), "http://engine.test")
        val both = createRequest()
        assertEquals(
          engine.createGame(both.copy(players = both.players.map(_.copy(cognitoId = alice)))),
          Left(Refusal.Invalid("the two seats must belong to two different players"))
        )
    }

    test("both seats are waiting from the moment the match exists; nobody moves first") {
        val (engine, _, _, _, m) = fixture()

        assertEquals(m.pending.map(_.participantId), List(11L, 22L))
        val opening = engine.status("m-1").toOption.get
        assertEquals(opening.participants.filter(_.pending).map(_.participantId), List(11L, 22L))
        assertEquals(opening.completed, false)
        // Both clocks start when the match was created, and neither waits on the other's move.
        assert(opening.participants.forall(_.prevMoveAt.contains(created)))
    }

    test("either player may throw first, and the match resolves on the second throw") {
        // Bob first here, Alice first in the fixture above: the engine has no opinion about order.
        val (engine, _, store, _, _) = fixture()

        assert(engine.move("m-1", bob, Shape.Paper).isRight)
        val half = store.get("m-1").get
        assert(!half.isOver)
        assertEquals(half.pending.map(_.participantId), List(11L))

        val applied = engine.move("m-1", alice, Shape.Scissors).toOption.get
        assert(applied.finished)
        assertEquals(applied.state.winner.map(_.participantId), Some(11L))
        assert(store.get("m-1").get.completed)
    }

    test("a player may not throw twice, and may not throw once the match is over") {
        val (engine, recorder, _, _, _) = fixture()

        assert(engine.move("m-1", alice, Shape.Rock).isRight)
        assertEquals(
          engine.move("m-1", alice, Shape.Paper),
          Left(Refusal.Invalid("you have already thrown; a throw cannot be taken back"))
        )
        assert(engine.move("m-1", bob, Shape.Rock).isRight)
        assertEquals(engine.move("m-1", bob, Shape.Paper), Left(Refusal.Invalid("this match is already over")))

        // Two throws landed; the two refusals were not moves and matchmaker heard nothing of them.
        assertEquals(recorder.moves.size, 2)
    }

    test("someone with no seat in the match may not throw in it") {
        val (engine, recorder, _, _, _) = fixture()
        assertEquals(
          engine.move("m-1", "sub-carol", Shape.Rock),
          Left(Refusal.NotYours("'sub-carol' has no seat in match 'm-1'"))
        )
        assert(engine.move("m-1", "", Shape.Rock).isLeft)
        assertEquals(recorder.moves, Nil)
    }

    test("neither player is shown the other's throw until both have thrown") {
        val (engine, _, store, _, m) = fixture(isPublic = true)
        engine.move("m-1", alice, Shape.Rock)

        val waiting = store.get("m-1").get
        val bobSeat = waiting.seatFor(bob).get
        val bobsView = engine.stateOf(waiting, Some(bobSeat))
        // Alice has thrown, and that is the whole of what Bob is told about it.
        assertEquals(bobsView.players.find(_.participantId == 11L).get.thrown, true)
        assertEquals(bobsView.players.find(_.participantId == 11L).get.shape, None)
        assertEquals(bobsView.yourThrow, None)
        assertEquals(bobsView.waitingFor, List("Two"))
        // Nor does a watcher see it, nor Alice herself see Bob's absence of one differently.
        assert(engine.stateOf(waiting, None).players.forall(_.shape.isEmpty))

        engine.move("m-1", bob, Shape.Paper)
        val over = engine.stateOf(store.get("m-1").get, Some(bobSeat))
        assertEquals(over.players.map(_.shape), List(Some("Rock"), Some("Paper")))
        assertEquals(over.yourThrow, Some("Paper"))
        assertEquals(over.winner, Some("Two"))
        assertEquals(over.waitingFor, Nil)
    }

    test("a player sees their own throw while waiting, so the page can show what they threw") {
        val (engine, _, store, _, _) = fixture()
        engine.move("m-1", alice, Shape.Scissors)
        val aliceSeat = store.get("m-1").get.seatFor(alice).get
        assertEquals(engine.stateOf(store.get("m-1").get, Some(aliceSeat)).yourThrow, Some("Scissors"))
    }

    test("every throw is reported to matchmaker, naming nobody as next") {
        val (engine, recorder, _, _, _) = fixture()

        assert(engine.move("m-1", alice, Shape.Rock).isRight)
        assertEquals(recorder.moves.size, 1)
        val (url, first) = recorder.moves.head
        assertEquals(url, moveUrl)
        assertEquals(first.participantId, 11L)
        // Bob has been pending since the match was created and stays pending by not being named —
        // naming him would restart his clock at Alice's throw.
        assertEquals(first.next, Nil)
        assertEquals(first.takenAt, created)
        assertEquals(first.startedAt, created)

        assert(engine.move("m-1", bob, Shape.Rock).isRight)
        assertEquals(recorder.moves.map(_._2.participantId), List(11L, 22L))
        assertEquals(recorder.moves.map(_._2.next), List(Nil, Nil))
        // Both clocks started when the match did, whichever of the two threw second.
        assertEquals(recorder.moves.map(_._2.startedAt), List(created, created))
    }

    test("the results follow the second throw, ranking the winner first") {
        val (engine, recorder, _, _, _) = fixture()

        engine.move("m-1", alice, Shape.Rock)
        assertEquals(recorder.results, Nil, "a half-thrown match has no result to report")
        engine.move("m-1", bob, Shape.Scissors)

        assertEquals(recorder.results.size, 1)
        val (url, results) = recorder.results.head
        assertEquals(url, resultsUrl)
        val byParticipant = results.results.map(r => r.participantId -> r).toMap
        assertEquals(byParticipant(11L).rank, 1)
        assert(byParticipant(11L).isWinner)
        assertEquals(byParticipant(11L).scores("outcome").str, "win")
        assertEquals(byParticipant(11L).scores("throw").str, "Rock")
        assertEquals(byParticipant(22L).rank, 2)
        assert(!byParticipant(22L).isWinner)
        assertEquals(byParticipant(22L).scores("outcome").str, "loss")
        assertEquals(byParticipant(22L).scores("throw").str, "Scissors")
    }

    test("the same throw twice is a draw: both first, neither a winner") {
        val (engine, recorder, store, _, _) = fixture()
        engine.move("m-1", alice, Shape.Paper)
        engine.move("m-1", bob, Shape.Paper)

        assert(store.get("m-1").get.isDraw)
        val results = recorder.results.head._2.results
        assert(results.forall(r => r.rank == 1 && !r.isWinner))
        assert(results.forall(_.scores("outcome").str == "draw"))
    }

    test("every pairing comes out the way the game says it does") {
        val expected = List(
          (Shape.Rock, Shape.Scissors, Some(11L)),
          (Shape.Scissors, Shape.Paper, Some(11L)),
          (Shape.Paper, Shape.Rock, Some(11L)),
          (Shape.Scissors, Shape.Rock, Some(22L)),
          (Shape.Paper, Shape.Scissors, Some(22L)),
          (Shape.Rock, Shape.Paper, Some(22L)),
          (Shape.Rock, Shape.Rock, None)
        )

        expected.foreach { (mine, theirs, winner) =>
            val store = InMemoryMatchStore()
            val engine = Engine(store, RecordingMatchmaker(), "http://engine.test", () => created)
            engine.createGame(createRequest())
            engine.move("m-1", alice, mine)
            engine.move("m-1", bob, theirs)
            assertEquals(store.get("m-1").get.winner.map(_.participantId), winner, s"$mine against $theirs")
        }
    }

    test("status reports both throws as turns, each charged from the match's own start") {
        // A clock that moves, unlike the fixture's: what is being checked here is the times on the
        // turns, and a frozen clock would make every one of them identical.
        val store = InMemoryMatchStore()
        var elapsed = 0L
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test", () => created.plusSeconds(elapsed))
        engine.createGame(createRequest())

        // Alice throws after ten seconds, Bob after a further eighty — and Bob is charged for all
        // ninety of his own, not for the eighty since Alice moved. Neither was waiting for anyone.
        elapsed = 10
        engine.move("m-1", alice, Shape.Rock)
        elapsed = 90
        engine.move("m-1", bob, Shape.Paper)

        val all = engine.status("m-1").toOption.get.turns
        assertEquals(all.map(_.participantId), List(11L, 22L))
        assertEquals(all.map(_.takenAt), List(created.plusSeconds(10), created.plusSeconds(90)))
        assertEquals(all.map(_.startedAt), List(Some(created), Some(created)))

        // `since` is exclusive: matchmaker is not sent the turn it named.
        val since = engine.status("m-1", Some(created.plusSeconds(10))).toOption.get
        assertEquals(since.turns.map(_.takenAt), List(created.plusSeconds(90)))
        assertEquals(engine.status("m-1", Some(created.plusSeconds(90))).toOption.get.turns, Nil)
    }

    test("status says the match is over, and every seat with it, once both have thrown") {
        val (engine, _, _, _, _) = fixture()
        engine.move("m-1", alice, Shape.Rock)

        val half = engine.status("m-1").toOption.get
        assertEquals(half.completed, false)
        assertEquals(half.participants.filter(_.pending).map(_.participantId), List(22L))

        engine.move("m-1", bob, Shape.Paper)
        val over = engine.status("m-1").toOption.get
        assert(over.completed)
        assert(over.participants.forall(p => p.completed && !p.pending))
    }

    test("an unknown match is a 404 to matchmaker and to a player alike") {
        val (engine, _, _, _, _) = fixture()
        assertEquals(engine.status("nope"), Left(Refusal.NotFound("no match 'nope'")))
        assertEquals(engine.move("nope", alice, Shape.Rock), Left(Refusal.NotFound("no match 'nope'")))
    }

    test("a match with no callback urls is still playable") {
        val store = InMemoryMatchStore()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test")
        engine.createGame(createRequest().copy(moveCallbackUrl = None, resultsCallbackUrl = None))

        assert(engine.move("m-1", alice, Shape.Rock).isRight)
        assert(engine.move("m-1", bob, Shape.Paper).isRight)
        assert(store.get("m-1").get.completed)
        assertEquals(recorder.moves, Nil)
        assertEquals(recorder.results, Nil)
    }

    test("two players throwing at once both land, which is the race this game is made of") {
        val (engine, recorder, store, _, _) = fixture()

        val both = List(alice, bob).map(player =>
            java.util.concurrent.CompletableFuture.supplyAsync(() => engine.move("m-1", player, Shape.Rock))
        )
        both.foreach(f => assert(f.join().isRight))

        val finished = store.get("m-1").get
        assertEquals(finished.throws.map(_.participantId).sorted, List(11L, 22L))
        assert(finished.completed)
        assertEquals(recorder.moves.size, 2)
        // Exactly one of the two throws was the second one, so exactly one result was reported.
        assertEquals(recorder.results.size, 1)
    }

    test("a stored match round-trips through its json, which is how DynamoDB holds it") {
        val (engine, _, store, _, _) = fixture(isPublic = true)
        engine.move("m-1", alice, Shape.Scissors)
        val m = store.get("m-1").get
        assertEquals(read[RpsMatch](write(m)), m)
    }
}
