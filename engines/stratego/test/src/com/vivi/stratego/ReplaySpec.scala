package com.vivi.stratego

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant
import scala.util.Random
import munit.FunSuite
import upickle.default.write
import com.vivi.engine.{InMemoryMatchStore, QuietTests, RecordingMatchmaker}
import Protocol.given

/** Replaying a match: the opening position the engine sends, what it hides of it, and the page's `replayFrame`, which
  * makes the moves on it.
  *
  * The page's half is run as it is, in Node — what the UI's tests already run in, so a machine without it fails here
  * rather than skipping. It is checked against the engine itself: every position a random game passed through, made
  * again from the opening and the moves, must be the board the engine had then.
  */
class ReplaySpec extends FunSuite with QuietTests {

    private val red = "sub-red"
    private val blue = "sub-blue"

    /** A match played through the engine with random setups and random legal moves, and the board after each move. */
    private class Played(seed: Long, moves: Int) {
        private val random = Random(seed)
        val store = InMemoryMatchStore[StrategoMatch]()
        val engine = Engine(store, RecordingMatchmaker(), "http://engine.test", () => Instant.EPOCH)
        engine.createGame(
          Protocol.CreateGameRequest(
            matchId = "m-1",
            gameName = "stratego",
            isPublic = true,
            parameters = Map.empty,
            settings = "{}",
            timeLimitSeconds = None,
            players = List(
              Protocol.EnginePlayer(red, 11L, Some("Red"), None, None),
              Protocol.EnginePlayer(blue, 22L, Some("Blue"), None, None)
            ),
            moveCallbackUrl = None,
            resultsCallbackUrl = None
          )
        )
        def m: StrategoMatch = store.get("m-1").get
        def who(side: Side): String = if (side == Side.Red) red else blue

        for (side <- Side.values)
            assert(engine.deploy("m-1", who(side), Board.randomSetup(random).map(_.toString)).isRight)
        val boards: List[Board] = m.board :: (1 to moves).toList.flatMap { _ =>
            val legal = if (m.isOver) Nil else m.legalMoves(m.toMove)
            Option.when(legal.nonEmpty) {
                val (from, to) = legal(random.nextInt(legal.size))
                assert(engine.move("m-1", who(m.toMove), from, to).isRight)
                m.board
            }
        }

        def state(viewer: Option[Side]): Protocol.StateResponse = engine.stateOf(m, viewer.flatMap(m.seatOf))

        /** Ends the match by concession if random play has not already ended it. */
        def finish(): Unit = if (!m.isOver) assert(engine.concede("m-1", who(m.toMove)).isRight)
    }

    private val seeds = List(1L, 2L, 3L, 42L, 2026L)

    test("the opening is the board both armies were deployed on") {
        val p = Played(7L, 0)
        assertEquals(p.m.opening, Some(p.boards.head))
    }

    test("a match whose setups were not kept has the same opening rebuilt from its moves") {
        for (seed <- seeds) {
            val p = Played(seed, 300)
            assert(p.m.moves.exists(_.battle.isDefined), s"seed $seed fought no battle, which proves nothing")
            val unkept = p.m.copy(turns = p.m.turns.map(_.copy(setup = None)))
            assertEquals(unkept.opening, p.m.opening, s"seed $seed")
            assertEquals(unkept.opening, Some(p.boards.head), s"seed $seed")
        }
    }

    test("there is nothing to replay until both armies are down") {
        val p = Played(7L, 0)
        val half = p.m.copy(
          turns = p.m.turns.take(1),
          board = Board.empty.deploy(Side.Red, Rank.values.toList.flatMap(r => List.fill(r.count)(r))).toOption.get
        )
        assertEquals(half.opening, None)
        assertEquals(p.engine.stateOf(half, half.seatOf(Side.Red)).replay, None)
    }

