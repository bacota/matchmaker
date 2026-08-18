package com.vivi.tictactoe

/** Which of the two marks a seat plays. The engine's `role` names are these, spelled exactly as
  * matchmaker's `game_role.name` rows should be — see the README's setup section.
  */
enum Mark {
  case X, O

  def other: Mark = this match {
    case X => O
    case O => X
  }
}

object Mark {
  def parse(s: String): Option[Mark] = values.find(_.toString.equalsIgnoreCase(s.trim))
}

/** A 3x3 board, cells numbered left to right and top to bottom:
  *
  * {{{
  *   0 | 1 | 2
  *   3 | 4 | 5
  *   6 | 7 | 8
  * }}}
  */
case class Board(cells: Vector[Option[Mark]]) {
  require(cells.sizeIs == 9, s"a board has 9 cells, not ${cells.size}")

  def apply(cell: Int): Option[Mark] = cells(cell)

  def isFull: Boolean = cells.forall(_.isDefined)

  def moveCount: Int = cells.count(_.isDefined)

  /** The mark on every cell of some winning line, if there is one.
    *
    * Only one line can ever be complete in a legally reached position, so the first match is the
    * answer rather than one of several.
    */
  def winner: Option[Mark] =
    Board.lines.collectFirst {
      case Seq(a, b, c) if cells(a).isDefined && cells(a) == cells(b) && cells(b) == cells(c) => cells(a).get
    }

  def winningLine: Option[Seq[Int]] =
    Board.lines.find(line => line.map(cells).distinct.sizeIs == 1 && cells(line.head).isDefined)

  /** Places a mark, or says why it cannot be placed.
    *
    * Rejecting rather than throwing because every caller is a request handler answering a player
    * who may simply have clicked a taken cell — that is a 400, not a failure of the engine.
    */
  def place(cell: Int, mark: Mark): Either[String, Board] =
    if (cell < 0 || cell > 8) Left(s"cell $cell is not on the board; cells are numbered 0 to 8")
    else if (cells(cell).isDefined) Left(s"cell $cell is already taken by ${cells(cell).get}")
    else Right(Board(cells.updated(cell, Some(mark))))

  /** The position as nine characters, `.` for an empty cell — how a board is stored and how the
    * tests state one.
    */
  def encoded: String = cells.map(_.map(_.toString).getOrElse(".")).mkString
}

object Board {

  val empty: Board = Board(Vector.fill(9)(None))

  val lines: Seq[Seq[Int]] =
    Seq(
      Seq(0, 1, 2), Seq(3, 4, 5), Seq(6, 7, 8), // rows
      Seq(0, 3, 6), Seq(1, 4, 7), Seq(2, 5, 8), // columns
      Seq(0, 4, 8), Seq(2, 4, 6)                // diagonals
    )

  def decode(s: String): Board =
    Board(s.map(c => Mark.parse(c.toString)).toVector)
}
