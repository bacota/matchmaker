package com.vivi.rps

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a throw looks like
  * on the wire.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[RpsMatch, Seat, Protocol.StateResponse](
      engine.core,
      playAuth,
      matchmakerKey,
      Html.signIn,
      live
    ) {

    protected def stateOf(m: RpsMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def gameTitle: String = "rock · paper · scissors"

    protected def page(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String],
        publicView: Boolean
    ): String = Html.board(matchId, title, state, login, liveUrl, publicView)

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        // Two ways to be a bad throw, answered the same way: a body that is not a move at all,
        // and one naming something nobody can throw. Both are the caller's mistake, and the
        // second is spelled out because "scissor" and "rocks" are what a hand-written client
        // sends.
        parse[Protocol.MoveRequest](request.body).flatMap(move =>
            Shape.parse(move.shape).toRight(s"'${move.shape}' is not a throw; expected rock, paper or scissors")
        ) match {
            case Left(why)    => error(400, why)
            case Right(shape) => asPlayer(request)(caller => engine.move(matchId, caller, shape).map(moved))
        }
}
