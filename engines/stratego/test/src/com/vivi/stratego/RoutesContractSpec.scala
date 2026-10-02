package com.vivi.stratego

import java.time.Instant
import upickle.default.write
import com.vivi.engine.{EngineRequest, EngineResponse, InMemoryMatchStore, Live, Matchmaker, PlayAuth, RoutesContract}
import Protocol.given

/** The routes every engine serves, as stratego serves them: the first move is Red's setup. What a move does is
  * `RoutesSpec`'s.
  */
class RoutesContractSpec extends RoutesContract {

    protected def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live],
        matchmaker: Matchmaker,
        now: () => Instant
    ): EngineRequest => EngineResponse =
        Routes(
          Engine(InMemoryMatchStore[StrategoMatch](), matchmaker, "http://engine.test", now),
          playAuth,
          matchmakerKey,
          live
        )

    protected def createRequest(matchId: String, isPublic: Boolean): Protocol.CreateGameRequest =
        Protocol.CreateGameRequest(
          matchId = matchId,
          gameName = "stratego",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer("sub-alice", 1L, Some("Red"), None, None),
            Protocol.EnginePlayer("sub-bob", 2L, Some("Blue"), None, None)
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    protected def firstMove: String = write(Protocol.MoveRequest(setup = Some(Armies.names(Armies.setup(Side.Red)))))

    protected def aliceSeat: String = "Red"

    protected def signInPrompt: String = "sign in to play"
}
