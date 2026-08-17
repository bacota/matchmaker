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

  private val challengeService = TestServices.services.challenges
  private val registrationService = TestServices.services.registration

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private case class Fixture(owner: Player, game: Game, character: Character[String])

  private def makeFixture(nickname: String, externalId: String, minPlayers: Int, maxPlayers: Int): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(GameId.unassigned, GameType.Character, "game", "description", "url", active = true, Seq.empty, Seq.empty, genUniqueString.sample.get, minPlayers, maxPlayers)
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
    CharacterOpenChallenge(ChallengeId(0), fixture.owner.playerId, "message", numberOfPlayers.toShort, None, None, "{}", fixture.game.gameId, fixture.character.characterId)

  property("create creates a challenge when numberOfPlayers is in range and caller owns the character") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
      } yield created match { case c: CharacterOpenChallenge => c.numberOfPlayers == 3.toShort && c.characterId == fixture.character.characterId; case _ => false }
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

  property("accept creates an acceptance when authorized and within capacity") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 3), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (accepterPlayer, accepterCharacter) = accepter
          accepted <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), accepterExternalId)
        } yield accepted.challengeId == created.challengeId &&
          accepted.asInstanceOf[CharacterAcceptance].characterId == accepterCharacter.characterId &&
          accepted.playerId == accepterPlayer.playerId
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("accept rejects a caller who does not own the accepting character") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId, otherExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 3), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (_, accepterCharacter) = accepter
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), otherExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("accept rejects a character from a different game than the challenge") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 3), externalId)
          otherGameFixture <- makeFixture(accepterNickname, accepterExternalId, minPlayers = 2, maxPlayers = 4)
          attempt <- challengeService
            .accept(fixture.game.gameId, created.challengeId, Some(otherGameFixture.character.characterId), accepterExternalId)
            .attempt
        } yield attempt match {
          case Left(_: ValidationError) => true
          case _                        => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("accept rejects once acceptances would exceed the challenge's numberOfPlayers") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, firstNickname, firstExternalId, secondNickname, secondExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 1, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 1), externalId)
          first <- makeCharacterInGame(fixture.game, firstNickname, firstExternalId)
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(first._2.characterId), firstExternalId)
          second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(second._2.characterId), secondExternalId).attempt
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
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (accepterPlayer, accepterCharacter) = accepter
          _ <- TestSession.resource.use { session =>
            new AcceptanceRepo(session).create(
              CharacterAcceptance(created.challengeId, accepterPlayer.playerId, fixture.game.gameId, accepterCharacter.characterId)
            )
          }
          _ <- challengeService.delete(fixture.game.gameId, created.challengeId, externalId)
          remainingChallenge <- TestSession.resource.use(session =>
            new com.vivi.matchmaker.persistence.OpenChallengeRepo(session).read(fixture.game.gameId, created.challengeId)
          )
          remainingAcceptance <- TestSession.resource.use(session =>
            new AcceptanceRepo(session).read(created.challengeId, accepterPlayer.playerId)
          )
        } yield remainingChallenge.isEmpty && remainingAcceptance.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("listByGame returns the game's open challenges") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
        listed <- challengeService.listByGame(fixture.game.gameId, externalId)
      } yield listed.map(_.challengeId) == List(created.challengeId)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("listByGame rejects an unregistered caller") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, strangerExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        attempt <- challengeService.listByGame(fixture.game.gameId, strangerExternalId).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete rejects a caller who does not own the character") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, otherExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
        attempt <- challengeService.delete(fixture.game.gameId, created.challengeId, otherExternalId).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
