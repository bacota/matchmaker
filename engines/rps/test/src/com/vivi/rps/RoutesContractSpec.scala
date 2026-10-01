package com.vivi.rps

import com.vivi.engine.{
    EngineRequest,
    EngineResponse,
    InMemoryMatchStore,
    PlayAuth,
    RecordingMatchmaker,
    RoutesContract
}

/** The routes every engine serves, as rock-paper-scissors serves them. What a throw does is `RoutesSpec`'s. */
class RoutesContractSpec extends RoutesContract {

    protected def routes(playAuth: PlayAuth, matchmakerKey: Option[String]): EngineRequest => EngineResponse =
        Routes(
          Engine(InMemoryMatchStore[RpsMatch](), RecordingMatchmaker(), "http://engine.test"),
          playAuth,
          matchmakerKey
        )

    protected def createRequest(matchId: String, isPublic: Boolean): Protocol.CreateGameRequest =
        Protocol.CreateGameRequest(
          matchId = matchId,
          gameName = "rock-paper-scissors",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("One"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Two"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    protected def firstMove: String = """{"shape":"rock"}"""

    protected def aliceSeat: String = "One"

    protected def signInPrompt: String = "sign in to play"
}
