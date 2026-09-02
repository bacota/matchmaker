package com.vivi.tictactoe

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** One player's seat in a match.
  *
  * `cognitoId` is who may move in it — the same subject the player signs in as, which is how
  * matchmaker named them and how the engine recognises them. `participantId` is matchmaker's key
  * for the seat and is what every callback quotes back.
  */
case class Seat(mark: Mark, cognitoId: String, participantId: Long)

/** One move that was made: who made it, when, and when their clock started for it.
  *
  * Kept per match rather than derived from the board, because a board says what the position is
  * and not when it got there. Matchmaker asks for these to charge a chess-clock time limit, and
  * asks for the ones after a time it names — so what matters is that each carries its own
  * timestamps rather than depending on its neighbours.
  */
case class TurnRecord(participantId: Long, takenAt: Instant, startedAt: Instant)

/** A match in progress, and everything needed to answer for it or to call matchmaker back.
  *
  * The callback urls are stored per match rather than configured once because matchmaker sends
  * them with the game: they carry its game id and match id, and an engine serving several
  * matchmaker installations would get different bases for each.
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
    turns: List[TurnRecord] = Nil
) {

  def seatOf(mark: Mark): Option[Seat] = seats.find(_.mark == mark)

  /** The seat belonging to a signed-in player, if they have one in this match. */
  def seatFor(cognitoId: String): Option[Seat] = seats.find(_.cognitoId == cognitoId)

  def winner: Option[Mark] = board.winner

  /** A finished match is one that is won or has no empty cell left. Kept derived rather than
    * stored so a board and a completion flag cannot disagree.
    */
  def isOver: Boolean = winner.isDefined || board.isFull

  def isDraw: Boolean = winner.isEmpty && board.isFull

  /** How many marks a seat has placed — the one thing worth scoring in a game this small, and
    * enough to show that an open `scores` map survives the round trip into matchmaker.
    */
  def moveCount(mark: Mark): Int = board.cells.count(_.contains(mark))
}

object TicTacToeMatch {

  /** Seats the players, honouring the roles matchmaker sent when it sent usable ones.
    *
    * A game configured in matchmaker with roles named `X` and `O` gets exactly those seats. With
    * no roles, or roles this engine does not recognise, the first player named takes X — the
    * engine still has to produce a playable game, and refusing would make role configuration a
    * prerequisite for trying it out.
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
