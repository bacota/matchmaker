package com.vivi.stratego

import com.vivi.engine.ErrorText
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
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid(ErrorText.matches.over))
                _ <- Either.cond(
                  !current.hasDeployed(seat.side),
                  (),
                  Refusal.Invalid(ErrorText.stratego.alreadyDeployed)
                )
                parsed <- ranks
                    .map(r => Rank.parse(r).toRight(Refusal.Invalid(ErrorText.stratego.notARank(r))))
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
                      participantId = seat.participantId,
                      side = seat.side,
                      takenAt = at,
                      startedAt = TurnClock.turnStart(
                        clock = current.clock,
                        participantId = seat.participantId,
                        turnStartedAt = current.createdAt
                      ),
                      setup = Some(parsed)
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
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid(ErrorText.matches.over))
                _ <- Either.cond(
                  current.isInPlay,
                  (),
                  Refusal.Invalid(
                    if (current.hasDeployed(seat.side)) ErrorText.stratego.otherNotDeployed(seat.side.other.toString)
                    else ErrorText.stratego.deployFirst
                  )
                )
                _ <- Either.cond(
                  current.toMove == seat.side,
                  (),
                  Refusal.Invalid(
                    ErrorText.stratego.notYourTurn(turn = current.toMove.toString, side = seat.side.toString)
                  )
                )
                // Checked before the board's own rules so that it is only ever said of a move the board allows. A
                // square off the board finds no piece here, and is refused by `Board.move` below.
                _ <- current.board.cells.lift(from).flatten.filter(_.side == seat.side) match {
                    case Some(p)
                        if current.board.targets(seat.side, from).contains(to) &&
                            Rules.isShuttling(current.history(seat.side), Step(pieceId = p.id, from = from, to = to)) =>
                        Left(
                          Refusal.Invalid(ErrorText.stratego.shuttling(from = Board.name(from), to = Board.name(to)))
                        )
                    case _ => Right(())
                }
                moved <- current.board.move(side = seat.side, from = from, to = to).left.map(Refusal.Invalid.apply)
            } yield {
                val started =
                    TurnClock.turnStart(
                      clock = current.clock,
                      participantId = seat.participantId,
                      turnStartedAt = StrategoMatch.clockStartedAt(current)
                    )
                val step = Step(pieceId = current.board(from).get.id, from = from, to = to)
                val record = MoveRecord(
                  participantId = seat.participantId,
                  side = seat.side,
                  takenAt = at,
                  startedAt = started,
                  step = Some(step),
                  battle = moved.battle
                )
                (current.copy(board = moved.board, turns = current.turns :+ record), record)
            }
        }

    /** A player giving the match up, which their opponent wins.
      *
      * At any point before the match is over — during setup, and whether or not it is their turn — since a player who
      * wants to stop should not have to wait to be allowed to. It is recorded as their turn, taken and started at the
      * same instant: the concession is not a move anyone was waiting on, so no time is charged for it.
      */
    def concede(matchId: String, cognitoId: String): Either[Refusal, MoveApplied[StrategoMatch, Seat, MoveRecord]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            Either.cond(
              !current.isOver, {
                  val record = MoveRecord(
                    participantId = seat.participantId,
                    side = seat.side,
                    takenAt = at,
                    startedAt = at,
                    isConcession = true
                  )
                  (current.copy(turns = current.turns :+ record), record)
              },
              Refusal.Invalid(ErrorText.matches.over)
            )
        }

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board.
      *
      * Everything a viewer may not see is left out here, and not merely left undrawn by the page: the response is what
      * travels, and a rank in it is a rank anybody with the developer tools can read. So an opponent's piece carries
      * its rank only once it has been revealed, the public board sees only what both players have seen, and during
      * setup an opponent's army is not sent at all — only the fact that it is down. The match being over changes none
      * of this: a player still sees only their own army and what they saw of the other, and the public board only what
      * was revealed in play.
      */
    def stateOf(m: StrategoMatch, seat: Option[Seat]): StateResponse = {
        val over = m.isOver
        val viewer = seat.map(_.side)
        // Not `over`: a finished match hides what it hid while it was played. See above.
        val pieces =
            m.board.cells.indices.toList.flatMap(square =>
                m.board(square).flatMap { p =>
                    val own = viewer.contains(p.side)
                    Option.when(m.isInPlay || own)(
                      PieceView(
                        square = square,
                        side = p.side.toString,
                        rank = Option.when(own || p.isRevealed)(p.rank.toString),
                        isRevealed = p.isRevealed,
                        isMoved = p.isMoved
                      )
                    )
                }
            )
        StateResponse(
          matchId = m.matchId,
          phase = if (over) "over" else if (m.isInPlay) "play" else "setup",
          you = viewer.map(_.toString),
          deployed = Side.values.toList.filter(m.hasDeployed).map(_.toString),
          turn = Option.when(m.isInPlay && !over)(m.toMove.toString),
          pieces = pieces,
          legalMoves = viewer
              .filter(v => m.isInPlay && !over && v == m.toMove)
              .toList
              .flatMap(m.legalMoves)
              .map((from, to) => List(from, to)),
          lastMove = m.moves.lastOption.flatMap(moveView),
          lost = Side.values.toList.map(s => LostView(s.toString, m.lost(s).map(_.toString))),
          isCompleted = over,
          winner = m.winner.map(_.toString),
          isDrawn = m.isDrawn,
          ending = m.ending.map(_.label),
          moveCount = m.moves.size,
          maxMoves = m.maxMoves,
          players = m.seats.map(s =>
              SeatView(
                side = s.side.toString,
                cognitoId = s.cognitoId,
                participantId = s.participantId,
                moves = m.moves.count(_.side == s.side),
                captured = m.captured(s.side),
                nickname = s.nickname
              )
          ),
          clock = core.clockView(m),
          replay = replayOf(m, viewer)
        )
    }

    private def moveView(r: MoveRecord): Option[LastMove] =
        r.step.map(s =>
            LastMove(
              side = r.side.toString,
              from = s.from,
              to = s.to,
              battle = r.battle.map(b =>
                  BattleView(attacker = b.attacker.toString, defender = b.defender.toString, result = b.result.toString)
              )
            )
        )

    /** The match from its opening position, hiding — see [[Protocol.ReplayView]] — the ranks of the pieces that stand
      * unseen by `viewer` now, whether or not the match is over.
      */
    private def replayOf(m: StrategoMatch, viewer: Option[Side]): Option[Protocol.ReplayView] = {
        val unseen = m.board.cells.flatten.filter(p => !p.isRevealed && !viewer.contains(p.side)).map(_.id).toSet
        m.opening.map(opening =>
            Protocol.ReplayView(
              opening.cells.indices.toList.flatMap(square =>
                  opening(square).map(p =>
                      PieceView(
                        square = square,
                        side = p.side.toString,
                        rank = Option.unless(unseen(p.id))(p.rank.toString),
                        isRevealed = false,
                        isMoved = false
                      )
                  )
              ),
              m.moves.flatMap(moveView)
            )
        )
    }
}
