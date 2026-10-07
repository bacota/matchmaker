package com.vivi.boxing

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
        """|{"matchId":"m-1","corners":[{"side":"Red","cognitoId":"sub-alice","participantId":11,"characterId":101,
          |"fighter":{"strength":5,"speed":5,"agility":5,"workrate":5,"chin":5}},{"side":"Blue",
          |"cognitoId":"sub-bob","participantId":22,"characterId":202,"fighter":{"strength":5,"speed":5,
          |"agility":5,"workrate":5,"chin":5}}],"scheduledRounds":3,"plans":[{"participantId":11,"round":1,
          |"allocation":{"offense":5,"defense":0,"power":0},"takenAt":"2026-01-01T00:00:01Z",
          |"startedAt":"2026-01-01T00:00:00Z"},{"participantId":22,"round":1,"allocation":{"offense":0,"defense":5,
          |"power":0},"takenAt":"2026-01-01T00:00:02Z","startedAt":"2026-01-01T00:00:00Z"},{"participantId":11,
          |"round":2,"allocation":{"offense":2,"defense":2,"power":1},"takenAt":"2026-01-01T00:00:03Z",
          |"startedAt":"2026-01-01T00:00:02Z"}],"isPublic":true,"completed":false,
          |"createdAt":"2026-01-01T00:00:00Z","moveCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/moves",
          |"resultsCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/results"}""".stripMargin.replace("\n", "")

    /** A bout as it was stored while fighters were still built in their first bout, with the base url the build saved
      * them through. The field is gone and is ignored when read, so a bout already stored is still the bout it was.
      */
    private val storedWithMatchmakerUrl =
        stored.stripSuffix("}") + ""","matchmakerUrl":"http://matchmaker.test"}"""

    /** A match partway through, on a clock that ticks a second per read so that every timestamp differs. */
    private def played(): Bout = {
        val tick = AtomicLong(0)
        val store = InMemoryMatchStore[Bout]()
        val engine =
            Engine(
              store,
              RecordingMatchmaker(),
              "http://engine.test",
              () => Instant.parse("2026-01-01T00:00:00Z").plusSeconds(tick.getAndIncrement())
            )
        val average = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)
        engine.createGame(
          Protocol.CreateGameRequest(
            matchId = "m-1",
            gameName = "boxing",
            isPublic = true,
            parameters = Map("rounds" -> "3"),
            settings = "{}",
            timeLimitSeconds = Some(600),
            players = List(
              Protocol.EnginePlayer("sub-alice", 11L, Some("Red"), Some(101L), Some(Fighter.toState(average))),
              Protocol.EnginePlayer("sub-bob", 22L, Some("Blue"), Some(202L), Some(Fighter.toState(average)))
            ),
            moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
            resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
          )
        )
        engine.plan("m-1", "sub-alice", Allocation(5, 0, 0))
        engine.plan("m-1", "sub-bob", Allocation(0, 5, 0))
        engine.plan("m-1", "sub-alice", Allocation(2, 2, 1))
        store.get("m-1").get
    }

    test("a match is stored as the JSON matches already in DynamoDB are written in") {
        assertEquals(write(played()), stored)
    }

    test("a match already stored reads back as the same match") {
        assertEquals(read[Bout](stored), played())
    }

    test("a no-tie bout stores the flag and reads it back; one stored without it is an ordinary bout") {
        val untied = played().copy(noTie = true)
        assert(write(untied).endsWith(""","noTie":true}"""))
        assertEquals(read[Bout](write(untied)), untied)
        assert(!read[Bout](stored).noTie)
    }

    test("a bout stored with the matchmaker url fighters were once saved through reads back the same") {
        assertEquals(read[Bout](storedWithMatchmakerUrl), played())
    }
}
