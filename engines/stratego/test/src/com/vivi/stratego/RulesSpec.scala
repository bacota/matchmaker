package com.vivi.stratego

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.util.Random

class RulesSpec extends ScalaCheckSuite {

    /** A board with only the pieces named, each as `square -> (side, rank)`. */
    private def boardOf(placed: (Int, (Side, Rank))*): Board =
        Board(placed.zipWithIndex.foldLeft(Board.empty.cells) { case (cells, ((square, (side, rank)), id)) =>
            cells.updated(square, Some(Piece(id, side, rank)))
        })

    private def fight(attacker: Rank, defender: Rank): Result = Rank.attack(attacker, defender)

    test("the higher rank wins, and equal ranks both fall") {
        assertEquals(fight(Rank.Colonel, Rank.Major), Result.AttackerWins)
        assertEquals(fight(Rank.Scout, Rank.Captain), Result.DefenderWins)
        assertEquals(fight(Rank.Captain, Rank.Captain), Result.BothLost)
        assertEquals(fight(Rank.Marshal, Rank.General), Result.AttackerWins)
    }

    test("a spy takes the marshal only by attacking it") {
        assertEquals(fight(Rank.Spy, Rank.Marshal), Result.AttackerWins)
        assertEquals(fight(Rank.Marshal, Rank.Spy), Result.AttackerWins)
        assertEquals(fight(Rank.Spy, Rank.General), Result.DefenderWins)
        assertEquals(fight(Rank.Spy, Rank.Spy), Result.BothLost)
    }

    test("a bomb destroys every attacker but a miner") {
        assertEquals(fight(Rank.Miner, Rank.Bomb), Result.AttackerWins)
        assertEquals(fight(Rank.Marshal, Rank.Bomb), Result.DefenderWins)
        assertEquals(fight(Rank.Scout, Rank.Bomb), Result.DefenderWins)
    }

    test("anything takes the flag") {
        assertEquals(fight(Rank.Spy, Rank.Flag), Result.AttackerWins)
        assertEquals(fight(Rank.Scout, Rank.Flag), Result.AttackerWins)
    }

    test("a piece moves one square orthogonally, onto empty squares and enemies but not its own side") {
        val board = boardOf(44 -> (Side.Red, Rank.Captain), 45 -> (Side.Red, Rank.Miner), 34 -> (Side.Blue, Rank.Spy))
        assertEquals(board.targets(Side.Red, 44).sorted, List(34, 54))
        assertEquals(board.targets(Side.Blue, 44), Nil)
    }

    test("nothing enters a lake") {
        val board = boardOf(41 -> (Side.Red, Rank.Captain))
        assertEquals(board.targets(Side.Red, 41).sorted, List(31, 40, 51))
        assert(board.move(Side.Red, 41, 42).isLeft)
    }

    test("bombs and flags never move") {
        val board = boardOf(0 -> (Side.Red, Rank.Bomb), 1 -> (Side.Red, Rank.Flag))
        assertEquals(board.targets(Side.Red, 0), Nil)
        assertEquals(board.move(Side.Red, 1, 11), Left("a flag cannot move"))
    }

    test("a scout runs any distance in a straight line, attacks the first enemy and never jumps") {
        val board = boardOf(
          0 -> (Side.Red, Rank.Scout),
          60 -> (Side.Blue, Rank.Sergeant),
          70 -> (Side.Blue, Rank.Flag),
          3 -> (Side.Red, Rank.Miner)
        )
        assertEquals(board.targets(Side.Red, 0).sorted, List(1, 2, 10, 20, 30, 40, 50, 60))
    }

    test("a scout that moves more than one square is revealed; one that moves one square is not") {
        val board = boardOf(0 -> (Side.Red, Rank.Scout), 9 -> (Side.Red, Rank.Scout))
        val far = board.move(Side.Red, 0, 30).toOption.get.board
        assert(far(30).get.revealed)
        val near = board.move(Side.Red, 9, 19).toOption.get.board
        assert(!near(19).get.revealed)
        assert(near(19).get.moved)
    }

