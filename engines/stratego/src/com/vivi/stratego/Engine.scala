package com.vivi.stratego

import java.time.Instant
import com.vivi.engine.{GameEngine, MatchStore, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Stratego: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and the things
  * that are this game's own — what a setup and a move are, and what each viewer is shown. How it tells matchmaker whose
  * turn it is is [[StrategoMatch$]]'s.
  *
  * @param announce
  *   called once with each new match, which is how the local server prints the play url and who is seated where.
  */
class Engine(
    store: MatchStore[StrategoMatch],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: StrategoMatch => Unit = _ => ()
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(StrategoMatch, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player's deployment: their 40 ranks, in the order of their home squares.
      *
      * Either side may deploy first, and neither sees the other's army. A setup cannot be changed once it is in: the
      * other player may already be deploying against what they were told of it, which is that it exists.
      */
    def deploy(
        matchId: String,
        cognitoId: String,
        ranks: List[String]
    ): Either[Refusal, MoveApplied[StrategoMatch, Seat, MoveRecord]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
                _ <- Either.cond(
                  !current.hasDeployed(seat.side),
                  (),
                  Refusal.Invalid("you have already deployed; a setup cannot be changed")
                )
                parsed <- ranks
                    .map(r => Rank.parse(r).toRight(Refusal.Invalid(s"'$r' is not a rank")))
                    .partitionMap(identity) match {
                    case (Nil, valid) => Right(valid)
                    case (wrong, _)   => Left(wrong.head)
                }
                board <- current.board.deploy(seat.side, parsed).left.map(Refusal.Invalid.apply)
            } yield {
                // Both clocks started when the match did — see `StrategoMatch` — or, in a live match, when
                // this player opened the board, if that was later.
                val record =
                    MoveRecord(
                      seat.participantId,
                      seat.side,
                      at,
                      TurnClock.turnStart(current.clock, seat.participantId, current.createdAt)
                    )
                (current.copy(board = board, turns = current.turns :+ record), record)
            }
        }

    /** A player's move: their piece on `from` to `to`, attacking whatever stands there. */
    def move(
        matchId: String,
        cognitoId: String,
        from: Int,
        to: Int
    ): Either[Refusal, MoveApplied[StrategoMatch, Seat, MoveRecord]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
                _ <- Either.cond(
                  current.inPlay,
                  (),
                  Refusal.Invalid(
                    if (current.hasDeployed(seat.side)) s"${seat.side.other} has not deployed yet"
                    else "deploy your army before moving"
                  )
                )
                _ <- Either.cond(
                  current.toMove == seat.side,
                  (),
                  Refusal.Invalid(s"it is ${current.toMove}'s turn, not ${seat.side}'s")
                )
                // Checked before the board's own rules so that it is only ever said of a move the board allows.
                _ <- current.board(from).filter(_.side == seat.side) match {
                    case Some(p)
                        if current.board.targets(seat.side, from).contains(to) &&
                            Rules.shuttles(current.history(seat.side), Step(p.id, from, to)) =>
                        Left(
                          Refusal.Invalid(
                            s"that would be a fourth move in a row between ${Board.name(from)} and ${Board.name(to)}"
                          )
                        )
                    case _ => Right(())
                }
                moved <- current.board.move(seat.side, from, to).left.map(Refusal.Invalid.apply)
            } yield {
                val started =
                    TurnClock.turnStart(current.clock, seat.participantId, StrategoMatch.clockStartedAt(current))
                val step = Step(current.board(from).get.id, from, to)
                val record = MoveRecord(seat.participantId, seat.side, at, started, Some(step), moved.battle)
                (current.copy(board = moved.board, turns = current.turns :+ record), record)
            }
        }

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board.
      *
      * Everything a viewer may not see is left out here, and not merely left undrawn by the page: the response is what
      * travels, and a rank in it is a rank anybody with the developer tools can read. So an opponent's piece carries
      * its rank only once it has been revealed, the public board sees only what both players have seen, and during
      * setup an opponent's army is not sent at all — only the fact that it is down. Once the match is over, everything
      * is.
      */
    def stateOf(m: StrategoMatch, seat: Option[Seat]): StateResponse = {
        val over = m.isOver
        val viewer = seat.map(_.side)
        val pieces =
            m.board.cells.indices.toList.flatMap(square =>
                m.board(square).flatMap { p =>
                    val own = viewer.contains(p.side)
                    Option.when(over || m.inPlay || own)(
                      PieceView(
                        square,
                        p.side.toString,
                        Option.when(over || own || p.revealed)(p.rank.toString),
                        p.revealed,
                        p.moved
                      )
                    )
                }
            )
        StateResponse(
          matchId = m.matchId,
          phase = if (over) "over" else if (m.inPlay) "play" else "setup",
          you = viewer.map(_.toString),
          deployed = Side.values.toList.filter(m.hasDeployed).map(_.toString),
          turn = Option.when(m.inPlay && !over)(m.toMove.toString),
          pieces = pieces,
          legalMoves = viewer
              .filter(v => m.inPlay && !over && v == m.toMove)
              .toList
              .flatMap(m.legalMoves)
              .map((from, to) => List(from, to)),
          lastMove = m.moves.lastOption.flatMap(r =>
              r.step.map(s =>
                  LastMove(
                    r.side.toString,
                    s.from,
                    s.to,
                    r.battle.map(b => BattleView(b.attacker.toString, b.defender.toString, b.result.toString))
                  )
              )
          ),
          lost = Side.values.toList.map(s => LostView(s.toString, m.lost(s).map(_.toString))),
          completed = over,
          winner = m.winner.map(_.toString),
          draw = m.isDraw,
          ending = m.ending.map(_.label),
          moveCount = m.moves.size,
          maxMoves = m.maxMoves,
          players = m.seats.map(s =>
              SeatView(
                s.side.toString,
                s.cognitoId,
                s.participantId,
                m.moves.count(_.side == s.side),
                m.captured(s.side)
              )
          ),
          clock = core.clockView(m)
        )
    }
}
