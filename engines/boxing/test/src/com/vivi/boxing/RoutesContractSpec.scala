package com.vivi.boxing

import java.time.Instant
import com.vivi.engine.{EngineRequest, EngineResponse, InMemoryMatchStore, Live, Matchmaker, PlayAuth, RoutesContract}

/** The routes every engine serves, as boxing serves them: both fighters built, so a plan is a legal first move. What a
  * plan does is `RoutesSpec`'s.
  */
class RoutesContractSpec extends RoutesContract {

    private val average = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)

    protected def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live],
        matchmaker: Matchmaker,
        now: () => Instant
    ): EngineRequest => EngineResponse =
        Routes(
          engine = Engine(
            store = InMemoryMatchStore[Bout](),
            matchmaker = matchmaker,
            baseUrl = "http://engine.test",
            now = now
          ),
          playAuth = playAuth,
          matchmakerKey = matchmakerKey,
          live = live
        )

    protected def createRequest(matchId: String, isPublic: Boolean): Protocol.CreateGameRequest =
        Protocol.CreateGameRequest(
          matchId = matchId,
          gameName = "boxing",
          isPublic = isPublic,
          parameters = Map("rounds" -> "3"),
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("Red"),
              characterId = Some(101L),
              characterState = Some(Fighter.toState(average))
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Blue"),
              characterId = Some(202L),
              characterState = Some(Fighter.toState(average))
            )
          ),
          moveCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/moves"),
          resultsCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/results")
        )

    protected def firstMove: String = """{"offense":5,"defense":0,"power":0}"""

    protected def aliceSeat: String = "Red"

    protected def signInPrompt: String = "sign in to fight"

    override protected def noStateInPage: String = "\"state\":null}"
}
