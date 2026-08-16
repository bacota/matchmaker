package com.vivi.matchmaker.ui

import java.time.{Duration, Instant}
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.api.Json.given
import com.vivi.matchmaker.model._

/** The shared wire format, compiled for Scala.js.
  *
  * The point is not that upickle works. It is that these codecs and the `java.time` values they
  * depend on behave the same in the browser as on the server — `Instant.parse` in particular is
  * `scala-java-time`'s implementation here and the JDK's there, and the API's own `ApiGatewaySpec`
  * cannot notice a difference between them.
  */
class WireFormatSpec extends FunSuite {

  test("a game round-trips, including the existential parameters field") {
    // `parameters` is `Seq[GameParameter[_]]`, which no macro can derive; `Json` pins it to
    // String. This is the codec most likely to break, so it is the one exercised in full.
    val game = Game(
      gameId = GameId(7),
      gameType = GameType.Character,
      name = "Chess",
      description = "the usual",
      url = "https://example.com/chess",
      active = true,
      roles = Seq(GameRole(GameRoleId(1), GameId(7), "white", optional = false)),
      parameters = Seq(
        GameParameter(
          GameId(7),
          GameParameterId(2),
          "clock",
          Some("5+3"),
          Seq(GameParameterValue(GameId(7), GameParameterId(2), "10+0"))
        )
      ),
      externalId = "secret",
      minPlayers = 2,
      maxPlayers = 2
    )

    val decoded = read[Game](write(game))

    assertEquals(decoded.gameId, game.gameId)
    assertEquals(decoded.roles, game.roles)
    assertEquals(decoded.parameters.size, 1)
    assertEquals(decoded.parameters.head.asInstanceOf[GameParameter[String]].defaultValue, Some("5+3"))
  }

  // What the admin's "Add a game" form actually posts: no id yet, and both existential-typed
  // collections empty. An empty `parameters` is the case a codec pinned to GameParameter[String]
  // could plausibly get wrong without the round-trip above noticing.
  test("a game being created encodes with an unassigned id and no roles or parameters") {
    val game = Game(
      gameId = GameId.unassigned,
      gameType = GameType.Plain,
      name = "Go",
      description = "19x19",
      url = "https://example.com/go",
      active = true,
      roles = Seq.empty,
      parameters = Seq.empty,
      externalId = "generated-secret",
      minPlayers = 2,
      maxPlayers = 2
    )

    val json = ujson.read(write(game))
    assertEquals(json("gameId").num.toInt, 0)
    assertEquals(json("roles").arr.length, 0)
    assertEquals(json("parameters").arr.length, 0)

    val decoded = read[Game](write(game))
    assertEquals(decoded.gameId, GameId.unassigned)
    assertEquals(decoded.parameters, Seq.empty)
  }

  test("ids are transparent on the wire, not wrapped in an object") {
    // A UI that sent {"value": 7} where the server expects 7 would fail only at runtime, so the
    // encoding is asserted rather than just the round trip.
    assertEquals(write(GameId(7)), "7")
    assertEquals(write(MatchId("m-1")), "\"m-1\"")
    assertEquals(read[PlayerId]("42"), PlayerId(42))
  }

  test("an instant survives the trip through scala-java-time") {
    val summary = MatchSummary(
      gameId = GameId(1),
      matchId = MatchId("m-1"),
      gameName = "Chess",
      description = "d",
      completed = false,
      start = Instant.parse("2026-08-07T12:00:00Z"),
      due = Some(Instant.parse("2026-08-08T12:00:00Z")),
      pending = true,
      participantId = ParticipantId(3),
      characterId = Some(CharacterId(4))
    )

    val decoded = read[MatchSummary](write(summary))

    assertEquals(decoded.start, summary.start)
    assertEquals(decoded.due, summary.due)
  }

  test("an absent optional stays absent rather than becoming a default") {
    val challenge = CharacterOpenChallenge(
      challengeId = ChallengeId(0),
      challenger = PlayerId(1),
      message = "anyone?",
      numberOfPlayers = 2,
      start = None,
      timeLimit = Some(Duration.ofMinutes(5)),
      settings = "{}",
      gameId = GameId(1),
      characterId = CharacterId(9)
    )

    val decoded = read[OpenChallenge](write(challenge))

    assertEquals(decoded.start, None)
    assertEquals(decoded.timeLimit, Some(Duration.ofSeconds(300)))
    assertEquals(decoded.numberOfPlayers, 2.toShort)
  }
}

class FormatSpec extends FunSuite {

  test("an instant is shown to the minute and marked as UTC") {
    assertEquals(Format.instant(Instant.parse("2026-08-07T12:34:56Z")), "2026-08-07 12:34:56 UTC")
  }

  test("sub-second precision is dropped rather than shown") {
    assertEquals(Format.instant(Instant.parse("2026-08-07T12:34:56.789Z")), "2026-08-07 12:34:56 UTC")
  }
}
