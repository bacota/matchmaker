package com.vivi.stratego

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a move looks like
  * on the wire — a setup or a piece moved, both posted to the one moves route, since both are a turn.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[StrategoMatch, Seat, Protocol.StateResponse](
      engine.core,
      playAuth,
      matchmakerKey,
      Html.signIn,
      live
    ) {

    protected def stateOf(m: StrategoMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def page(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String],
        publicView: Boolean
    ): String = Html.board(matchId, state, login, liveUrl, publicView)

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        parse[Protocol.MoveRequest](request.body) match {
            case Left(why) => error(400, why)
            case Right(Protocol.MoveRequest(Some(ranks), None, None)) =>
                asPlayer(request)(caller => engine.deploy(matchId, caller, ranks).map(moved))
            case Right(Protocol.MoveRequest(None, Some(from), Some(to))) =>
                asPlayer(request)(caller => engine.move(matchId, caller, from, to).map(moved))
            case Right(_) => error(400, """a move is either {"setup":[40 ranks]} or {"from":square,"to":square}""")
        }
}
