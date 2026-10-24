package com.vivi.matchmaker.ui

import java.time.{Duration, Instant}
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.api.Json
import com.vivi.matchmaker.api.Json.given
import com.vivi.matchmaker.api.TournamentJson.given
import com.vivi.matchmaker.model._

/** The shared wire format, compiled for Scala.js.
  *
  * The point is not that upickle works. It is that these codecs and the `java.time` values they depend on behave the
  * same in the browser as on the server — `Instant.parse` in particular is `scala-java-time`'s implementation here and
  * the JDK's there, and the API's own `ApiGatewaySpec` cannot notice a difference between them.
  */
class WireFormatSpec extends FunSuite {

    // The match is what the game-engine flow hands back to the browser, and the urls on it are the
    // only reason the UI can send a player to the game at all — an absent publicUrl must stay
    // absent rather than becoming an empty string.
    test("a match round-trips with the game engine's urls") {
        val played = Match(
          gameId = GameId(7),
          matchId = MatchId(1),
          tournamentId = TournamentId(3),
          description = "a friendly game",
          completedAt = Some(Instant.parse("2030-01-01T10:42:00Z")),
          isCancelled = false,
          start = Some(Instant.parse("2030-01-01T10:00:00Z")),
          timeLimit = Some(Duration.ofMinutes(30)),
          settings = "{}",
          isPublic = true,
          statusUrl = Some("https://engine.example.com/status/1"),
          playUrl = Some("https://engine.example.com/play/1"),
          publicUrl = None
        )

        assertEquals(read[Match](write(played)), played)
    }

    // Every match is a tournament's, whose owner's it is; the match carries no owner of its own.
    test("a match round-trips, carrying its tournament and no creator") {
        val created = Match(
          gameId = GameId(7),
          matchId = MatchId(1),
          tournamentId = TournamentId(3),
          description = "round 1",
          completedAt = None,
          start = Some(Instant.parse("2030-01-01T10:00:00Z")),
          timeLimit = None,
          settings = "{}"
        )

        val json = ujson.read(write(created))
        assertEquals(json("tournamentId").num.toLong, 3L)
        assert(!json.obj.contains("creator"))
        assertEquals(read[Match](write(created)), created)
    }

