package com.vivi.rps

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.engine.{InMemoryMatchStore, RecordingMatchmaker}

/** The JSON a match is stored as, pinned.
  *
  * DynamoDB holds every match as this JSON in one attribute, and a match in progress outlives a deploy. So the shape is
  * a contract with matches already stored: a renamed, nested or newly required field would make them unreadable, and
  * nothing else would notice until a player's next move failed. A deliberate change to the shape means changing this
  * literal — and either defaulting the new field or migrating the stored matches.
  */
class StoredMatchSpec extends FunSuite {

    // One line in storage; broken here only to be readable.
    private val stored =
        """|{"matchId":"m-1","seats":[{"side":"One","cognitoId":"sub-alice","participantId":11},{"side":"Two",
          |"cognitoId":"sub-bob","participantId":22}],"throws":[{"participantId":11,"shape":"Rock",
          |"takenAt":"2026-01-01T00:00:01Z","startedAt":"2026-01-01T00:00:00Z"}],"isPublic":true,"isCompleted":false,
          |"createdAt":"2026-01-01T00:00:00Z","moveCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/moves",
          |"resultsCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/results"}""".stripMargin.replace("\n", "")

    /** A match partway through, on a clock that ticks a second per read so that every timestamp differs. */
    private def played(): RpsMatch = {
        val tick = AtomicLong(0)
        val store = InMemoryMatchStore[RpsMatch]()
        val engine =
            Engine(
              store = store,
              matchmaker = RecordingMatchmaker(),
              baseUrl = "http://engine.test",
              now = () => Instant.parse("2026-01-01T00:00:00Z").plusSeconds(tick.getAndIncrement())
            )
        engine.createGame(
          Protocol.CreateGameRequest(
            matchId = "m-1",
            gameName = "rock-paper-scissors",
            isPublic = true,
            parameters = Map.empty,
            settings = "{}",
            timeLimitSeconds = Some(600),
            players = List(
              Protocol.EnginePlayer(
                cognitoId = "sub-alice",
                participantId = 11L,
                role = Some("One"),
                characterId = None,
                characterState = None
              ),
              Protocol.EnginePlayer(
                cognitoId = "sub-bob",
                participantId = 22L,
                role = Some("Two"),
                characterId = None,
                characterState = None
              )
            ),
            moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
            resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
          )
        )
        engine.move(matchId = "m-1", cognitoId = "sub-alice", shape = Shape.Rock)
        store.get("m-1").get
    }

    test("a match is stored as the JSON matches already in DynamoDB are written in") {
        assertEquals(write(played()), stored)
    }

    test("a match already stored reads back as the same match") {
        assertEquals(read[RpsMatch](stored), played())
    }
}
