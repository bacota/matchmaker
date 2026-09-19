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

    test("a Challenge round-trips its Instant and Duration fields") {
        val challenge = CharacterChallenge(
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
        assertEquals(read[Challenge](write(challenge)), challenge)
    }

    test("a Duration is seconds and an Instant is ISO-8601") {
        val json = ujson.read(
          write(
            CharacterChallenge(
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
    // merged Challenge reader, so a discriminator that did not survive being a field of another
    // object would show up only when the challenges page was loaded.
    test("a ChallengeSummary round-trips both kinds of challenge, keeping the subtype") {
        val character = ChallengeSummary(
          CharacterChallenge(
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
        val plain = ChallengeSummary(
          PlainChallenge(
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

        assertEquals(read[ChallengeSummary](write(character)), character)
        assertEquals(read[ChallengeSummary](write(plain)), plain)
        // The subtype is what decides whether the UI has a character to accept with, so assert it
        // rather than trusting equality alone to have compared it.
        assert(read[ChallengeSummary](write(character)).challenge.isInstanceOf[CharacterChallenge])
        assert(read[ChallengeSummary](write(plain)).challenge.isInstanceOf[PlainChallenge])

        // A list of both, which is the response shape rather than a single summary.
        val both = List(character, plain)
        assertEquals(read[List[ChallengeSummary]](write(both)), both)
    }

    // What an invitational challenge looks like on the wire: the flag that closes it, and the rows
    // saying who may accept it. Both are new in V22, and both have defaults -- which is the catch
    // below.
    test("a ChallengeSummary carries a closed challenge and its invitations") {
        val summary = ChallengeSummary(
          PlainChallenge(
            ChallengeId(1),
            PlayerId(2),
            "just us",
            start = None,
            timeLimit = None,
            settings = "{}",
            gameId = GameId(3),
            gameRoleId = GameRoleId(4),
            isOpen = false
          ),
          acceptances = 1,
          takenRoles = Seq(GameRoleId(4)),
          // With a role and without: a seat held for this player, and an invitation to any free one.
          invitations = Seq(
            Invitation(GameId(3), ChallengeId(1), PlayerId(5), Some(GameRoleId(6))),
            Invitation(GameId(3), ChallengeId(1), PlayerId(7))
          )
        )

        val decoded = read[ChallengeSummary](write(summary))
        assertEquals(decoded, summary)
        // Asserted rather than left to equality: these two are what decide whether the UI offers an
        // Accept at all, and to whom.
        assert(!decoded.challenge.isOpen)
        assertEquals(decoded.invitations.map(_.gameRoleId), Seq(Some(GameRoleId(6)), None))
    }

    // upickle omits a field whose value equals its default, so `isOpen = true` and no invitations
    // write nothing at all -- which is the wire format an older browser sends, and what every
    // challenge created before V22 amounts to. Reading that back as "open, nobody invited" is the
    // only answer that keeps those challenges joinable.
    test("an open challenge says nothing about being open, and reads back open") {
        val open = ChallengeSummary(
          PlainChallenge(
            ChallengeId(1),
            PlayerId(2),
            "anyone?",
            start = None,
            timeLimit = None,
            settings = "{}",
            gameId = GameId(3),
            gameRoleId = GameRoleId(4)
          ),
          acceptances = 1
        )
        val json = ujson.read(write(open))

        assertEquals(json("challenge").obj.get("isOpen"), None)
        assertEquals(json.obj.get("invitations"), None)

        // And the other direction: JSON with neither field is an open challenge nobody was invited to.
        val fromOlderClient = read[ChallengeSummary](write(open))
        assert(fromOlderClient.challenge.isOpen)
        assert(fromOlderClient.invitations.isEmpty)
    }

    // The two halves of an invitation as the API passes them around: `Invite` is what a caller asks
    // for, `ChallengeInvitation` is what a player's own invitations list answers with.
    test("an Invite and a ChallengeInvitation round-trip, with and without a role") {
        val asked = Invite(PlayerId(1), Some(GameRoleId(2)))
        val anySeat = Invite(PlayerId(3))
        assertEquals(read[Invite](write(asked)), asked)
        assertEquals(read[Invite](write(anySeat)), anySeat)

        val listed = ChallengeInvitation(
          Invitation(GameId(1), ChallengeId(2), PlayerId(3), Some(GameRoleId(4))),
          gameName = "Chess",
          challengerNickname = "ada",
          message = "best of three",
          roleName = Some("white")
        )
        val unnamed = listed.copy(
          invitation = listed.invitation.copy(gameRoleId = None),
          roleName = None
        )
        assertEquals(read[ChallengeInvitation](write(listed)), listed)
        assertEquals(read[ChallengeInvitation](write(unnamed)), unnamed)
        // roleName is Some exactly when the invitation names a role, which is what the row on the
        // home page reads to say "as white" or nothing at all.
        assertEquals(read[ChallengeInvitation](write(unnamed)).roleName, None)
    }

    test("a ChallengeSummary nests the challenge rather than flattening it") {
        val json = ujson.read(
          write(
            ChallengeSummary(
              PlainChallenge(
                ChallengeId(1),
                PlayerId(2),
                "m",
                None,
                None,
                "{}",
                GameId(3),
                gameRoleId = GameRoleId(4)
              ),
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
