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
          engine = Engine(
            store = InMemoryMatchStore[StrategoMatch](),
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
          gameName = "stratego",
          isPublic = isPublic,
          parameters = Map.empty,
          settings = "{}",
          timeLimitSeconds = None,
          players = List(
            Protocol.EnginePlayer(
              cognitoId = "sub-alice",
              participantId = 1L,
              role = Some("Red"),
              characterId = None,
              characterState = None
            ),
            Protocol.EnginePlayer(
              cognitoId = "sub-bob",
              participantId = 2L,
              role = Some("Blue"),
              characterId = None,
              characterState = None
            )
          ),
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    protected def firstMove: String = write(Protocol.MoveRequest(setup = Some(Armies.names(Armies.setup(Side.Red)))))

    protected def aliceSeat: String = "Red"

    protected def signInPrompt: String = "sign in to play"
}
