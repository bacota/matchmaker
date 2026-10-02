package com.vivi.boxing

import upickle.default.write
import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, what a round plan looks
  * like on the wire, and the two routes only this game has — the page a fighter is built on, and the build itself.
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

    override protected def extra: PartialFunction[(String, List[String]), EngineRequest => EngineResponse] = {
        // The build page, served to anyone for the reason the play page is: a browser navigation
        // carries no token, so the page is the shell that signs the player in and then posts the
        // build with one. It shows nothing that is anybody's.
        case ("GET", "fighters" :: "new" :: Nil) =>
            _ =>
                EngineResponse(
                  200,
                  Html.buildPage(playAuth.login, Protocol.BuildRules(Fighter.Budget, Fighter.Min, Fighter.Max)),
                  "text/html; charset=utf-8"
                )

        // A new fighter, built for whoever signed in and reported to matchmaker. Answered 201 with
        // the character id matchmaker gave it.
        case ("POST", "fighters" :: Nil) =>
            request =>
                parse[Protocol.BuildRequest](request.body) match {
                    case Left(why) => error(400, why)
                    case Right(b) =>
                        asPlayer(request)(caller =>
                            engine.buildFighter(caller, b).map(built => EngineResponse(201, write(built)))
                        )
                }
    }
}
