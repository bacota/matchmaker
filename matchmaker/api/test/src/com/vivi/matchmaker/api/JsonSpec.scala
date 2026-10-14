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
          gameId = GameId(1),
          gameType = GameType.Character,
          name = "name",
          displayName = "name",
          description = "description",
          url = "url",
          isActive = true,
          roles = Seq(
            GameRole(
              gameRoleId = GameRoleId(2),
              gameId = GameId(1),
              name = "role",
              roleType = RoleType.Required,
              displayName = "role"
            )
          ),
          parameters = Seq(
            GameParameter[String](
              gameId = GameId(1),
              gameParameterId = GameParameterId(3),
              name = "parameter",
              defaultValue = Some("default"),
              values =
                  Seq(GameParameterValue(gameId = GameId(1), gameParameterId = GameParameterId(3), value = "default")),
              displayName = "Parameter"
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

    // A game without roles carries its range of players; a game with roles carries none, and writes none.
    test("a game without roles carries its range of players, and a game with roles has none") {
        val game = Game(
          gameId = GameId(1),
          gameType = GameType.Plain,
          name = "n",
          displayName = "n",
          description = "d",
          url = "u",
          isActive = true,
          roles = Seq.empty,
          parameters = Seq.empty,
          externalId = "boxing"
        )
        val open = game.copy(minPlayers = Some(3), maxPlayers = Some(8), moveType = MoveType.Simultaneous)
        val decoded = read[Game](write(open))
        assertEquals((decoded.minPlayers, decoded.maxPlayers), (Some(3), Some(8)))
        assert(decoded.isRoleless)
        val unlimited = read[Game](write(open.copy(maxPlayers = None)))
        assertEquals((unlimited.minPlayers, unlimited.maxPlayers), (Some(3), None))
        assert(!read[Game](write(game)).isRoleless)
        assert(!ujson.read(write(game)).obj.contains("minPlayers"))
    }

    // The key rides in the save request beside the game's fields and is read apart from them, and a
    // game written out never has one to write: there is no field for it.
    test("a game save carries its API key beside the game, and a game never writes one") {
        val game = Game(
          gameId = GameId(1),
          gameType = GameType.Plain,
          name = "n",
          displayName = "n",
          description = "d",
          url = "u",
          isActive = true,
          roles = Seq.empty,
          parameters = Seq.empty,
          externalId = "boxing"
        )
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
          read[SeatNotifications](
            """{"isNotifiedOfMatchStarted":true,"isNotifiedOfTurnTaken":false,"isNotifiedOfYourTurn":true,"isNotifiedOfMatchEnded":false}"""
          ),
          SeatNotifications(
            isNotifiedOfMatchStarted = true,
            isNotifiedOfTurnTaken = false,
            isNotifiedOfYourTurn = true,
            isNotifiedOfMatchEnded = false
          )
        )
        // Four fields, not eleven: the kinds about a challenge are not a seat's to answer, and a
        // body naming them is not a seat's settings.
        assertEquals(
          ujson.read(seat).obj.keySet,
          Set("isNotifiedOfMatchStarted", "isNotifiedOfTurnTaken", "isNotifiedOfYourTurn", "isNotifiedOfMatchEnded")
        )
    }

    // What POST /games/:id/challenges takes. Its Option fields are the bare value when there is one, and absent when
    // there is none -- a client that omits one is offering what it means.
    test("a ChallengeRequest round-trips, and reads back its defaults when they are left out") {
        val request = ChallengeRequest(
          characterId = Some(CharacterId(1)),
          poolSize = Some(3),
          roundDuration = Some(Duration.ofHours(2)),
          gameSettings = """{"rounds":"5"}""",
          isLive = true,
          isFriendly = false,
          isPublic = true,
          start = Some(Instant.ofEpochSecond(1000)),
          isAutoStarted = true,
          isOpen = false,
          invitations = List(PlayerId(2), PlayerId(3))
        )
        assertEquals(read[ChallengeRequest](write(request)), request)
        assertEquals(ujson.read(write(request))("roundDuration").num.toLong, 7200L)
        assertEquals(read[ChallengeRequest]("{}"), ChallengeRequest())
    }

    // What GET /games/:id/challenges actually returns: the tournament as a list shows it, its entries and invitations.
    test("a ChallengeSummary round-trips its tournament, entries and invitations") {
        val tournament = Tournament(
          gameId = GameId(1),
          tournamentId = TournamentId(2),
          name = "ada's challenge",
          description = "",
          owner = PlayerId(3),
          tournamentModelId = TournamentModelId("challenge"),
          defaultPoolSize = 2,
          settings = TournamentSettings(
            hasAutomaticRounds = true,
            isInvitational = true,
            roundDuration = Some(Duration.ofMinutes(30)),
            gameSettings = """{"rounds":"4"}""",
            start = Some(Instant.ofEpochSecond(5000)),
            hasRoleChoice = true,
            maxEntrants = Some(2),
            isAutoStarted = true
          ),
          status = TournamentStatus.Open
        )
        val summary = ChallengeSummary(
          summary = TournamentSummary(
            tournament = tournament,
            gameName = "Chess",
            ownerNickname = "ada",
            entrants = 1,
            isOwner = false,
            isEntered = false,
            isInvited = true
          ),
          entries = List(
            Entrant(
              gameId = GameId(1),
              tournamentId = TournamentId(2),
              entrantId = EntrantId(4),
              playerId = Some(PlayerId(3)),
              characterId = None,
              ratingAtEntry = 1500,
              enteredAt = Instant.ofEpochSecond(100)
            )
          ),
          invited = List(PlayerName(PlayerId(5), "grace"))
        )
        assertEquals(read[ChallengeSummary](write(summary)), summary)
    }

    test("a MatchSummary round-trips") {
        val summary = MatchSummary(
          gameId = GameId(1),
          matchId = MatchId(1),
          gameName = "game",
          description = "description",
          completedAt = Some(Instant.ofEpochSecond(3000)),
          isCancelled = false,
          isOwner = true,
          start = Instant.ofEpochSecond(1000),
          due = Some(Instant.ofEpochSecond(2000)),
          isPending = true,
          participantId = ParticipantId(5),
          characterId = Some(CharacterId(6))
        )
        assertEquals(read[MatchSummary](write(summary)), summary)
    }

    test("an opponent is written as the player it was before characters, with the character beside it") {
        val plain = Opponent(Some(PublicPlayer(PlayerId(2), "bob")))
        val inCharacter =
            Opponent(
              Some(PublicPlayer(PlayerId(2), "bob")),
              Some(CharacterName(characterId = CharacterId(7), gameId = GameId(1), name = "Joe"))
            )
        assertEquals(ujson.read(write(plain)), ujson.Obj("playerId" -> 2, "nickname" -> "bob"))
        // Still a player to a client that reads one, and the same opponent back to one that knows the character.
        assertEquals(read[PublicPlayer](write(inCharacter)), plain.player.get)
        assertEquals(read[Opponent](write(inCharacter)), inCharacter)
        assertEquals(read[Opponent](write(plain)), plain)
    }

    test("a seat nobody played is written as its character alone") {
        val nobody = Opponent(None, Some(CharacterName(characterId = CharacterId(7), gameId = GameId(1), name = "Joe")))
        assertEquals(ujson.read(write(nobody)).obj.keySet, Set("character"))
        assertEquals(read[Opponent](write(nobody)), nobody)
    }

    test("a Player and a Character round-trip") {
        val player = Player(playerId = PlayerId(1), nickname = "nickname", isAdmin = true, externalId = "sub-1")
        assertEquals(read[Player](write(player)), player)

        val character = Character[String](
          characterId = CharacterId(1),
          gameId = GameId(2),
          name = "name",
          description = "description",
          state = "state",
          playerId = Some(PlayerId(3))
        )
        assertEquals(read[Character[String]](write(character)), character)
    }
}