    test("a game round-trips, including the existential parameters field") {
        // `parameters` is `Seq[GameParameter[_]]`, which no macro can derive; `Json` pins it to
        // String. This is the codec most likely to break, so it is the one exercised in full.
        val game = Game(
          gameId = GameId(7),
          gameType = GameType.Character,
          name = "Chess",
          displayName = "Chess",
          description = "the usual",
          url = "https://example.com/chess",
          isActive = true,
          roles = Seq(
            GameRole(
              gameRoleId = GameRoleId(1),
              gameId = GameId(7),
              name = "white",
              roleType = RoleType.Required,
              displayName = "white"
            )
          ),
          parameters = Seq(
            GameParameter(
              gameId = GameId(7),
              gameParameterId = GameParameterId(2),
              name = "clock",
              defaultValue = Some("5+3"),
              values =
                  Seq(GameParameterValue(gameId = GameId(7), gameParameterId = GameParameterId(2), value = "10+0")),
              displayName = "Clock"
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
          displayName = "Go",
          description = "19x19",
          url = "https://example.com/go",
          isActive = true,
          roles = Seq.empty,
          parameters = Seq.empty,
          externalId = "generated-secret"
        )

        val json = ujson.read(write(game))
        assertEquals(json("gameId").num.toInt, 0)
        assertEquals(json("roles").arr.length, 0)
        assertEquals(json("parameters").arr.length, 0)

        val decoded = read[Game](write(game))
        assertEquals(decoded.gameId, GameId.unassigned)
        assertEquals(decoded.parameters, Seq.empty)
    }

    test("a tournament model carries its form overrides as an object of words") {
        val model = TournamentModel(
          tournamentModelId = TournamentModelId("heats"),
          version = 3,
          name = "Heats",
          description = "",
          classification = None,
          poolSize = None,
          hasLateEntries = false,
          design = com.vivi.matchmaker.model.tournament.TournamentDesign(Nil),
          formOverrides = Map("labels.competition.poolSize" -> "Heat size")
        )

        assert(write(model).contains(""""formOverrides":{"labels.competition.poolSize":"Heat size"}"""), write(model))
        assertEquals(read[TournamentModel](write(model)), model)
    }

    test("ids are transparent on the wire, not wrapped in an object") {
        // A UI that sent {"value": 7} where the server expects 7 would fail only at runtime, so the
        // encoding is asserted rather than just the round trip.
        assertEquals(write(GameId(7)), "7")
        assertEquals(write(MatchId(12)), "12")
        assertEquals(read[PlayerId]("42"), PlayerId(42))
    }

    test("an instant survives the trip through scala-java-time") {
        val summary = MatchSummary(
          gameId = GameId(1),
          matchId = MatchId(1),
          gameName = "Chess",
          description = "d",
          completedAt = Some(Instant.parse("2026-08-09T09:30:00Z")),
          isCancelled = false,
          isOwner = true,
          start = Instant.parse("2026-08-07T12:00:00Z"),
          due = Some(Instant.parse("2026-08-08T12:00:00Z")),
          isPending = true,
          participantId = ParticipantId(3),
          characterId = Some(CharacterId(4))
        )

        val decoded = read[MatchSummary](write(summary))

        assertEquals(decoded.start, summary.start)
        assertEquals(decoded.due, summary.due)
        assertEquals(decoded.completedAt, summary.completedAt)
    }
}

class FormatSpec extends FunSuite {

    test("an instant is shown to the minute and marked as UTC") {
        assertEquals(Format.instant(Instant.parse("2026-08-07T12:34:56Z")), "2026-08-07 12:34:56 UTC")
    }

    test("sub-second precision is dropped rather than shown") {
        assertEquals(Format.instant(Instant.parse("2026-08-07T12:34:56.789Z")), "2026-08-07 12:34:56 UTC")
    }
    test("a seat's Elo is its rating as the match began, with the match's change when it was rated") {
        assertEquals(Format.elo(1500, None), "Elo 1500")
        assertEquals(Format.elo(1500, Some(16)), "Elo 1500 (+16)")
        assertEquals(Format.elo(1500, Some(-16)), "Elo 1500 (\u221216)")
        assertEquals(Format.elo(1500, Some(0)), "Elo 1500 (\u00b10)")
    }

    test("a result row's Elo round-trips, and its change is absent for a match that was not rated") {
        val rated = Json.ParticipantResultView(
          gameId = GameId(1),
          matchId = MatchId(1),
          participantId = ParticipantId(2),
          nickname = "alice",
          roleName = Some("First"),
          rank = Some(1),
          scores = Map.empty,
          isWinner = true,
          eloStart = 1500,
          eloDelta = Some(16)
        )
        assertEquals(read[Json.ParticipantResultView](write(rated)), rated)
        val friendly = rated.copy(eloDelta = None)
        assertEquals(read[Json.ParticipantResultView](write(friendly)), friendly)
    }

    test("numeric parameter values are offered low to high, not in text order") {
        assertEquals(Format.parameterValues(Seq("10", "25", "3", "12", "9")), Seq("3", "9", "10", "12", "25"))
    }

    test("decimals and negatives are numbers too") {
        assertEquals(Format.parameterValues(Seq("1.5", "-2", "10", "0.25")), Seq("-2", "0.25", "1.5", "10"))
    }

    test("parameter values that are not all numbers are offered in text order") {
        assertEquals(Format.parameterValues(Seq("5x5", "3x3", "4x4")), Seq("3x3", "4x4", "5x5"))
        assertEquals(Format.parameterValues(Seq("10", "fast", "3")), Seq("10", "3", "fast"))
    }
}
