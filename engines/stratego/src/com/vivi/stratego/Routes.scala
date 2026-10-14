package com.vivi.stratego

import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, and what a move looks like
  * on the wire — a setup, a piece moved or a concession, all posted to the one moves route, since each is a turn.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[StrategoMatch, Seat, Protocol.StateResponse](
      engine = engine.core,
      playAuth = playAuth,
      matchmakerKey = matchmakerKey,
      signIn = Html.signIn,
      live = live
    ) {

    protected def stateOf(m: StrategoMatch, seat: Option[Seat]): Protocol.StateResponse = engine.stateOf(m, seat)

    protected def gameTitle: String = "capture the flag"

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
            case Right(Protocol.MoveRequest(Some(ranks), None, None, None)) =>
                asPlayer(request)(caller =>
                    engine.deploy(matchId = matchId, cognitoId = caller, ranks = ranks).map(moved)
                )
            case Right(Protocol.MoveRequest(None, Some(from), Some(to), None)) =>
                asPlayer(request)(caller =>
                    engine.move(matchId = matchId, cognitoId = caller, from = from, to = to).map(moved)
                )
            case Right(Protocol.MoveRequest(None, None, None, Some(true))) =>
                asPlayer(request)(caller => engine.concede(matchId, caller).map(moved))
            case Right(_) =>
                error(
                  400,
                  """a move is {"setup":[40 ranks]}, {"from":square,"to":square} or {"concede":true}"""
                )
        }
}
