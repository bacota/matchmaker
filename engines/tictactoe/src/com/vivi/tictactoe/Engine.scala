package com.vivi.tictactoe

import java.time.Instant
import com.vivi.engine.{GameEngine, MatchStore, Matchmaker, MoveApplied, Refusal}
import Protocol._

/** Tic-tac-toe: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and the two
  * things that are this game's own — what a move is, and what a player is shown. How it tells matchmaker whose turn it
  * is is [[TicTacToeMatch$]]'s.
  *
  * @param announce
  *   called once with each new match, which is how the local server prints the board's url and who is seated where.
  */
class Engine(
    store: MatchStore[TicTacToeMatch],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: TicTacToeMatch => Unit = _ => ()
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(TicTacToeMatch, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player's move: a mark placed in `cell`, by whoever's turn it is. */
    def move(
        matchId: String,
        cognitoId: String,
        cell: Int
    ): Either[Refusal, MoveApplied[TicTacToeMatch, Seat, TurnRecord]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
                _ <- Either.cond(
                  current.turn == seat.mark,
                  (),
                  Refusal.Invalid(s"it is ${current.turn}'s turn, not ${seat.mark}'s")
                )
                board <- current.board.place(cell, seat.mark).left.map(Refusal.Invalid.apply)
            } yield {
                // The clock for this move started when the move before it was made, or when the
                // match was created for the first move of the game.
                val turn = TurnRecord(seat.participantId, at, TicTacToeMatch.clockStartedAt(current))
                val played =
                    current.copy(
                      board = board,
                      turn = seat.mark.other,
                      lastMoveAt = Some(at),
                      turns = current.turns :+ turn
                    )
                (played, turn)
            }
        }

    /** The state a board page renders. `seat` is the viewer's own, absent on the public board. */
    def stateOf(m: TicTacToeMatch, seat: Option[Seat]): StateResponse =
        StateResponse(
          matchId = m.matchId,
          board = m.board.encoded,
          turn = Option.unless(m.isOver)(m.turn.toString),
          you = seat.map(_.mark.toString),
          completed = m.isOver,
          winner = m.winner.map(_.toString),
          draw = m.isDraw,
          winningLine = m.board.winningLine,
          players = m.seats.map(s => SeatView(s.mark.toString, s.cognitoId, s.participantId, m.moveCount(s.mark))),
          clock = core.clockView(m)
        )
}
