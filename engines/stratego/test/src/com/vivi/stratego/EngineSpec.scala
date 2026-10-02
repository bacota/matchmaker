package com.vivi.stratego

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{InMemoryMatchStore, QuietTests, RecordingMatchmaker, Refusal}
import Protocol.given
import Armies.{names, setup}

/** Plays matches through the engine, checking both what a player is told and what matchmaker is told — and, in this
  * game above all, what a player is *not* told.
  */
class EngineSpec extends FunSuite with QuietTests {

    private val moveUrl = "http://matchmaker.test/games/1/matches/m-1/moves"
    private val resultsUrl = "http://matchmaker.test/games/1/matches/m-1/results"
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private val alice = "sub-alice"
    private val bob = "sub-bob"

    private def createRequest(
        isPublic: Boolean = false,
        roles: List[Option[String]] = List(None, None),
        parameters: Map[String, String] = Map.empty
    ) =
        Protocol.CreateGameRequest(
          matchId = "m-1",
          gameName = "stratego",
          isPublic = isPublic,
          parameters = parameters,
          settings = "{}",
          timeLimitSeconds = Some(600),
          players = List(
            Protocol.EnginePlayer(alice, 11L, roles.head, None, None),
            Protocol.EnginePlayer(bob, 22L, roles(1), None, None)
          ),
          moveCallbackUrl = Some(moveUrl),
          resultsCallbackUrl = Some(resultsUrl)
        )

    private class Fixture(request: Protocol.CreateGameRequest = createRequest()) {
        val clock = AtomicReference(start)
        val store = InMemoryMatchStore[StrategoMatch]()
        val recorder = RecordingMatchmaker()
        val engine = Engine(store, recorder, "http://engine.test", () => clock.get)
        val created = engine.createGame(request).toOption.get
        def m: StrategoMatch = store.get("m-1").get
        def advance(seconds: Long): Instant = clock.updateAndGet(_.plusSeconds(seconds))
        def state(who: Option[String]): Protocol.StateResponse =
            engine.stateOf(m, who.map(w => m.seatFor(w).get))
    }

    /** A red scout on a4 (square 30) with an open file to a7 (square 60), where blue has put `blueOnA7`. */
    private def deployed(blueOnA7: Rank = Rank.Sergeant, request: Protocol.CreateGameRequest = createRequest()) = {
        val f = Fixture(request)
        assert(f.engine.deploy("m-1", alice, names(setup(Side.Red, Map(30 -> Rank.Scout)))).isRight)
        assert(f.engine.deploy("m-1", bob, names(setup(Side.Blue, Map(60 -> blueOnA7)))).isRight)
        f
    }

    test("creating a game seats Red and Blue and hands back the urls matchmaker needs") {
        val f = Fixture(createRequest(isPublic = true))
        assertEquals(f.created.playUrl, "http://engine.test/matches/m-1/play")
        assertEquals(f.created.publicUrl, Some("http://engine.test/matches/m-1/board"))
        assertEquals(f.m.seats.map(s => (s.side, s.participantId)), List((Side.Red, 11L), (Side.Blue, 22L)))
        assertEquals(f.m.maxMoves, StrategoMatch.defaultMaxMoves)
    }

    test("the roles matchmaker sends decide who plays Red") {
        val f = Fixture(createRequest(roles = List(Some("Blue"), Some("Red"))))
        assertEquals(f.m.seatOf(Side.Red).get.participantId, 22L)
    }

    test("a game for anything but two different players, or with an unusable move cap, is refused") {
        val engine = Engine(InMemoryMatchStore[StrategoMatch](), RecordingMatchmaker(), "http://engine.test")
        val solo = createRequest().copy(players = createRequest().players.take(1))
        assertEquals(
          engine.createGame(solo),
          Left(Refusal.Invalid("stratego is a two-player game; 1 player(s) were sent"))
        )
        val doubled = createRequest().copy(players = createRequest().players.map(_.copy(cognitoId = alice)))
        assertEquals(
          engine.createGame(doubled),
          Left(Refusal.Invalid("the two seats must belong to two different players"))
        )
        assertEquals(
          engine.createGame(createRequest(parameters = Map("maxMoves" -> "0"))),
          Left(Refusal.Invalid("maxMoves must be a positive whole number, not '0'"))
        )
    }