    test("a battle reveals the survivor, and removes the loser — or both") {
        val board = boardOf(30 -> (Side.Red, Rank.Major), 40 -> (Side.Blue, Rank.Captain), 31 -> (Side.Red, Rank.Scout))
        val won = board.move(Side.Red, 30, 40).toOption.get
        assertEquals(won.battle, Some(Battle(Rank.Major, Rank.Captain, Result.AttackerWins)))
        assertEquals(won.board(30), None)
        assertEquals(won.board(40).map(p => (p.rank, p.revealed)), Some((Rank.Major, true)))

        val lost = board.move(Side.Red, 31, 41).toOption.get.board.move(Side.Blue, 40, 41).toOption.get
        assertEquals(lost.battle.map(_.result), Some(Result.AttackerWins))
        assertEquals(lost.board(41).map(_.side), Some(Side.Blue))

        val even = boardOf(30 -> (Side.Red, Rank.Captain), 40 -> (Side.Blue, Rank.Captain)).move(Side.Red, 30, 40)
        assertEquals(even.toOption.get.board.cells.flatten, Vector.empty)
    }

    test("a move is refused for a missing piece, an enemy piece, or a square off the board") {
        val board = boardOf(30 -> (Side.Red, Rank.Major), 60 -> (Side.Blue, Rank.Captain))
        assertEquals(board.move(Side.Red, 31, 32), Left("there is no piece on b4"))
        assertEquals(board.move(Side.Red, 60, 50), Left("the piece on a7 is not yours"))
        assert(board.move(Side.Red, 30, 100).isLeft)
        assert(board.move(Side.Red, 30, 50).isLeft)
    }

    test("a setup is exactly one army") {
        val army = Board.randomSetup(Random(1))
        assert(Board.validateSetup(army).isRight)
        assertEquals(Board.validateSetup(army.tail), Left("a setup places 40 pieces, not 39"))
        val twoMarshals = Rank.Marshal :: army.filterNot(_ == Rank.General)
        assertEquals(
          Board.validateSetup(twoMarshals),
          Left("a setup has the wrong number of general (0, not 1), marshal (2, not 1)")
        )
    }

    test("deploying puts each army on its own four rows, with ids that differ between the sides") {
        val board = Board.empty
            .deploy(Side.Red, Board.randomSetup(Random(1)))
            .flatMap(_.deploy(Side.Blue, Board.randomSetup(Random(2))))
            .toOption
            .get
        assertEquals(board.pieces(Side.Red).map(_._1), (0 until 40).toVector)
        assertEquals(board.pieces(Side.Blue).map(_._1), (60 until 100).toVector)
        assertEquals(board.cells.flatten.map(_.id).distinct.size, 80)
        assert(board.hasFlag(Side.Red) && board.hasFlag(Side.Blue))
    }

    test("a piece may not make a fourth consecutive move between the same two squares") {
        val history = List(Step(5, 40, 50), Step(5, 50, 40), Step(5, 40, 50))
        assert(Rules.shuttles(history, Step(5, 50, 40)))
        // A different piece, a different square, or a break in the run are all fine.
        assert(!Rules.shuttles(history, Step(6, 50, 40)))
        assert(!Rules.shuttles(history, Step(5, 50, 60)))
        assert(!Rules.shuttles(Step(7, 0, 10) :: history.tail, Step(5, 50, 40)))

        val board = boardOf(50 -> (Side.Red, Rank.Captain))
        val shuttled = List(Step(0, 40, 50), Step(0, 50, 40), Step(0, 40, 50))
        assertEquals(Rules.legalMoves(board, Side.Red, shuttled).sorted, List((50, 51), (50, 60)))
    }

    property("random legal play never puts a piece in a lake, and never makes a piece appear") {
        Prop.forAll(Gen.long) { seed =>
            val random = Random(seed)
            val start = Board.empty
                .deploy(Side.Red, Board.randomSetup(random))
                .flatMap(_.deploy(Side.Blue, Board.randomSetup(random)))
                .toOption
                .get
            val (end, _) = (1 to 200).foldLeft((start, Side.Red)) { case ((board, side), _) =>
                val moves = board.moves(side)
                if (moves.isEmpty) (board, side)
                else {
                    val (from, to) = moves(random.nextInt(moves.size))
                    (board.move(side, from, to).toOption.get.board, side.other)
                }
            }
            val ids = end.cells.flatten.map(_.id)
            Board.lakes.forall(end(_).isEmpty) && ids.distinct.size == ids.size && ids.size <= 80
        }
    }
}
