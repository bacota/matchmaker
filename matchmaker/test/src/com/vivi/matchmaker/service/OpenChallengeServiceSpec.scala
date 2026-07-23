package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{AcceptanceRepo, CharacterRepo, GameRepo, TestSession}

class OpenChallengeServiceSpec extends PropertySuite {
  TestMigration.ensure()

  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val challengeService = new OpenChallengeService[String](config)
  private val registrationService = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private case class Fixture(owner: Player, game: Game, character: Character[String])

  private def makeFixture(nickname: String, externalId: String, minPlayers: Int, maxPlayers: Int): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(GameId.unassigned, "game", "description", "url", active = true, Seq.empty, Seq.empty, genUniqueString.sample.get, minPlayers, maxPlayers)
        )
        character <- new CharacterRepo[String](session).create(
          Character(CharacterId(0), game.gameId, "character", "description", "", Some(owner.playerId))
        )
      } yield Fixture(owner, game, character)
    }

  private def challengeFor(fixture: Fixture, numberOfPlayers: Int): OpenChallenge =
    OpenChallenge(ChallengeId(0), fixture.owner.playerId, "message", numberOfPlayers.toShort, None, None, "{}", fixture.game.gameId, fixture.character.characterId)

  property("create creates a challenge when numberOfPlayers is in range and caller owns the character") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
      } yield created.numberOfPlayers == 3.toShort && created.characterId == fixture.character.characterId
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a caller who does not own the character") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, otherExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        attempt <- challengeService.create(challengeFor(fixture, 3), otherExternalId).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a numberOfPlayers below the game's minPlayers") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        attempt <- challengeService.create(challengeFor(fixture, 1), externalId).attempt
      } yield attempt match {
        case Left(_: ValidationError) => true
        case _                        => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a numberOfPlayers above the game's maxPlayers") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        attempt <- challengeService.create(challengeFor(fixture, 5), externalId).attempt
      } yield attempt match {
        case Left(_: ValidationError) => true
        case _                        => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete removes the challenge and its acceptances when authorized by the owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 3), externalId)
          accepterFixture <- makeFixture(accepterNickname, accepterExternalId, minPlayers = 2, maxPlayers = 4)
          _ <- TestSession.resource.use { session =>
            new AcceptanceRepo(session).create(
              Acceptance(created.challengeId, accepterFixture.owner.playerId, accepterFixture.game.gameId, accepterFixture.character.characterId)
            )
          }
          _ <- challengeService.delete(created.challengeId, externalId)
          remainingChallenge <- TestSession.resource.use(session => new com.vivi.matchmaker.persistence.OpenChallengeRepo(session).read(created.challengeId))
          remainingAcceptance <- TestSession.resource.use(session =>
            new AcceptanceRepo(session).read(created.challengeId, accepterFixture.owner.playerId)
          )
        } yield remainingChallenge.isEmpty && remainingAcceptance.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete rejects a caller who does not own the character") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, otherExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
        attempt <- challengeService.delete(created.challengeId, otherExternalId).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
