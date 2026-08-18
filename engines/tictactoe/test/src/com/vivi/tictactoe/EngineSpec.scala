package com.vivi.tictactoe

import java.time.Instant
import munit.FunSuite
import upickle.default.{read, write}
import Protocol.given

/** Plays whole matches through the engine, checking both what a player is told and what
  * matchmaker is told — the callbacks are half of the engine's job, and the only half matchmaker
  * actually depends on.
  */
class EngineSpec extends FunSuite {

  private val moveUrl = "http://matchmaker.test/games/1/matches/m-1/moves"
  private val resultsUrl = "http://matchmaker.test/games/1/matches/m-1/results"

  private def createRequest(isPublic: Boolean = false, roles: List[Option[String]] = List(None, None)) =
    Protocol.CreateGameRequest(
      matchId = "m-1",
      gameName = "tic-tac-toe",
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
    val engine = Engine(store, recorder, "http://engine.test", () => Instant.parse("2026-01-01T00:00:00Z"))
    val created = engine.createGame(createRequest(isPublic, roles)).toOption.get
    val m = store.get("m-1").get
    (engine, recorder, store, created, m)
  }

  /** Who plays this mark — a move is authorized by the player's Cognito subject, which is what
    * matchmaker sent as the seat's `cognitoId`.
    */
  private def playerOf(m: TicTacToeMatch, mark: Mark) = m.seatOf(mark).get.cognitoId

  test("creating a game seats both players and hands back the urls matchmaker needs") {
    val (engine, _, _, created, m) = fixture(isPublic = true)

    assertEquals(created.statusUrl, "http://engine.test/matches/m-1/status")
    // One url for both players: the engine works out whose seat it is from who signed in.
    assertEquals(created.playUrl, "http://engine.test/matches/m-1/play")
    assertEquals(created.publicUrl, Some("http://engine.test/matches/m-1/board"))
    assertEquals(m.seats.map(_.participantId), List(11L, 22L))
    assertEquals(m.seats.map(_.mark), List(Mark.X, Mark.O))
    assertEquals(m.seats.map(_.cognitoId), List("sub-alice", "sub-bob"))
  }

  test("a private game has no public url") {
    val (_, _, _, created, _) = fixture(isPublic = false)
    assertEquals(created.publicUrl, None)
  }

  test("the roles matchmaker sends decide who plays X") {
    val (_, _, _, _, m) = fixture(roles = List(Some("O"), Some("X")))
    assertEquals(m.seatOf(Mark.O).get.participantId, 11L)
    assertEquals(m.seatOf(Mark.X).get.participantId, 22L)
  }

  test("a game for anything but two players is refused") {
    val store = InMemoryMatchStore()
    val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
    val solo = createRequest().copy(players = createRequest().players.take(1))
    assertEquals(engine.createGame(solo), Left(Refusal.Invalid("tic-tac-toe is a two-player game; 1 player(s) were sent")))
  }

  test("every move is reported to matchmaker, naming who moved and who is next") {
    val (engine, recorder, store, _, m) = fixture()

    assert(engine.move("m-1", playerOf(m, Mark.X), 4).isRight)
    assertEquals(recorder.moves.size, 1)
    val (url, first) = recorder.moves.head
    assertEquals(url, moveUrl)
    assertEquals(first.participantId, 11L)
    assertEquals(first.next, List(22L))
    assertEquals(first.prevMoveAt, Some(Instant.parse("2026-01-01T00:00:00Z")))
    assertEquals(store.get("m-1").get.board.encoded, "....X....")
  }

  test("a player may not move out of turn, twice, or into a taken cell") {
    val (engine, recorder, _, _, m) = fixture()

    assertEquals(engine.move("m-1", playerOf(m, Mark.O), 0), Left(Refusal.Invalid("it is X's turn, not O's")))
    assert(engine.move("m-1", playerOf(m, Mark.X), 0).isRight)
    assertEquals(engine.move("m-1", playerOf(m, Mark.X), 1), Left(Refusal.Invalid("it is O's turn, not X's")))
    assertEquals(engine.move("m-1", playerOf(m, Mark.O), 0), Left(Refusal.Invalid("cell 0 is already taken by X")))
    // A refused move is not a move: matchmaker heard about exactly the one that landed.
    assertEquals(recorder.moves.size, 1)
  }

  test("someone with no seat in the match may not move in it") {
    val (engine, recorder, _, _, _) = fixture()
    assertEquals(engine.move("m-1", "sub-carol", 0), Left(Refusal.NotYours("'sub-carol' has no seat in match 'm-1'")))
    assertEquals(engine.move("m-1", "", 0).isLeft, true)
    assertEquals(recorder.moves, Nil)
  }

  test("a match whose two seats are the same player is refused, since a seat is found by subject") {
    val store = InMemoryMatchStore()
    val engine = Engine(store, RecordingMatchmaker(), "http://engine.test")
    val both = createRequest()
    val doubled = both.copy(players = both.players.map(_.copy(cognitoId = "sub-alice")))
    assertEquals(engine.createGame(doubled), Left(Refusal.Invalid("the two seats must belong to two different players")))
  }