    test("both seats are pending for setup, and either may deploy first") {
        val f = Fixture()
        assertEquals(
          f.engine.status("m-1").toOption.get.participants.filter(_.pending).map(_.participantId),
          List(11L, 22L)
        )

        assert(f.engine.deploy("m-1", bob, names(setup(Side.Blue))).isRight)
        // Nobody named: Red was already waiting, on a clock that has not restarted.
        assertEquals(f.recorder.moves.map(_._2.next), List(Nil))
        assertEquals(f.engine.status("m-1").toOption.get.participants.filter(_.pending).map(_.participantId), List(11L))

        f.advance(30)
        assert(f.engine.deploy("m-1", alice, names(setup(Side.Red))).isRight)
        // Now the game proper: Red to move, from the moment the second army went down.
        assertEquals(f.recorder.moves.last._2.next, List(11L))
        assertEquals(f.recorder.moves.last._2.state.get.pending, List(Protocol.PendingSeat(11L, start.plusSeconds(30))))
        assertEquals(f.recorder.moves.map(_._2.startedAt), List(start, start))
        assert(f.m.inPlay)
    }

    test("a setup must be one whole army, made once") {
        val f = Fixture()
        assertEquals(
          f.engine.deploy("m-1", alice, names(setup(Side.Red)).tail),
          Left(Refusal.Invalid("a setup places 40 pieces, not 39"))
        )
        assertEquals(
          f.engine.deploy("m-1", alice, "Admiral" :: names(setup(Side.Red)).tail),
          Left(Refusal.Invalid("'Admiral' is not a rank"))
        )
        assert(f.engine.deploy("m-1", alice, names(setup(Side.Red))).isRight)
        assertEquals(
          f.engine.deploy("m-1", alice, names(setup(Side.Red))),
          Left(Refusal.Invalid("you have already deployed; a setup cannot be changed"))
        )
        assertEquals(f.recorder.moves.size, 1)
    }

    test("nobody moves before both armies are down, and then Red moves first and the sides alternate") {
        val f = Fixture()
        assertEquals(f.engine.move("m-1", alice, 30, 40), Left(Refusal.Invalid("deploy your army before moving")))
        f.engine.deploy("m-1", alice, names(setup(Side.Red, Map(30 -> Rank.Scout))))
        assertEquals(f.engine.move("m-1", alice, 30, 40), Left(Refusal.Invalid("Blue has not deployed yet")))
        f.engine.deploy("m-1", bob, names(setup(Side.Blue)))

        assertEquals(f.engine.move("m-1", bob, 60, 50), Left(Refusal.Invalid("it is Red's turn, not Blue's")))
        assert(f.engine.move("m-1", alice, 30, 40).isRight)
        assertEquals(f.recorder.moves.last._2.next, List(22L))
        assertEquals(f.engine.move("m-1", alice, 40, 41), Left(Refusal.Invalid("it is Blue's turn, not Red's")))
        assert(f.engine.move("m-1", bob, 60, 50).isRight)
        assertEquals(f.engine.move("m-1", alice, 1, 11), Left(Refusal.Invalid("a bomb cannot move")))
    }

    test("someone with no seat in the match may not deploy or move in it") {
        val f = Fixture()
        assertEquals(
          f.engine.deploy("m-1", "sub-carol", names(setup(Side.Red))),
          Left(Refusal.NotYours("'sub-carol' has no seat in match 'm-1'"))
        )
        assertEquals(f.recorder.moves, Nil)
    }

