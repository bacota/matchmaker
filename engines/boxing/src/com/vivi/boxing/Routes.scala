package com.vivi.boxing

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a round plan looks
  * like on the wire.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[Bout, Corner, Protocol.StateResponse](
      engine.core,
      playAuth,
      matchmakerKey,
      Html.signIn,
      live
    ) {

    protected def stateOf(m: Bout, corner: Option[Corner]): Protocol.StateResponse = engine.stateOf(m, corner)

    protected def page(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String],
        publicView: Boolean
    ): String = Html.board(matchId, state, login, liveUrl, publicView)

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        parse[Protocol.PlanRequest](request.body) match {
            case Left(why) => error(400, why)
            case Right(p) =>
                asPlayer(request)(caller =>
                    engine.plan(matchId, caller, Allocation(p.offense, p.defense, p.power)).map(moved)
                )
        }
}