  test("a win ends the match, and matchmaker gets the move and then the results") {
    val (engine, recorder, store, _, m) = fixture()
    val x = playerOf(m, Mark.X)
    val o = playerOf(m, Mark.O)

    // X takes the top row, O the middle of the board and one below it.
    List((x, 0), (o, 3), (x, 1), (o, 4), (x, 2)).foreach((token, cell) => assert(engine.move("m-1", token, cell).isRight, s"cell $cell"))

    val finished = store.get("m-1").get
    assertEquals(finished.winner, Some(Mark.X))
    assert(finished.completed)

    assertEquals(recorder.moves.size, 5)
    // The last move names nobody as next: there is no next.
    assertEquals(recorder.moves.last._2.next, Nil)

    assertEquals(recorder.results.size, 1)
    val (url, results) = recorder.results.head
    assertEquals(url, resultsUrl)
    val byParticipant = results.results.map(r => r.participantId -> r).toMap
    assertEquals(byParticipant(11L).rank, 1)
    assert(byParticipant(11L).isWinner)
    assertEquals(byParticipant(11L).scores("outcome").str, "win")
    assertEquals(byParticipant(11L).scores("moves").num, 3.0)
    assertEquals(byParticipant(22L).rank, 2)
    assert(!byParticipant(22L).isWinner)
    assertEquals(byParticipant(22L).scores("outcome").str, "loss")
  }

  test("a drawn match ranks both players first and neither a winner") {
    val (engine, recorder, _, _, m) = fixture()
    val x = playerOf(m, Mark.X)
    val o = playerOf(m, Mark.O)

    //  X O X
    //  X O O
    //  O X X
    List((x, 0), (o, 1), (x, 2), (o, 4), (x, 3), (o, 6), (x, 7), (o, 5), (x, 8))
      .foreach((token, cell) => assert(engine.move("m-1", token, cell).isRight, s"cell $cell"))

    val results = recorder.results.head._2.results
    assert(results.forall(r => r.rank == 1 && !r.isWinner))
    assert(results.forall(_.scores("outcome").str == "draw"))
  }

  test("moving in a finished match is refused rather than reopening it") {
    val (engine, recorder, _, _, m) = fixture()
    val x = playerOf(m, Mark.X)
    val o = playerOf(m, Mark.O)
    List((x, 0), (o, 3), (x, 1), (o, 4), (x, 2)).foreach((token, cell) => engine.move("m-1", token, cell))

    assertEquals(engine.move("m-1", o, 8), Left(Refusal.Invalid("this match is already over")))
    assertEquals(recorder.results.size, 1)
  }

  test("status says whose turn it is, and that the match is over once it is") {
    val (engine, _, _, _, m) = fixture()

    val opening = engine.status("m-1").toOption.get
    assertEquals(opening.completed, false)
    assertEquals(opening.participants.filter(_.pending).map(_.participantId), List(11L))
    // Nobody has moved, so the first player's clock started when the match was created.
    assertEquals(opening.participants.head.prevMoveAt, Some(Instant.parse("2026-01-01T00:00:00Z")))

    engine.move("m-1", playerOf(m, Mark.X), 4)
    assertEquals(engine.status("m-1").toOption.get.participants.filter(_.pending).map(_.participantId), List(22L))

    List((playerOf(m, Mark.O), 0), (playerOf(m, Mark.X), 1), (playerOf(m, Mark.O), 2), (playerOf(m, Mark.X), 7))
      .foreach((token, cell) => engine.move("m-1", token, cell))

    val over = engine.status("m-1").toOption.get
    assert(over.completed)
    assert(over.participants.forall(p => p.completed && !p.pending))
  }

  test("an unknown match is a 404 to matchmaker and to a player alike") {
    val (engine, _, _, _, _) = fixture()
    assertEquals(engine.status("nope"), Left(Refusal.NotFound("no match 'nope'")))
    assertEquals(engine.move("nope", "sub-alice", 0), Left(Refusal.NotFound("no match 'nope'")))
  }

  test("a match with no callback urls is still playable") {
    val store = InMemoryMatchStore()
    val recorder = RecordingMatchmaker()
    val engine = Engine(store, recorder, "http://engine.test")
    engine.createGame(createRequest().copy(moveCallbackUrl = None, resultsCallbackUrl = None))
    val m = store.get("m-1").get

    assert(engine.move("m-1", playerOf(m, Mark.X), 0).isRight)
    assertEquals(recorder.moves, Nil)
  }

  test("a stored match round-trips through its json, which is how DynamoDB holds it") {
    val (_, _, store, _, _) = fixture(isPublic = true)
    val m = store.get("m-1").get
    assertEquals(read[TicTacToeMatch](write(m)), m)
  }
}
