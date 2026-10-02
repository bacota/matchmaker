package com.vivi.stratego

import scala.util.Random

/** Which army a seat commands. The engine's `role` names are these, spelled exactly as matchmaker's `game_role.name`
  * rows should be — see `register-game.sql`. Red deploys on rows 1 to 4 and moves first.
  */
enum Side {
    case Red, Blue

    def other: Side = this match {
        case Red  => Blue
        case Blue => Red
    }

    /** The 40 squares this side deploys on, in ascending order — the order a setup lists its ranks in. */
    def homeSquares: Range = this match {
        case Red  => 0 until 40
        case Blue => 60 until 100
    }
}

object Side {
    def parse(s: String): Option[Side] = values.find(_.toString.equalsIgnoreCase(s.trim))
}

/** A piece's rank, with how many of it each army has. `strength` orders the ranks that fight by number; the bomb and
  * the flag never attack, and how they are attacked is [[Rank.attack]]'s.
  */
enum Rank(val strength: Int, val count: Int) {
    case Flag extends Rank(0, 1)
    case Spy extends Rank(1, 1)
    case Scout extends Rank(2, 8)
    case Miner extends Rank(3, 5)
    case Sergeant extends Rank(4, 4)
    case Lieutenant extends Rank(5, 4)
    case Captain extends Rank(6, 4)
    case Major extends Rank(7, 3)
    case Colonel extends Rank(8, 2)
    case General extends Rank(9, 1)
    case Marshal extends Rank(10, 1)
    case Bomb extends Rank(11, 6)

    def movable: Boolean = this != Bomb && this != Flag
}

object Rank {
    def parse(s: String): Option[Rank] = values.find(_.toString.equalsIgnoreCase(s.trim))

    /** The 40 pieces of one army, as a multiset. */
    val army: Map[Rank, Int] = values.map(r => r -> r.count).toMap

    /** What happens when `attacker` strikes `defender`.
      *
      * The higher strength wins and equals both fall, with three exceptions: a spy that *attacks* the marshal takes it,
      * a bomb destroys anything that attacks it but a miner, and a flag is taken by anything.
      */
    def attack(attacker: Rank, defender: Rank): Result =
        defender match {
            case Flag                       => Result.AttackerWins
            case Bomb if attacker == Miner  => Result.AttackerWins
            case Bomb                       => Result.DefenderWins
            case Marshal if attacker == Spy => Result.AttackerWins
            case _ =>
                if (attacker.strength > defender.strength) Result.AttackerWins
                else if (attacker.strength == defender.strength) Result.BothLost
                else Result.DefenderWins
        }
}

enum Result {
    case AttackerWins, DefenderWins, BothLost
}

/** One piece on the board.
  *
  * `id` follows the piece as it moves, which is what the two-square rule needs to recognise a piece shuttling back and
  * forth. `revealed` is whether the opponent has seen its rank — it fought, or it is a scout that moved more than one
  * square — and once true it stays true. `moved` is whether it has ever moved, which tells the opponent it is neither a
  * bomb nor the flag.
  */
case class Piece(id: Int, side: Side, rank: Rank, revealed: Boolean = false, moved: Boolean = false)

/** A fight that a move started, with both ranks as they were — what both players are told, since a battle reveals both
  * pieces.
  */
case class Battle(attacker: Rank, defender: Rank, result: Result)

/** A move that was made: the board after it, and the battle it caused, if it attacked. */
case class Moved(board: Board, battle: Option[Battle])

/** A 10x10 board, squares numbered from Red's back left corner, left to right and then away from Red:
  *
  * {{{
  *   90 ... 99    row 10, Blue's back row
  *   ...
  *   40 41 [42 43] 44 45 [46 47] 48 49    row 5, the bracketed squares lakes
  *   ...
  *    0 ...  9    row 1, Red's back row
  * }}}
  *
  * Named as a player would, column letter then row: square 0 is `a1`, square 99 is `j10`.
  */