    test("the opening hides exactly what still stands unseen, before the match is over and after") {
        val p = Played(3L, 120)
        assert(!p.m.isOver)
        def check(when: String) =
            for (viewer <- List(Some(Side.Red), Some(Side.Blue), None)) {
                val unseen =
                    p.m.board.cells.flatten.filter(q => !q.revealed && !viewer.contains(q.side)).map(_.id).toSet
                val opening = p.m.opening.get
                val hidden =
                    p.state(viewer).replay.get.opening.filter(_.rank.isEmpty).map(v => opening(v.square).get.id)
                assertEquals(hidden.toSet, unseen, s"$when, viewing as $viewer")
                assert(unseen.nonEmpty)
            }
        check("in play")
        p.finish()
        assert(p.m.isOver)
        check("over")
    }

    /** `replayFrame` for every position from 0 to every move, run in Node on `replay`. */
    private def frames(replay: Protocol.ReplayView): ujson.Arr = {
        val program =
            s"""${Html.replayScript}
const replay = ${write(replay)};
const out = [];
for (let k = 0; k <= replay.moves.length; k++) out.push(replayFrame(replay, k));
console.log(JSON.stringify(out));
"""
        val file = Files.createTempFile("replay", ".js")
        try {
            Files.writeString(file, program, UTF_8)
            val process =
                try new ProcessBuilder("node", file.toString).redirectErrorStream(true).start()
                catch {
                    case e: java.io.IOException =>
                        fail(s"these tests run the page's script in Node, which is not on the PATH: ${e.getMessage}")
                }
            val output = new String(process.getInputStream.readAllBytes(), UTF_8)
            assertEquals(process.waitFor(), 0, output)
            ujson.read(output).asInstanceOf[ujson.Arr]
        } finally Files.deleteIfExists(file)
    }

    /** A frame's pieces as the state's `pieces` are written, ordered by square. */
    private def piecesOf(frame: ujson.Value): List[Protocol.PieceView] =
        frame("by").obj.values.toList
            .map(p =>
                Protocol.PieceView(
                  p("square").num.toInt,
                  p("side").str,
                  p("rank").strOpt,
                  p("revealed").bool,
                  p("moved").bool
                )
            )
            .sortBy(_.square)

    private def viewOf(board: Board): List[Protocol.PieceView] =
        board.cells.indices.toList.flatMap(sq =>
            board(sq).map(q => Protocol.PieceView(sq, q.side.toString, Some(q.rank.toString), q.revealed, q.moved))
        )

    test("every move made again on the opening arrives at the board the engine had after it") {
        for (seed <- seeds) {
            val p = Played(seed, 300)
            val boards = p.boards
            p.finish()
            // The replay as nobody is ever sent it, every rank named, so that every rank is checked too.
            val everything = Protocol.ReplayView(
              viewOf(p.m.opening.get),
              p.state(None).replay.get.moves
            )
            val made = frames(everything)
            assertEquals(made.value.size, boards.size, s"seed $seed")
            for ((frame, (board, k)) <- made.value.zip(boards.zipWithIndex))
                assertEquals(piecesOf(frame), viewOf(board), s"seed $seed, after $k moves")
            val lost =
                made.value.last("lost").arr.map(l => Protocol.LostView(l("side").str, l("ranks").arr.map(_.str).toList))
            assertEquals(lost.toList, p.state(None).lost, s"seed $seed")
        }
    }

    test("mid-match, the replay arrives at exactly what each viewer is shown, hiding nothing more and nothing less") {
        // Random play takes a flag early now and then; the games still going are the ones with something hidden.
        val going = LazyList.from(1).map(seed => Played(seed.toLong, 120)).filterNot(_.m.isOver).take(5).toList
        for ((p, game) <- going.zipWithIndex; viewer <- List(Some(Side.Red), Some(Side.Blue), None)) {
            val state = p.state(viewer)
            val made = frames(state.replay.get)
            assertEquals(piecesOf(made.value.last), state.pieces, s"game $game, viewing as $viewer")
        }
    }
}
