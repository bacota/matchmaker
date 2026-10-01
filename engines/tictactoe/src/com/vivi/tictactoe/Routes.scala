package com.vivi.tictactoe

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a move looks like
  * on the wire.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String])
    extends EngineRoutes[TicTacToeMatch, Seat, Protocol.StateResponse](
      engine.core,
      playAuth,
      matchmakerKey,
      Html.signIn
    ) {

    protected def stateOf(m: TicTacToeMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def page(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        publicView: Boolean
    ): String = Html.board(matchId, state, login, publicView)

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        parse[Protocol.MoveRequest](request.body) match {
            case Left(why)   => error(400, why)
            case Right(move) => asPlayer(request)(caller => engine.move(matchId, caller, move.cell).map(moved))
        }
}
