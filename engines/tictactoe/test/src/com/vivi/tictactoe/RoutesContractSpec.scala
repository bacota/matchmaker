package com.vivi.tictactoe

import com.vivi.engine.{
    EngineRequest,
    EngineResponse,
    InMemoryMatchStore,
    PlayAuth,
    RecordingMatchmaker,
    RoutesContract
}

/** The routes every engine serves, as tic-tac-toe serves them. What a move does is `RoutesSpec`'s. */
class RoutesContractSpec extends RoutesContract {

    protected def routes(playAuth: PlayAuth, matchmakerKey: Option[String]): EngineRequest => EngineResponse =
        Routes(
          Engine(InMemoryMatchStore[TicTacToeMatch](), RecordingMatchmaker(), "http://engine.test"),
          playAuth,
          matchmakerKey
        )

    protected def createRequest(matchId: String, isPublic: Boolean): Protocol.CreateGameRequest =
        Protocol.CreateGameRequest(
          matchId = matchId,
          gameName = "tic-tac-toe",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("X"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("O"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    protected def firstMove: String = """{"cell":0}"""

    protected def aliceSeat: String = "X"

    protected def signInPrompt: String = "sign in to play"
}
