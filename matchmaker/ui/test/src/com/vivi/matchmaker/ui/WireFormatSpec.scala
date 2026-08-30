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

  // The match is what the game-engine flow hands back to the browser, and the urls on it are the
  // only reason the UI can send a player to the game at all — an absent publicUrl must stay
  // absent rather than becoming an empty string.
  test("a match round-trips with the game engine's urls") {
    val played = Match(
      gameId = GameId(7),
      matchId = MatchId("6b7c-uuid"),
      challengeId = ChallengeId(3),
      description = "a friendly game",
      completedAt = Some(Instant.parse("2030-01-01T10:42:00Z")),
      cancelled = false,
      start = Instant.parse("2030-01-01T10:00:00Z"),
      timeLimit = Some(Duration.ofMinutes(30)),
      settings = "{}",
      isPublic = true,
      statusUrl = Some("https://engine.example.com/status/1"),
      playUrl = Some("https://engine.example.com/play/1"),
      publicUrl = None
    )

    assertEquals(read[Match](write(played)), played)
  }

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
      externalId = "secret"
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
      cancelled = false,
      isCreator = true,
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

  // The challenges page decodes this and nothing else, and the nested `challenge` goes through
  // the merged OpenChallenge reader. A discriminator or field mapping that does not survive being
  // nested would break only here, in the browser, on the one screen that loads it.
  test("an OpenChallengeSummary round-trips both kinds of challenge, keeping the subtype") {
    val character = OpenChallengeSummary(
      CharacterOpenChallenge(
        challengeId = ChallengeId(1),
        challenger = PlayerId(2),
        message = "anyone?",
        start = Some(Instant.parse("2026-08-07T12:00:00Z")),
        timeLimit = Some(Duration.ofMinutes(5)),
        settings = "{}",
        gameId = GameId(3),
        characterId = CharacterId(9),
        isPublic = true,
        gameRoleId = GameRoleId(4)
      ),
      acceptances = 2,
      takenRoles = Seq(GameRoleId(4))
    )
    val plain = OpenChallengeSummary(
      PlainOpenChallenge(
        challengeId = ChallengeId(5),
        challenger = PlayerId(6),
        message = "a plain game",
        start = None,
        timeLimit = None,
        settings = "{}",
        gameId = GameId(7),
        gameRoleId = GameRoleId(8)
      ),
      acceptances = 1,
      takenRoles = Seq(GameRoleId(8))
    )

    // The list is the response shape; decoding them singly would not notice a broken Seq codec.
    val decoded = read[Seq[OpenChallengeSummary]](write(Seq(character, plain)))

    assertEquals(decoded, Seq(character, plain))
    // Which subtype came back decides whether the UI offers a character to accept with, so it is
    // asserted rather than left to equality.
    assert(decoded.head.challenge.isInstanceOf[CharacterOpenChallenge])
    assert(decoded(1).challenge.isInstanceOf[PlainOpenChallenge])
    // The count is what the Start button is shown or hidden on.
    assertEquals(decoded.map(_.acceptances), Seq(2, 1))
    assertEquals(decoded.head.challenge.start, character.challenge.start)
  }

  test("an absent optional stays absent rather than becoming a default") {
    val challenge = CharacterOpenChallenge(
      challengeId = ChallengeId(0),
      challenger = PlayerId(1),
      message = "anyone?",
      start = None,
      timeLimit = Some(Duration.ofMinutes(5)),
      settings = "{}",
      gameId = GameId(1),
      characterId = CharacterId(9),
      gameRoleId = GameRoleId(2)
    )

    val decoded = read[OpenChallenge](write(challenge))

    assertEquals(decoded.start, None)
    assertEquals(decoded.timeLimit, Some(Duration.ofSeconds(300)))
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