    test("during setup a player sees their own army and only the fact of the other's") {
        val f = Fixture(createRequest(isPublic = true))
        f.engine.deploy("m-1", alice, names(setup(Side.Red)))

        val red = f.state(Some(alice))
        assertEquals(red.phase, "setup")
        assertEquals(red.deployed, List("Red"))
        assertEquals(red.pieces.size, 40)
        assert(red.pieces.forall(p => p.side == "Red" && p.rank.isDefined))

        assertEquals(f.state(Some(bob)).pieces, Nil)
        assertEquals(f.state(None).pieces, Nil)
        // Nothing of Red's setup is anywhere in what Blue is sent.
        assert(!write(f.state(Some(bob))).contains("Marshal"))
    }

    test("in play an opponent's ranks are hidden from the other player and from the public board") {
        val f = deployed()
        val red = f.state(Some(alice))
        assertEquals(red.phase, "play")
        assertEquals(red.pieces.size, 80)
        assert(red.pieces.filter(_.side == "Red").forall(_.rank.isDefined))
        assert(red.pieces.filter(_.side == "Blue").forall(_.rank.isEmpty))
        assert(f.state(None).pieces.forall(_.rank.isEmpty))
        assertEquals(f.state(None).you, None)
    }

    test("a battle reveals both pieces to everyone, and nothing else") {
        val f = deployed(blueOnA7 = Rank.Sergeant)
        // The scout runs up the file into the sergeant, and loses.
        assert(f.engine.move("m-1", alice, 30, 60).isRight)

        val public = f.state(None)
        assertEquals(public.pieces.filter(_.rank.isDefined).map(p => (p.square, p.rank.get)), List(60 -> "Sergeant"))
        assertEquals(
          public.lastMove,
          Some(Protocol.LastMove("Red", 30, 60, Some(Protocol.BattleView("Scout", "Sergeant", "DefenderWins"))))
        )
        assertEquals(public.lost.map(l => l.side -> l.ranks), List("Red" -> List("Scout"), "Blue" -> Nil))
        assertEquals(
          f.state(Some(alice)).pieces.filter(p => p.side == "Blue" && p.rank.isDefined).map(_.square),
          List(60)
        )
        assertEquals(f.state(Some(bob)).players.map(_.captured), List(0, 1))
    }

    test("a scout that runs more than one square is revealed by it") {
        val f = deployed()
        f.engine.move("m-1", alice, 30, 50)
        assertEquals(f.state(Some(bob)).pieces.find(_.square == 50).flatMap(_.rank), Some("Scout"))
    }

    test("legal moves are offered to the player to move, and to nobody else") {
        val f = deployed()
        assert(f.state(Some(alice)).legalMoves.contains(List(30, 60)))
        assertEquals(f.state(Some(bob)).legalMoves, Nil)
        assertEquals(f.state(None).legalMoves, Nil)
    }

    test("a move from a square off the board is refused, not a failure of the engine") {
        val f = deployed()
        assertEquals(
          f.engine.move("m-1", alice, 100, 0),
          Left(Refusal.Invalid("square 100 is not on the board; squares are numbered 0 to 99"))
        )
        assertEquals(
          f.engine.move("m-1", alice, -1, 0),
          Left(Refusal.Invalid("square -1 is not on the board; squares are numbered 0 to 99"))
        )
    }

    test("a match keeps the move cap it was created with, even one equal to the default") {
        val explicit = Fixture(createRequest(parameters = Map("maxMoves" -> StrategoMatch.defaultMaxMoves.toString)))
        assert(write(explicit.m).contains(s"\"maxMoves\":${StrategoMatch.defaultMaxMoves}"))
        // And one created without the parameter stores the default it was given, rather than following it.
        assert(write(Fixture().m).contains(s"\"maxMoves\":${StrategoMatch.defaultMaxMoves}"))
    }

    test("a fourth consecutive move between the same two squares is refused") {
        val f = deployed()
        List((alice, 30, 40), (bob, 69, 59), (alice, 40, 30), (bob, 59, 69), (alice, 30, 40), (bob, 69, 59))
            .foreach((who, from, to) => assert(f.engine.move("m-1", who, from, to).isRight, s"$from to $to"))
        assertEquals(
          f.engine.move("m-1", alice, 40, 30),
          Left(Refusal.Invalid("that would be a fourth move in a row between a5 and a4"))
        )
        assert(!f.state(Some(alice)).legalMoves.contains(List(40, 30)))
        assert(f.engine.move("m-1", alice, 40, 41).isRight)
    }

