package com.vivi.matchmaker.api

import java.time.{Duration, Instant}
import munit.FunSuite
import upickle.default.{read, write}
import com.vivi.matchmaker.model._
import Json.given
import TournamentJson.given

class JsonSpec extends FunSuite {

    test("ids are transparent numbers on the wire") {
        assertEquals(write(GameId(7)), "7")
        assertEquals(write(MatchId(12)), "12")
        assertEquals(read[CharacterId]("42"), CharacterId(42))
    }

    test("a Game round-trips, including its existential parameters") {
        val game = Game(
          GameId(1),
          GameType.Character,
          "name",
          "name",
          "description",
          "url",
          active = true,
          roles = Seq(GameRole(GameRoleId(2), GameId(1), "role", roleType = RoleType.Required, displayName = "role")),
          parameters = Seq(
            GameParameter[String](
              GameId(1),
              GameParameterId(3),
              "parameter",
              Some("default"),
              Seq(GameParameterValue(GameId(1), GameParameterId(3), "default")),
              "Parameter"
            )
          ),
          externalId = "secret"
        )

        val decoded = read[Game](write(game))
        assertEquals(decoded.gameId, game.gameId)
        assertEquals(decoded.roles, game.roles)
        assertEquals(decoded.parameters.size, 1)
        assertEquals(decoded.parameters.head.asInstanceOf[GameParameter[String]].defaultValue, Some("default"))
        assertEquals(decoded.parameters.head.displayName, "Parameter")
    }

    // Unimportant roles travel with the game, and a game written before they existed keeps ratings by role.
    test("a game says whether its roles are unimportant, and one that does not say has important ones") {
        val game = Game(GameId(1), GameType.Plain, "n", "n", "d", "u", true, Seq.empty, Seq.empty, "boxing")
        assert(read[Game](write(game.copy(unimportantRoles = true))).unimportantRoles)
        val older = ujson.write(ujson.Obj.from(ujson.read(write(game)).obj.toSeq.filterNot(_._1 == "unimportantRoles")))
        assert(!read[Game](older).unimportantRoles)
    }

    // The key rides in the save request beside the game's fields and is read apart from them, and a
    // game written out never has one to write: there is no field for it.
    test("a game save carries its API key beside the game, and a game never writes one") {
        val game = Game(GameId(1), GameType.Plain, "n", "n", "d", "u", true, Seq.empty, Seq.empty, "boxing")
        val body = ujson.write(ujson.Obj.from(ujson.read(write(game)).obj.toSeq :+ ("apiKey" -> ujson.Str("s3cret"))))

        assertEquals(read[Game](body).externalId, "boxing")
        assertEquals(read[Json.GameApiKeyField](body).apiKey, Some("s3cret"))
        assertEquals(read[Json.GameApiKeyField](write(game)).apiKey, None)
        assert(!write(game.copy(hasApiKey = true)).contains("s3cret"))
        assert(!write(game).contains("apiKey"))
    }

    /* Preferences and answers are plain objects, and must stay plain.
     *
     * upickle tags a case class that has *any* supertype with a `$type` discriminator -- sealed or
     * not. Giving `SeatNotifications` a parent shared with the chain's other answers, which is the
     * obvious way to let one policy ask either of them, silently rewrote the shape of this route's
     * body. Round-tripping cannot see it: write-then-read agrees with itself. Only JSON
     * written by hand, as a client writes it, shows it up -- which is what this asserts.
     */
    test("a seat's answers are an untagged object") {
        val seat = write(SeatNotifications.all(true))

        assert(!seat.contains("$type"), seat)

        // And so JSON with no tag reads back, which is what a client sends.
        assertEquals(
          read[SeatNotifications]("""{"matchStarted":true,"turnTaken":false,"yourTurn":true,"matchEnded":false}"""),
          SeatNotifications(matchStarted = true, turnTaken = false, yourTurn = true, matchEnded = false)
        )
        // Four fields, not eleven: the kinds about a challenge are not a seat's to answer, and a
        // body naming them is not a seat's settings.
        assertEquals(ujson.read(seat).obj.keySet, Set("matchStarted", "turnTaken", "yourTurn", "matchEnded"))
    }

    // What POST /games/:id/challenges takes. Its Option fields are the bare value when there is one, and absent when
    // there is none -- a client that omits one is offering what it means.
    test("a ChallengeRequest round-trips, and reads back its defaults when they are left out") {
        val request = ChallengeRequest(
          characterId = Some(CharacterId(1)),
          poolSize = Some(3),
          roundDuration = Some(Duration.ofHours(2)),
          gameSettings = """{"rounds":"5"}""",
          live = true,
          friendly = false,
          isPublic = true,
          start = Some(Instant.ofEpochSecond(1000)),
          autoStart = true,
          open = false,
          invitations = List(PlayerId(2), PlayerId(3))
        )
        assertEquals(read[ChallengeRequest](write(request)), request)
        assertEquals(ujson.read(write(request))("roundDuration").num.toLong, 7200L)
        assertEquals(read[ChallengeRequest]("{}"), ChallengeRequest())
    }

    // What GET /games/:id/challenges actually returns: the tournament as a list shows it, its entries and invitations.
    test("a ChallengeSummary round-trips its tournament, entries and invitations") {
        val tournament = Tournament(
          GameId(1),
          TournamentId(2),
          "ada's challenge",
          "",
          PlayerId(3),
          TournamentModelId("challenge"),
          2,
          TournamentSettings(
            autoRounds = true,
            invitational = true,
            roundDuration = Some(Duration.ofMinutes(30)),
            gameSettings = """{"rounds":"4"}""",
            start = Some(Instant.ofEpochSecond(5000)),
            chooseRoles = true,
            maxEntrants = Some(2),
            autoStart = true
          ),
          TournamentStatus.Open
        )
        val summary = ChallengeSummary(
          TournamentSummary(tournament, "Chess", "ada", 1, isOwner = false, entered = false, invited = true),
          List(
            Entrant(GameId(1), TournamentId(2), EntrantId(4), Some(PlayerId(3)), None, 1500, Instant.ofEpochSecond(100))
          ),
          List(PlayerName(PlayerId(5), "grace"))
        )
        assertEquals(read[ChallengeSummary](write(summary)), summary)
    }

    test("a MatchSummary round-trips") {
        val summary = MatchSummary(
          GameId(1),
          MatchId(1),
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

    test("an opponent is written as the player it was before characters, with the character beside it") {
        val plain = Opponent(PublicPlayer(PlayerId(2), "bob"))
        val inCharacter =
            Opponent(PublicPlayer(PlayerId(2), "bob"), Some(CharacterName(CharacterId(7), GameId(1), "Joe")))
        assertEquals(ujson.read(write(plain)), ujson.Obj("playerId" -> 2, "nickname" -> "bob"))
        // Still a player to a client that reads one, and the same opponent back to one that knows the character.
        assertEquals(read[PublicPlayer](write(inCharacter)), plain.player)
        assertEquals(read[Opponent](write(inCharacter)), inCharacter)
        assertEquals(read[Opponent](write(plain)), plain)
    }

    test("a Player and a Character round-trip") {
        val player = Player(PlayerId(1), "nickname", isAdmin = true, externalId = "sub-1")
        assertEquals(read[Player](write(player)), player)

        val character = Character[String](CharacterId(1), GameId(2), "name", "description", "state", Some(PlayerId(3)))
        assertEquals(read[Character[String]](write(character)), character)
    }
}
