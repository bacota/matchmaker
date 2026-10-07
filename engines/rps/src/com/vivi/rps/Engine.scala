package com.vivi.rps

import java.time.Instant
import com.vivi.engine.{
    GameEngine,
    MatchStore,
    Matchmaker,
    MoveApplied,
    Refusal,
    TurnClock,
    InMemoryMatchStore,
    RoleChoosing
}
import Protocol._

/** Rock-paper-scissors: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and
  * the two things that are this game's own — what a throw is, and what a player is shown.
  *
  * What this engine exists to exercise, and tic-tac-toe does not, is a game with no turn order. Both seats are pending
  * from the moment the match is created; either player may throw first; neither is told what the other threw until both
  * have; and the match resolves on the second throw rather than on anybody's move in particular. Matchmaker already
  * models that — its move callback takes a *list* of seats to make pending and leaves unnamed seats alone, and
  * `MatchSummary.whoseTurn` is a list — so this is the game that proves those are real. How it tells matchmaker so is
  * [[RpsMatch$]]'s.
  *
  * @param announce
  *   called once with each new match, which is how the local server prints the play url and who is seated where.
  */
class Engine(
    store: MatchStore[RpsMatch],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: RpsMatch => Unit = _ => (),
    // Where matches choosing their roles are kept: see `GameEngine`.
    roles: MatchStore[RoleChoosing] = InMemoryMatchStore[RoleChoosing]()
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(RpsMatch, store, matchmaker, baseUrl, now, announce, roles)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player's throw.
      *
      * Simultaneity is the whole game, so this is the one engine where the store's compare-and-set earns its keep on
      * the ordinary path rather than on a rare race: two players clicking at once is what is *expected* to happen, and
      * both throws must land.
      */
    def move(
        matchId: String,
        cognitoId: String,
        shape: Shape
    ): Either[Refusal, MoveApplied[RpsMatch, Seat, ThrowRecord]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
                // No "it is not your turn" here: it is always both players' turn. The only move a
                // player cannot make is a second one — changing a throw once it is in would let
                // whoever moved last win every match.
                _ <- Either.cond(
                  !current.hasThrown(seat),
                  (),
                  Refusal.Invalid("you have already thrown; a throw cannot be taken back")
                )
            } yield {
                // Both clocks started when the match was created — see `RpsMatch` — or, in a live match,
                // when this player opened the board, if that was later.
                val record =
                    ThrowRecord(
                      seat.participantId,
                      shape,
                      at,
                      TurnClock.turnStart(current.clock, seat.participantId, current.createdAt)
                    )
                (current.copy(throws = current.throws :+ record), record)
            }
        }

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board.
      *
      * The other player's throw is in the answer only once the match is over. Until then a viewer is told that a seat
      * *has* thrown and nothing more — which is as much as is safe to say and as much as the page needs to show that it
      * is waiting rather than broken.
      */
    def stateOf(m: RpsMatch, seat: Option[Seat]): StateResponse = {
        val over = m.isOver
        StateResponse(
          matchId = m.matchId,
          waitingFor = m.pending.map(_.side.toString),
          you = seat.map(_.side.toString),
          yourThrow = seat.flatMap(m.throwOf).map(_.shape.toString),
          completed = over,
          winner = Option.when(over)(m.winner.map(_.side.toString)).flatten,
          draw = m.isDraw,
          players = m.seats.map(s =>
              SeatView(
                side = s.side.toString,
                cognitoId = s.cognitoId,
                participantId = s.participantId,
                thrown = m.hasThrown(s),
                shape = Option.when(over)(m.throwOf(s).map(_.shape.toString)).flatten
              )
          ),
          clock = core.clockView(m)
        )
    }
}