    test("taking the flag wins, and matchmaker gets the move and then the results") {
        val f = deployed(blueOnA7 = Rank.Flag)
        assert(f.engine.move("m-1", alice, 30, 60).isRight)

        val m = f.m
        assert(m.completed)
        assertEquals(m.winner, Some(Side.Red))
        assertEquals(m.ending, Some(Ending.FlagTaken))
        assertEquals(f.recorder.moves.last._2.next, Nil)

        val (url, results) = f.recorder.results.head
        assertEquals(url, resultsUrl)
        val byParticipant = results.results.map(r => r.participantId -> r).toMap
        assert(byParticipant(11L).isWinner)
        assertEquals(byParticipant(11L).scores("ending").str, "flag")
        assertEquals(byParticipant(11L).scores("captured").num, 1.0)
        assertEquals(byParticipant(22L).rank, 2)
        // Setups are turns too: matchmaker charges their time.
        assertEquals(results.turns.get.map(_.participantId), List(11L, 22L, 11L))

        assertEquals(f.engine.move("m-1", bob, 61, 51), Left(Refusal.Invalid("this match is already over")))
    }

    test("once the match is over every rank is shown, on the public board too") {
        val f = deployed(blueOnA7 = Rank.Flag, request = createRequest(isPublic = true))
        f.engine.move("m-1", alice, 30, 60)
        val public = f.state(None)
        assertEquals(public.phase, "over")
        assertEquals(public.winner, Some("Red"))
        assert(public.pieces.forall(_.rank.isDefined))
    }

    test("the move cap ends the match as a draw") {
        val f = deployed(request = createRequest(parameters = Map("maxMoves" -> "2")))
        f.engine.move("m-1", alice, 30, 40)
        assert(!f.m.isOver)
        f.engine.move("m-1", bob, 69, 59)
        assert(f.m.isOver)
        assert(f.m.isDraw)
        assert(f.recorder.results.head._2.results.forall(r => r.rank == 1 && !r.isWinner))
        assertEquals(f.recorder.results.head._2.results.head.scores("ending").str, "cap")
    }

    test("status counts every turn, setups included, and says whose turn it is") {
        val f = deployed()
        f.engine.move("m-1", alice, 30, 40)
        val status = f.engine.status("m-1").toOption.get
        assertEquals(status.sequence, Some(3L))
        assertEquals(status.participants.filter(_.pending).map(_.participantId), List(22L))
        assertEquals(status.turns.map(_.participantId), List(11L, 22L, 11L))
    }

    test("a stored match round-trips through its json, which is how DynamoDB holds it") {
        val f = deployed()
        f.engine.move("m-1", alice, 30, 60)
        assertEquals(read[StrategoMatch](write(f.m)), f.m)
    }

    test("a callback that fails does not fail the move, which stands, and the results are still sent".tag(Quiet)) {
        val f = deployed(blueOnA7 = Rank.Flag)
        f.recorder.failCallbacks = true
        assert(f.engine.move("m-1", alice, 30, 60).isRight)
        assert(f.m.completed)
        assertEquals(f.recorder.results.size, 1)
    }

    test("under a live clock a player who never deploys forfeits the match") {
        val f = Fixture(createRequest().copy(live = Some(Protocol.LiveTerms(30, "PER_TURN"))))
        f.m.seats.foreach(seat => f.engine.core.opened("m-1", seat))
        f.advance(10)
        assert(f.engine.deploy("m-1", alice, names(setup(Side.Red))).isRight)
        f.advance(19)
        assert(!f.engine.read("m-1").toOption.get.isOver)
        f.advance(2)
        val ended = f.engine.read("m-1").toOption.get
        assertEquals(ended.ending, Some(Ending.Forfeit))
        assertEquals(ended.winner, Some(Side.Red))
    }
}
