package com.vivi.boxing

import upickle.default.write
import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, what a round plan looks
  * like on the wire, and the one route only this game has — building a fighter.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String])
    extends EngineRoutes[Bout, Corner, Protocol.StateResponse](engine.core, playAuth, matchmakerKey, Html.signIn) {

    protected def stateOf(m: Bout, corner: Option[Corner]): Protocol.StateResponse = engine.stateOf(m, corner)

    protected def page(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        publicView: Boolean
    ): String = Html.board(matchId, state, login, publicView)

    protected def move(request: EngineRequest, matchId: String): EngineResponse =
        parse[Protocol.PlanRequest](request.body) match {
            case Left(why) => error(400, why)
            case Right(p) =>
                asPlayer(request)(caller =>
                    engine.plan(matchId, caller, Allocation(p.offense, p.defense, p.power)).map(moved)
                )
        }

    override protected def extra: PartialFunction[(String, List[String]), EngineRequest => EngineResponse] = {
        // Building the fighter in this corner, before its first round. Answered with the new state,
        // as a plan is.
        case ("POST", "matches" :: matchId :: "fighter" :: Nil) =>
            request =>
                parse[Protocol.BuildRequest](request.body) match {
                    case Left(why) => error(400, why)
                    case Right(b) =>
                        asPlayer(request) { caller =>
                            val fighter = Fighter(b.strength, b.speed, b.agility, b.workrate, b.chin)
                            engine.build(matchId, caller, fighter).map { built =>
                                EngineResponse(200, write(engine.stateOf(built, built.cornerFor(caller))))
                            }
                        }
                }
    }
}
