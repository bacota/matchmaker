package com.vivi.boxing

import java.time.Instant
import com.vivi.engine.{EngineRequest, EngineResponse, InMemoryMatchStore, Live, Matchmaker, PlayAuth, RoutesContract}

/** The routes every engine serves, as boxing serves them: both fighters built, so a plan is a legal first move. What a
  * plan does is `RoutesSpec`'s.
  */
class RoutesContractSpec extends RoutesContract {

    private val average = Fighter(5, 5, 5, 5, 5)

    protected def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live],
        matchmaker: Matchmaker,
        now: () => Instant
    ): EngineRequest => EngineResponse =
        Routes(
          Engine(InMemoryMatchStore[Bout](), matchmaker, "http://engine.test", now),
          playAuth,
          matchmakerKey,
          live
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
            Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), Some(101L), Some(Fighter.toState(average))),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Blue"), Some(202L), Some(Fighter.toState(average)))
          ),
          moveCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/moves"),
          resultsCallbackUrl = Some(s"http://matchmaker.test/games/1/matches/$matchId/results")
        )

    protected def firstMove: String = """{"offense":5,"defense":0,"power":0}"""

    protected def aliceSeat: String = "Red"

    protected def signInPrompt: String = "sign in to fight"
}
