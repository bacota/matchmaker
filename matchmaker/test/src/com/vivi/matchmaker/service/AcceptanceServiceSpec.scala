package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, GameRepo, TestSession}

class AcceptanceServiceSpec extends PropertySuite {
  TestMigration.ensure()

  private val challengeService = TestServices.services.challenges
  private val acceptanceService = TestServices.services.acceptances
  private val registrationService = TestServices.services.registration

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private case class Fixture(owner: Player, game: Game, character: Character[String])

  private def makeFixture(nickname: String, externalId: String): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(
            GameId.unassigned, GameType.Character, "game", "description", "url", active = true,
            // Two roles, because every acceptance names one and no two acceptances of a challenge
            // may name the same one -- the challenger takes the first, the accepter the second.
            Seq(
              GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false),
              GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false)
            ),
            Seq.empty, genUniqueString.sample.get, 2, 4
          )
        )
        character <- new CharacterRepo[String](session).create(
          Character(CharacterId(0), game.gameId, "character", "description", "", Some(owner.playerId))
        )
      } yield Fixture(owner, game, character)
    }

  private def makeCharacterInGame(game: Game, nickname: String, externalId: String): IO[(Player, Character[String])] =
    TestSession.resource.use { session =>
      for {
        player <- registrationService.register(nickname, externalId)
        character <- new CharacterRepo[String](session).create(
          Character(CharacterId(0), game.gameId, "character", "description", "", Some(player.playerId))
        )
      } yield (player, character)
    }

  private def challengeFor(fixture: Fixture, numberOfPlayers: Int): OpenChallenge =
    CharacterOpenChallenge(
      ChallengeId(0), fixture.owner.playerId, "message", numberOfPlayers.toShort, None, None, "{}", fixture.game.gameId,
      fixture.character.characterId, isPublic = false, gameRoleId = fixture.game.roles.head.gameRoleId
    )

  private def setUp(nickname: String, externalId: String, accepterNickname: String, accepterExternalId: String) =
    for {
      fixture <- makeFixture(nickname, externalId)
      created <- challengeService.create(challengeFor(fixture, 3), externalId)
      accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
      (accepterPlayer, accepterCharacter) = accepter
      accepted <- challengeService.accept(
        fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), fixture.game.roles(1).gameRoleId, accepterExternalId
      )
    } yield (fixture, created, accepterPlayer, accepted)

  property("delete removes the acceptance when called by the acceptor") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (fixture, created, accepterPlayer, _) = setup
          _ <- acceptanceService.delete(fixture.game.gameId, created.challengeId, accepterPlayer.playerId, accepterExternalId)
          remaining <- TestSession.resource.use(session =>
            new AcceptanceRepo(session).read(fixture.game.gameId, created.challengeId, accepterPlayer.playerId)
          )
        } yield remaining.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete removes the acceptance when called by the challenger") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (fixture, created, accepterPlayer, _) = setup
          _ <- acceptanceService.delete(fixture.game.gameId, created.challengeId, accepterPlayer.playerId, externalId)
          remaining <- TestSession.resource.use(session =>
            new AcceptanceRepo(session).read(fixture.game.gameId, created.challengeId, accepterPlayer.playerId)
          )
        } yield remaining.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete rejects a caller who is neither the acceptor nor the challenger") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId, otherExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (fixture, created, accepterPlayer, _) = setup
          attempt <- acceptanceService.delete(fixture.game.gameId, created.challengeId, accepterPlayer.playerId, otherExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete fails when no acceptance exists for the challenge and player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture, 3), externalId)
          other <- registrationService.register(otherNickname, otherExternalId)
          attempt <- acceptanceService.delete(fixture.game.gameId, created.challengeId, other.playerId, otherExternalId).attempt
        } yield attempt match {
          case Left(_: NotFoundError) => true
          case _                      => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("mine lists the caller's own acceptances") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          set <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (_, created, accepterPlayer, _) = set
          mine <- acceptanceService.mine(accepterExternalId)
        } yield mine.exists(a => a.challengeId == created.challengeId && a.playerId == accepterPlayer.playerId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("mine does not show one player the acceptances of another") {
    // The route takes no player id, so this is really checking that the caller's identity is what
    // scopes the query — the property the whole design of this method rests on.
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          set <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (_, created, _, _) = set
          // Not the challenger: creating a challenge accepts it, so the challenger legitimately
          // has an acceptance of their own. An uninvolved player is the one who must see nothing.
          outsider <- registrationService.register(s"$nickname-outsider", s"$externalId-outsider")
          outsiderSees <- acceptanceService.mine(outsider.externalId)
        } yield !outsiderSees.exists(_.challengeId == created.challengeId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("mine rejects a caller with no player") {
    forAll(genUniqueString) { unknownExternalId =>
      val result = acceptanceService.mine(unknownExternalId).attempt.map {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
