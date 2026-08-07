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
      externalId = "secret",
      minPlayers = 2,
      maxPlayers = 4
    )

    val decoded = read[Game](write(game))
    assertEquals(decoded.gameId, game.gameId)
    assertEquals(decoded.roles, game.roles)
    assertEquals(decoded.parameters.size, 1)
    assertEquals(decoded.parameters.head.asInstanceOf[GameParameter[String]].defaultValue, Some("default"))
  }

  test("an OpenChallenge round-trips its Short, Instant and Duration fields") {
    val challenge = OpenChallenge(
      ChallengeId(1),
      PlayerId(2),
      "message",
      numberOfPlayers = 3.toShort,
      start = Some(Instant.ofEpochSecond(1000)),
      timeLimit = Some(Duration.ofSeconds(3600)),
      settings = "{}",
      gameId = GameId(4),
      characterId = CharacterId(5)
    )
    assertEquals(read[OpenChallenge](write(challenge)), challenge)
  }

  test("a Duration is seconds and an Instant is ISO-8601") {
    val json = ujson.read(
      write(
        OpenChallenge(
          ChallengeId(1),
          PlayerId(2),
          "m",
          1.toShort,
          Some(Instant.parse("2026-01-01T00:00:00Z")),
          Some(Duration.ofMinutes(2)),
          "{}",
          GameId(3),
          CharacterId(4)
        )
      )
    )
    assertEquals(json("timeLimit").num.toLong, 120L)
    assertEquals(json("start").str, "2026-01-01T00:00:00Z")
  }

  test("a MatchSummary round-trips") {
    val summary = MatchSummary(
      GameId(1),
      MatchId("m"),
      "game",
      "description",
      completed = false,
      start = Instant.ofEpochSecond(1000),
      due = Some(Instant.ofEpochSecond(2000)),
      pending = true,
      participantId = ParticipantId(5),
      characterId = CharacterId(6)
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
