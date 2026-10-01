package com.vivi.rps

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a throw looks like
  * on the wire.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String])
    extends EngineRoutes[RpsMatch, Seat, Protocol.StateResponse](engine.core, playAuth, matchmakerKey, Html.signIn) {

    protected def stateOf(m: RpsMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def page(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        publicView: Boolean
    ): String = Html.board(matchId, state, login, publicView)

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
