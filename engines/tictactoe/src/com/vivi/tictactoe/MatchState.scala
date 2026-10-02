package com.vivi.tictactoe

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant
import com.vivi.engine.{Game, MatchLike, Outcome, SeatLike, TurnClock, TurnLike}

/** One player's seat in a match.
  *
  * `cognitoId` is who may move in it — the same subject the player signs in as, which is how matchmaker named them and
  * how the engine recognises them. `participantId` is matchmaker's key for the seat and is what every callback quotes
  * back.
  */
case class Seat(mark: Mark, cognitoId: String, participantId: Long) extends SeatLike

/** One move that was made: who made it, when, and when their clock started for it.
  *
  * Kept per match rather than derived from the board, because a board says what the position is and not when it got
  * there. Matchmaker asks for these to charge a chess-clock time limit, and asks for the ones after a time it names —
  * so what matters is that each carries its own timestamps rather than depending on its neighbours.
  */
case class TurnRecord(participantId: Long, takenAt: Instant, startedAt: Instant) extends TurnLike

/** A match in progress, and everything needed to answer for it or to call matchmaker back.
  *
  * The callback urls are stored per match rather than configured once because matchmaker sends them with the game: they
  * carry its game id and match id, and an engine serving several matchmaker installations would get different bases for
  * each.
  *
  * `clock` is a live match's turn clock, and `None` for every other match.
  */
case class TicTacToeMatch(
    matchId: String,
    board: Board,
    turn: Mark,
    seats: List[Seat],
    isPublic: Boolean,
    completed: Boolean,
    createdAt: Instant,
    lastMoveAt: Option[Instant],
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    // Defaulted so a match stored before turns were recorded still reads back: it simply has
    // none, and matchmaker charges nothing for the moves made before this existed.
    turns: List[TurnRecord] = Nil,
    // Defaulted for the same reason: a match stored before live matches existed is not one.
    clock: Option[TurnClock] = None
) extends MatchLike {

    def seatOf(mark: Mark): Option[Seat] = seats.find(_.mark == mark)

    /** The seat belonging to a signed-in player, if they have one in this match. */
    def seatFor(cognitoId: String): Option[Seat] = seats.find(_.cognitoId == cognitoId)

    /** Whoever has three in a row — or, in a live match the clock ended, whoever did not run out. */
    def winner: Option[Mark] =
        if (ranOut)
            seats.find(s => clock.flatMap(_.outcomeOf(s.participantId)).contains(Outcome.Win)).map(_.mark)
        else board.winner

    /** A finished match is one that is won or has no empty cell left, or one whose clock ran out. Kept derived rather
      * than stored so a board and a completion flag cannot disagree.
      */
    def isOver: Boolean = ranOut || winner.isDefined || board.isFull

    def isDraw: Boolean = !ranOut && winner.isEmpty && board.isFull

    /** Whether a live match's clock ended this one. */
    def ranOut: Boolean = clock.exists(_.ranOut)

    /** How many marks a seat has placed — the one thing worth scoring in a game this small, and enough to show that an
      * open `scores` map survives the round trip into matchmaker.
      */
    def moveCount(mark: Mark): Int = board.cells.count(_.contains(mark))
}

/** Tic-tac-toe as matchmaker sees it: a game of alternating turns.
  *
  * One seat is pending at a time, the one whose mark is to be placed, and its clock starts when the move before it was
  * made — or when the match was created, for the opening move, so that the first player's clock starts when the game
  * does rather than never. That is what matchmaker would have guessed, but a guess is only right for a game of
  * alternating turns, and `engines/rps` is not one, so it is stated rather than left to be guessed.
  */
object TicTacToeMatch extends Game[TicTacToeMatch, Seat, TurnRecord] {

    /** Seats the players, honouring the roles matchmaker sent when it sent usable ones.
      *
      * A game configured in matchmaker with roles named `X` and `O` gets exactly those seats. With no roles, or roles
      * this engine does not recognise, the first player named takes X — the engine still has to produce a playable
      * game, and refusing would make role configuration a prerequisite for trying it out.
      */
    def seat(players: List[Protocol.EnginePlayer]): Either[String, List[Seat]] =
        if (players.sizeIs != 2) Left(s"tic-tac-toe is a two-player game; ${players.size} player(s) were sent")
        else if (players.map(_.cognitoId).distinct.sizeIs != 2)
            // Both seats are found by the caller's subject, so one player holding both would make the
            // match unplayable in a way that is much harder to diagnose later than here.
            Left("the two seats must belong to two different players")
        else {
            val requested = players.map(p => p.role.flatMap(Mark.parse))
            val marks =
                if (requested.flatten.distinct.sizeIs == 2) requested.map(_.get)
                else List(Mark.X, Mark.O)
            Right(players.zip(marks).map((p, mark) => Seat(mark, p.cognitoId, p.participantId)))
        }

    def seats(m: TicTacToeMatch): List[Seat] = m.seats

    def isOver(m: TicTacToeMatch): Boolean = m.isOver

    def markCompleted(m: TicTacToeMatch): TicTacToeMatch = m.copy(completed = true)

    def pending(m: TicTacToeMatch): List[Seat] = if (m.isOver) Nil else m.seatOf(m.turn).toList

    def clockStartedAt(m: TicTacToeMatch): Instant = m.lastMoveAt.getOrElse(m.createdAt)

    def turns(m: TicTacToeMatch): List[TurnRecord] = m.turns

    /** Counted from the board rather than from `turns`, which is empty for a match stored before turns were recorded.
      */
    def sequence(m: TicTacToeMatch): Long = m.board.moveCount.toLong

    def outcome(m: TicTacToeMatch, seat: Seat): Outcome =
        m.clock
            .flatMap(_.outcomeOf(seat.participantId))
            .getOrElse(
              if (m.winner.contains(seat.mark)) Outcome.Win else if (m.isDraw) Outcome.Draw else Outcome.Loss
            )

    def clock(m: TicTacToeMatch): Option[TurnClock] = m.clock

    def withClock(m: TicTacToeMatch, clock: TurnClock): TicTacToeMatch = m.copy(clock = Some(clock))

    /** `moves` is how many marks the seat placed. */
    def scores(m: TicTacToeMatch, seat: Seat): Map[String, ujson.Value] =
        Map("moves" -> ujson.Num(m.moveCount(seat.mark).toDouble), "mark" -> ujson.Str(seat.mark.toString))

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, TicTacToeMatch] =
        seat(request.players).map { seats =>
            TicTacToeMatch(
              matchId = request.matchId,
              board = Board.empty,
              turn = Mark.X,
              seats = seats,
              isPublic = request.isPublic,
              completed = false,
              createdAt = now,
              lastMoveAt = None,
              moveCallbackUrl = request.moveCallbackUrl,
              resultsCallbackUrl = request.resultsCallbackUrl
            )
        }

    // Stored as JSON, which is what both stores hold: the in-memory one keeps the object itself,
    // and DynamoDB keeps this string in one attribute rather than a modelled item — the engine
    // never queries by anything but the match id.
    given ReadWriter[Mark] = upickle.default.readwriter[String].bimap(_.toString, s => Mark.valueOf(s))
    given ReadWriter[Board] = upickle.default.readwriter[String].bimap(_.encoded, Board.decode)
    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Seat] = macroRW
    given ReadWriter[TurnRecord] = macroRW
    given ReadWriter[TicTacToeMatch] = macroRW
}
