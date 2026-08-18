package com.vivi.tictactoe

import munit.FunSuite

class BoardSpec extends FunSuite {

  test("a completed row, column or diagonal wins") {
    assertEquals(Board.decode("XXX...OO.").winner, Some(Mark.X))
    assertEquals(Board.decode("X.OX.OX..").winner, Some(Mark.X))
    assertEquals(Board.decode("O.X.O.X.O").winner, Some(Mark.O))
    assertEquals(Board.decode("..O.O.X..").winner, None)
  }

  test("a full board with no line is a draw, not a win") {
    val drawn = Board.decode("XXOOOXXOX")
    assertEquals(drawn.winner, None)
    assert(drawn.isFull)
  }

  test("the winning line is reported so the page can mark it") {
    assertEquals(Board.decode("O..OXX O.".replace(" ", "O")).winningLine, Some(Seq(0, 3, 6)))
    assertEquals(Board.empty.winningLine, None)
  }

  test("a mark cannot be placed off the board or on a taken cell") {
    assertEquals(Board.empty.place(9, Mark.X).left.map(_.take(6)), Left("cell 9"))
    assertEquals(Board.empty.place(-1, Mark.X).isLeft, true)
    val taken = Board.empty.place(4, Mark.X).toOption.get
    assert(taken.place(4, Mark.O).isLeft)
    assert(taken.place(0, Mark.O).isRight)
  }

  test("a board survives being encoded and decoded, which is how it is stored") {
    val board = Board.decode("XO.X..O.X")
    assertEquals(Board.decode(board.encoded), board)
    assertEquals(board.moveCount, 5)
    assertEquals(Board.empty.encoded, ".........")
  }
}
