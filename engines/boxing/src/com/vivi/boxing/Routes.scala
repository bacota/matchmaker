package com.vivi.boxing

import com.vivi.engine.ErrorText
import upickle.default.write
import com.vivi.engine.{EngineRequest, EngineResponse, EngineRoutes, Live, LoginConfig, PlayAuth}
import Protocol.given

/** The engine's HTTP surface: the routes every engine serves, which are [[EngineRoutes]]'s, what a round plan looks
  * like on the wire, and the routes only this game has — the fighters page, and building, listing, editing and giving
  * away fighters.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String], live: Option[Live] = None)
    extends EngineRoutes[Bout, Corner, Protocol.StateResponse](
      engine = engine.core,
      playAuth = playAuth,
      matchmakerKey = matchmakerKey,
      signIn = Html.signIn,
      live = live
    ) {

    protected def stateOf(m: Bout, corner: Option[Corner]): Protocol.StateResponse = engine.stateOf(m, corner)

    protected def gameTitle: String = "boxing"

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
        parse[Protocol.PlanRequest](request.body) match {
            case Left(why) => error(400, why)
            case Right(p) =>
                asPlayer(request)(caller =>
                    engine
                        .plan(
                          matchId = matchId,
                          cognitoId = caller,
                          allocation = Allocation(offense = p.offense, defense = p.defense, power = p.power)
                        )
                        .map(moved)
                )
        }

    override protected def extra: PartialFunction[(String, List[String]), EngineRequest => EngineResponse] = {
        // How the game is played, for anybody: nothing in it is anybody's.
        case ("GET", "rules" :: Nil) =>
            _ => EngineResponse(status = 200, body = Html.rulesPage, contentType = "text/html; charset=utf-8")

        // The fighters page, served to anyone for the reason the play page is: a browser navigation
        // carries no token, so the page is the shell that signs the player in and then fetches and
        // posts with one. It shows nothing that is anybody's.
        case ("GET", "fighters" :: Nil) =>
            _ =>
                EngineResponse(
                  status = 200,
                  body = Html.fightersPage(
                    playAuth.login,
                    Protocol.BuildRules(budget = Fighter.Budget, min = Fighter.Min, max = Fighter.Max)
                  ),
                  contentType = "text/html; charset=utf-8"
                )

        // The signed-in player's fighters, as matchmaker has them.
        case ("GET", "fighters" :: "mine" :: Nil) =>
            request =>
                asPlayer(request)(caller =>
                    engine.fightersOf(caller).map(fs => EngineResponse(status = 200, body = write(fs)))
                )

        // A new fighter, built for whoever signed in and reported to matchmaker. Answered 201 with
        // the character id matchmaker gave it.
        case ("POST", "fighters" :: Nil) =>
            request =>
                parse[Protocol.BuildRequest](request.body) match {
                    case Left(why) => error(400, why)
                    case Right(b) =>
                        asPlayer(request)(caller =>
                            engine
                                .buildFighter(caller, b)
                                .map(built => EngineResponse(status = 201, body = write(built)))
                        )
                }

        // Editing one of the signed-in player's fighters — its name and description — and giving one
        // away. Matchmaker is told of both, and decides whether the fighter is theirs to change.
        case ("PUT", "fighters" :: characterId :: Nil) =>
            request =>
                withFighter(
                  request = request,
                  characterId = characterId,
                  body = parse[Protocol.EditRequest](request.body)
                ) { (caller, id, r) =>
                    engine
                        .editFighter(
                          cognitoId = caller,
                          characterId = id,
                          newName = r.name,
                          description = r.description
                        )
                        .map(done => EngineResponse(status = 200, body = write(done)))
                }

        case ("PUT", "fighters" :: characterId :: "owner" :: Nil) =>
            request =>
                withFighter(
                  request = request,
                  characterId = characterId,
                  body = parse[Protocol.GiveRequest](request.body)
                ) { (caller, id, r) =>
                    engine
                        .giveFighter(cognitoId = caller, characterId = id, toNickname = r.toNickname)
                        .map(done => EngineResponse(status = 200, body = write(done)))
                }
    }

    /* A route about one of the caller's fighters: its id, its body, and who is asking. */
    private def withFighter[A](request: EngineRequest, characterId: String, body: Either[String, A])(
        f: (String, Long, A) => Either[com.vivi.engine.Refusal, EngineResponse]
    ): EngineResponse =
        (characterId.toLongOption, body) match {
            case (None, _)            => error(400, ErrorText.request.notAFighterId(characterId))
            case (_, Left(why))       => error(400, why)
            case (Some(id), Right(a)) => asPlayer(request)(caller => f(caller, id, a))
        }
}
