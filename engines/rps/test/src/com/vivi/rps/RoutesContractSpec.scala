package com.vivi.rps

import java.time.Instant
import com.vivi.engine.{EngineRequest, EngineResponse, InMemoryMatchStore, Live, Matchmaker, PlayAuth, RoutesContract}

/** The routes every engine serves, as rock-paper-scissors serves them. What a throw does is `RoutesSpec`'s. */
class RoutesContractSpec extends RoutesContract {

    protected def routes(
        playAuth: PlayAuth,
        matchmakerKey: Option[String],
        live: Option[Live],
        matchmaker: Matchmaker,
        now: () => Instant
    ): EngineRequest => EngineResponse =
        Routes(
          engine = Engine(
            store = InMemoryMatchStore[RpsMatch](),
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
          gameName = "rock-paper-scissors",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("One"),
              characterId = None,
              characterState = None
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Two"),
              characterId = None,
              characterState = None
            )
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    protected def firstMove: String = """{"shape":"rock"}"""

    protected def aliceSeat: String = "One"

    protected def signInPrompt: String = "sign in to play"
}