case class Board(cells: Vector[Option[Piece]]) {
    require(cells.sizeIs == 100, s"a board has 100 squares, not ${cells.size}")

    def apply(square: Int): Option[Piece] = cells(square)

    def pieces(side: Side): Seq[(Int, Piece)] =
        cells.indices.flatMap(i => cells(i).filter(_.side == side).map(i -> _))

    /** Where the piece at `from` may move, or nothing if no piece of `side` stands there.
      *
      * One square in any of the four directions, onto an empty square or an enemy's — the latter an attack. A scout
      * goes on in a straight line as far as it likes, until it meets the edge, a lake or a piece; it may attack the
      * first enemy it meets, but never jumps one. Bombs and flags never move, and nothing enters a lake.
      */
    def targets(side: Side, from: Int): List[Int] =
        cells(from) match {
            case Some(p) if p.side == side && p.rank.movable =>
                Board.directions.flatMap { (dr, dc) =>
                    def walk(at: Int, acc: List[Int]): List[Int] =
                        Board.step(at, dr, dc) match {
                            case None => acc
                            case Some(next) if cells(next).isEmpty =>
                                if (p.rank == Rank.Scout) walk(next, next :: acc) else next :: acc
                            case Some(next) if cells(next).exists(_.side != side) => next :: acc
                            case Some(_)                                          => acc
                        }
                    walk(from, Nil).reverse
                }
            case _ => Nil
        }

    /** Every move `side` could make, by the board alone — before the two-square rule, which needs the history. */
    def moves(side: Side): List[(Int, Int)] =
        pieces(side).toList.flatMap((from, _) => targets(side, from).map(from -> _))

    /** Moves `side`'s piece from `from` to `to`, fighting whatever stands there, or says why it cannot.
      *
      * Rejecting rather than throwing because every caller is a request handler answering a player who may simply have
      * tapped the wrong square — that is a 400, not a failure of the engine.
      */
    def move(side: Side, from: Int, to: Int): Either[String, Moved] =
        if (!Board.onBoard(from)) Left(s"square $from is not on the board; squares are numbered 0 to 99")
        else if (!Board.onBoard(to)) Left(s"square $to is not on the board; squares are numbered 0 to 99")
        else
            cells(from) match {
                case None                       => Left(s"there is no piece on ${Board.name(from)}")
                case Some(p) if p.side != side  => Left(s"the piece on ${Board.name(from)} is not yours")
                case Some(p) if !p.rank.movable => Left(s"a ${p.rank.toString.toLowerCase} cannot move")
                case Some(p) if !targets(side, from).contains(to) =>
                    Left(
                      s"your ${p.rank.toString.toLowerCase} cannot move from ${Board.name(from)} to ${Board.name(to)}"
                    )
                case Some(p) =>
                    // A scout that crossed more than one square has shown what it is.
                    val mover = p.copy(moved = true, revealed = p.revealed || Board.distance(from, to) > 1)
                    val cleared = cells.updated(from, None)
                    cells(to) match {
                        case None => Right(Moved(Board(cleared.updated(to, Some(mover))), None))
                        case Some(defender) =>
                            val result = Rank.attack(mover.rank, defender.rank)
                            val after = result match {
                                case Result.AttackerWins => cleared.updated(to, Some(mover.copy(revealed = true)))
                                case Result.DefenderWins => cleared.updated(to, Some(defender.copy(revealed = true)))
                                case Result.BothLost     => cleared.updated(to, None)
                            }
                            Right(Moved(Board(after), Some(Battle(mover.rank, defender.rank, result))))
                    }
            }

    /** The board with `side`'s army deployed: `ranks` in the order of [[Side.homeSquares]]. */
    def deploy(side: Side, ranks: List[Rank]): Either[String, Board] =
        Board.validateSetup(ranks).map { valid =>
            val idBase = if (side == Side.Red) 0 else 40
            val placed = side.homeSquares.zip(valid).zipWithIndex.foldLeft(cells) { case (acc, ((square, rank), i)) =>
                acc.updated(square, Some(Piece(idBase + i, side, rank)))
            }
            Board(placed)
        }

    def hasFlag(side: Side): Boolean = cells.exists(_.exists(p => p.side == side && p.rank == Rank.Flag))
}

object Board {

    val empty: Board = Board(Vector.fill(100)(None))

    val lakes: Set[Int] = Set(42, 43, 46, 47, 52, 53, 56, 57)

    private val directions: List[(Int, Int)] = List((1, 0), (-1, 0), (0, 1), (0, -1))

    def onBoard(square: Int): Boolean = square >= 0 && square < 100

    def row(square: Int): Int = square / 10

    def column(square: Int): Int = square % 10

    def name(square: Int): String = s"${('a' + column(square)).toChar}${row(square) + 1}"

    /** The square one step from `at`, if it is on the board and not a lake. */
    private def step(at: Int, dr: Int, dc: Int): Option[Int] = {
        val r = row(at) + dr
        val c = column(at) + dc
        Option.when(r >= 0 && r < 10 && c >= 0 && c < 10)(r * 10 + c).filterNot(lakes)
    }

    private def distance(a: Int, b: Int): Int = math.abs(row(a) - row(b)) + math.abs(column(a) - column(b))

    /** A setup is exactly one army: 40 ranks, each as many times as [[Rank.count]] says. */
    def validateSetup(ranks: List[Rank]): Either[String, List[Rank]] = {
        val counts = ranks.groupMapReduce(identity)(_ => 1)(_ + _)
        val wrong = Rank.values.toList.filter(r => counts.getOrElse(r, 0) != r.count)
        if (ranks.sizeIs != 40) Left(s"a setup places 40 pieces, not ${ranks.size}")
        else if (wrong.nonEmpty)
            Left(
              "a setup has the wrong number of " +
                  wrong
                      .map(r => s"${r.toString.toLowerCase} (${counts.getOrElse(r, 0)}, not ${r.count})")
                      .mkString(", ")
            )
        else Right(ranks)
    }

    /** A legal setup in random order — for tests, and for the page's "random" button, which has its own copy. */
    def randomSetup(random: Random): List[Rank] =
        random.shuffle(Rank.values.toList.flatMap(r => List.fill(r.count)(r)))
}
