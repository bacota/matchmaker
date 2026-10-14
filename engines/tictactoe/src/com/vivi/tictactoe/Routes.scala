package com.vivi.tictactoe

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a move looks like
  * on the wire.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[TicTacToeMatch, Seat, Protocol.StateResponse](
      engine = engine.core,
      playAuth = playAuth,
      matchmakerKey = matchmakerKey,
      signIn = Html.signIn,
      live = live
    ) {

    protected def stateOf(m: TicTacToeMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def gameTitle: String = "tic-tac-toe"

    protected def page(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String],
        publicView: Boolean
    ): String = Html.board(
      matchId = matchId,
      title = title,
      state = state,
      login = login,
      liveUrl = liveUrl,
      publicView = publicView
    )

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        parse[Protocol.MoveRequest](request.body) match {
            case Left(why) => error(400, why)
            case Right(move) =>
                asPlayer(request)(caller =>
                    engine.move(matchId = matchId, cognitoId = caller, cell = move.cell).map(moved)
                )
        }
}
