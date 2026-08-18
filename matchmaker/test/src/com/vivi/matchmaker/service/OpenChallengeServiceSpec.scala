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

  // Regression test: creating a challenge is itself an acceptance of it, so the challenger must
  // already be among its acceptances when create returns.
  property("create accepts the challenge on the challenger's behalf") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        created <- challengeService.create(challengeFor(fixture, 3), externalId)
        acceptance <- TestSession.resource.use { session =>
          new AcceptanceRepo(session).read(fixture.game.gameId, created.challengeId, fixture.owner.playerId)
        }
      } yield acceptance match {
        case Some(a: CharacterAcceptance) => a.characterId == fixture.character.characterId
        case _                            => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // The challenger's role is not a column on open_challenge — it is stored on the acceptance
  // create makes for them, and read back from there. This checks both halves: that the role given
  // on the challenge lands on that acceptance, and that reading the challenge reports it again.
  property("the challenger's role is stored on their acceptance and read back with the challenge") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        base <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
        game <- TestSession.resource.use { session =>
          val repo = new GameRepo[String](session)
          // Read back rather than reuse: the role's id is assigned by the insert.
          repo.update(base.game.copy(roles = Seq(GameRole(GameRoleId(0), base.game.gameId, "attacker", optional = false)))) *>
            repo.read(base.game.gameId).map(_.get)
        }
        role = game.roles.head.gameRoleId
        challenge = challengeFor(base, 3) match {
          case c: CharacterOpenChallenge => c.copy(gameRoleId = Some(role))
          case other                     => other
        }
        created <- challengeService.create(challenge, externalId)
        acceptance <- TestSession.resource.use { session =>
          new AcceptanceRepo(session).read(game.gameId, created.challengeId, base.owner.playerId)
        }
        listed <- challengeService.listByGame(game.gameId, externalId)
        // The join query used to authorize deleting an acceptance rebuilds the challenge too, so
        // it has to reach the challenger's role the same way.
        joined <- TestSession.resource.use { session =>
          new AcceptanceRepo(session).readWithChallengeAndPlayers(game.gameId, created.challengeId, base.owner.playerId)
        }
      } yield acceptance.exists(_.gameRoleId.contains(role)) &&
        listed.exists(c => c.challengeId == created.challengeId && c.gameRoleId.contains(role)) &&
        joined.exists((challenge, _, _) => challenge.gameRoleId.contains(role))
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

  // Regression test: owning the character is not the whole story — the challenger field says who
  // the challenge belongs to, and a caller must not be able to point it at another player.
  property("create rejects a challenger who does not own the character") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 2, maxPlayers = 4)
          other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
          (otherPlayer, _) = other
          challenge = challengeFor(fixture, 3) match {
            case c: CharacterOpenChallenge => c.copy(challenger = otherPlayer.playerId)
            case c                         => c
          }
          attempt <- challengeService.create(challenge, externalId).attempt
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
          accepted <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), None, accepterExternalId)
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
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), None, otherExternalId).attempt
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
            .accept(fixture.game.gameId, created.challengeId, Some(otherGameFixture.character.characterId), None, accepterExternalId)
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
          // 2, not 1: the challenger's own acceptance is created with the challenge, so a
          // one-player challenge is already full and nobody could accept it at all.
          created <- challengeService.create(challengeFor(fixture, 2), externalId)
          first <- makeCharacterInGame(fixture.game, firstNickname, firstExternalId)
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(first._2.characterId), None, firstExternalId)
          second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(second._2.characterId), None, secondExternalId).attempt
        } yield attempt match {
          case Left(_: ValidationError) => true
          case _                        => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // Regression test: accepting the same challenge twice as the same player used to hit
  // `acceptance_pkey`'s unique constraint directly (create() has no idea it's a duplicate) and
  // surface to the caller as a raw 500 instead of a normal service error.
  property("accept rejects a player who has already accepted the same challenge") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId, minPlayers = 1, maxPlayers = 4)
          created <- challengeService.create(challengeFor(fixture, 2), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (_, accepterCharacter) = accepter
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), None, accepterExternalId)
          attempt <- challengeService
            .accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), None, accepterExternalId)
            .attempt
        } yield attempt match {
          case Left(_: ConflictError) => true
          case _                      => false
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
            new AcceptanceRepo(session).read(fixture.game.gameId, created.challengeId, accepterPlayer.playerId)
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
