package com.vivi.matchmaker.api

import java.time.{Duration, Instant}
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.model._
import Json.given

class JsonSpec extends FunSuite {

  test("ids are transparent numbers on the wire") {
    assertEquals(write(GameId(7)), "7")
    assertEquals(write(MatchId("m-1")), "\"m-1\"")
    assertEquals(read[CharacterId]("42"), CharacterId(42))
  }

  test("a Game round-trips, including its existential parameters") {
    val game = Game(
      GameId(1),
      GameType.Character,
      "name",
      "description",
      "url",
      active = true,
      roles = Seq(GameRole(GameRoleId(2), GameId(1), "role", optional = false)),
      parameters = Seq(
        GameParameter[String](
          GameId(1),
          GameParameterId(3),
          "parameter",
          Some("default"),
          Seq(GameParameterValue(GameId(1), GameParameterId(3), "default"))
        )
      ),
      externalId = "secret"
    )

    val decoded = read[Game](write(game))
    assertEquals(decoded.gameId, game.gameId)
    assertEquals(decoded.roles, game.roles)
    assertEquals(decoded.parameters.size, 1)
    assertEquals(decoded.parameters.head.asInstanceOf[GameParameter[String]].defaultValue, Some("default"))
  }

  test("an OpenChallenge round-trips its Instant and Duration fields") {
    val challenge = CharacterOpenChallenge(
      ChallengeId(1),
      PlayerId(2),
      "message",
      start = Some(Instant.ofEpochSecond(1000)),
      timeLimit = Some(Duration.ofSeconds(3600)),
      settings = "{}",
      gameId = GameId(4),
      characterId = CharacterId(5),
      gameRoleId = GameRoleId(6)
    )
    assertEquals(read[OpenChallenge](write(challenge)), challenge)
  }

  test("a Duration is seconds and an Instant is ISO-8601") {
    val json = ujson.read(
      write(
        CharacterOpenChallenge(
          ChallengeId(1),
          PlayerId(2),
          "m",
          Some(Instant.parse("2026-01-01T00:00:00Z")),
          Some(Duration.ofMinutes(2)),
          "{}",
          GameId(3),
          CharacterId(4),
          gameRoleId = GameRoleId(5)
        )
      )
    )
    assertEquals(json("timeLimit").num.toLong, 120L)
    assertEquals(json("start").str, "2026-01-01T00:00:00Z")
  }

  // What GET /games/:id/challenges actually returns. The nested `challenge` goes through the
  // merged OpenChallenge reader, so a discriminator that did not survive being a field of another
  // object would show up only when the challenges page was loaded.
  test("an OpenChallengeSummary round-trips both kinds of challenge, keeping the subtype") {
    val character = OpenChallengeSummary(
      CharacterOpenChallenge(
        ChallengeId(1),
        PlayerId(2),
        "message",
        start = Some(Instant.ofEpochSecond(1000)),
        timeLimit = Some(Duration.ofSeconds(3600)),
        settings = "{}",
        gameId = GameId(4),
        characterId = CharacterId(5),
        isPublic = true,
        gameRoleId = GameRoleId(6)
      ),
      acceptances = 2,
      takenRoles = Seq(GameRoleId(6), GameRoleId(7))
    )
    val plain = OpenChallengeSummary(
      PlainOpenChallenge(
        ChallengeId(7),
        PlayerId(8),
        "message",
        start = None,
        timeLimit = None,
        settings = "{}",
        gameId = GameId(9),
        gameRoleId = GameRoleId(10)
      ),
      acceptances = 1,
      takenRoles = Seq(GameRoleId(10))
    )

    assertEquals(read[OpenChallengeSummary](write(character)), character)
    assertEquals(read[OpenChallengeSummary](write(plain)), plain)
    // The subtype is what decides whether the UI has a character to accept with, so assert it
    // rather than trusting equality alone to have compared it.
    assert(read[OpenChallengeSummary](write(character)).challenge.isInstanceOf[CharacterOpenChallenge])
    assert(read[OpenChallengeSummary](write(plain)).challenge.isInstanceOf[PlainOpenChallenge])

    // A list of both, which is the response shape rather than a single summary.
    val both = List(character, plain)
    assertEquals(read[List[OpenChallengeSummary]](write(both)), both)
  }

  test("an OpenChallengeSummary nests the challenge rather than flattening it") {
    val json = ujson.read(
      write(
        OpenChallengeSummary(
          PlainOpenChallenge(ChallengeId(1), PlayerId(2), "m", None, None, "{}", GameId(3), gameRoleId = GameRoleId(4)),
          acceptances = 2
        )
      )
    )
    assertEquals(json("acceptances").num.toInt, 2)
    assertEquals(json("challenge")("challengeId").num.toInt, 1)
  }

  test("a MatchSummary round-trips") {
    val summary = MatchSummary(
      GameId(1),
      MatchId("m"),
      "game",
      "description",
      completedAt = Some(Instant.ofEpochSecond(3000)),
      cancelled = false,
      isCreator = true,
      start = Instant.ofEpochSecond(1000),
      due = Some(Instant.ofEpochSecond(2000)),
      pending = true,
      participantId = ParticipantId(5),
      characterId = Some(CharacterId(6))
    )
    assertEquals(read[MatchSummary](write(summary)), summary)
  }

  test("a Player and a Character round-trip") {
    val player = Player(PlayerId(1), "nickname", isAdmin = true, externalId = "sub-1")
    assertEquals(read[Player](write(player)), player)

    val character = Character[String](CharacterId(1), GameId(2), "name", "description", "state", Some(PlayerId(3)))
    assertEquals(read[Character[String]](write(character)), character)
  }
}
