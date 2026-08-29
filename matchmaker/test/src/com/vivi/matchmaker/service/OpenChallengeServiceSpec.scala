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

  private def makeFixture(nickname: String, externalId: String): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(
            GameId.unassigned, GameType.Character, "game", "description", "url", active = true,
            // Three roles: every acceptance names one and no two acceptances of a challenge may
            // name the same one, so a challenger plus two accepters need one each -- and the
            // capacity check below has to be reachable without running out of roles first.
            Seq(
              GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false),
              GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false),
              GameRole(GameRoleId(0), GameId.unassigned, "third", optional = false)
            ),
            Seq.empty, genUniqueString.sample.get
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

  private def challengeFor(fixture: Fixture): OpenChallenge =
    CharacterOpenChallenge(
      ChallengeId(0), fixture.owner.playerId, "message", None, None, "{}", fixture.game.gameId,
      fixture.character.characterId, isPublic = false, gameRoleId = fixture.game.roles.head.gameRoleId
    )

  property("create creates a challenge when the caller owns the character") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId)
        created <- challengeService.create(challengeFor(fixture), externalId)
      } yield created match { case c: CharacterOpenChallenge => c.characterId == fixture.character.characterId; case _ => false }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // Regression test: creating a challenge is itself an acceptance of it, so the challenger must
  // already be among its acceptances when create returns.
  property("create accepts the challenge on the challenger's behalf") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId)
        created <- challengeService.create(challengeFor(fixture), externalId)
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
        base <- makeFixture(nickname, externalId)
        game <- TestSession.resource.use { session =>
          val repo = new GameRepo[String](session)
          // Read back rather than reuse: the role's id is assigned by the insert.
          repo.update(
            base.game.copy(roles =
              Seq(
                GameRole(GameRoleId(0), base.game.gameId, "attacker", optional = false),
                GameRole(GameRoleId(0), base.game.gameId, "defender", optional = false)
              )
            )
          ) *>
            repo.read(base.game.gameId).map(_.get)
        }
        role = game.roles.head.gameRoleId
        challenge = challengeFor(base) match {
          case c: CharacterOpenChallenge => c.copy(gameRoleId = role)
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
      } yield acceptance.exists(_.gameRoleId == role) &&
        listed.exists(c => c.challenge.challengeId == created.challengeId && c.challenge.gameRoleId == role) &&
        joined.exists((challenge, _, _) => challenge.gameRoleId == role)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a caller who does not own the character") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, otherExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId)
        attempt <- challengeService.create(challengeFor(fixture), otherExternalId).attempt
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
          fixture <- makeFixture(nickname, externalId)
          other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
          (otherPlayer, _) = other
          challenge = challengeFor(fixture) match {
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

  property("accept creates an acceptance when authorized and the role is free") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (accepterPlayer, accepterCharacter) = accepter
          accepted <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), fixture.game.roles(1).gameRoleId, accepterExternalId)
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
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (_, accepterCharacter) = accepter
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), fixture.game.roles(1).gameRoleId, otherExternalId).attempt
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
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          otherGameFixture <- makeFixture(accepterNickname, accepterExternalId)
          attempt <- challengeService
            .accept(fixture.game.gameId, created.challengeId, Some(otherGameFixture.character.characterId), fixture.game.roles(1).gameRoleId, accepterExternalId)
            .attempt
        } yield attempt match {
          case Left(_: ValidationError) => true
          case _                        => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // With no numberOfPlayers, this is the whole of the capacity rule: a challenge holds one
  // acceptance per role of its game, so refusing a role somebody has already taken is what makes
  // a full challenge full.
  property("accept rejects a role another player has already taken") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, firstNickname, firstExternalId, secondNickname, secondExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          first <- makeCharacterInGame(fixture.game, firstNickname, firstExternalId)
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(first._2.characterId), fixture.game.roles(1).gameRoleId, firstExternalId)
          second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
          // The same role the first accepter took, not the third one still going free.
          attempt <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(second._2.characterId), fixture.game.roles(1).gameRoleId, secondExternalId).attempt
        } yield attempt match {
          case Left(_: ConflictError) => true
          case _                      => false
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
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (_, accepterCharacter) = accepter
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), fixture.game.roles(1).gameRoleId, accepterExternalId)
          // A different, free role and room to spare, so the only thing that can refuse this is
          // the rule that a player takes one seat per challenge -- which since V5 is the
          // application's to enforce and not the key's.
          attempt <- challengeService
            .accept(fixture.game.gameId, created.challengeId, Some(accepterCharacter.characterId), fixture.game.roles(2).gameRoleId, accepterExternalId)
            .attempt
        } yield attempt match {
          case Left(e: ConflictError) => e.getMessage.contains("has already accepted")
          case _                      => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete removes the challenge and its acceptances when authorized by the owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          created <- challengeService.create(challengeFor(fixture), externalId)
          accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
          (accepterPlayer, accepterCharacter) = accepter
          _ <- TestSession.resource.use { session =>
            new AcceptanceRepo(session).create(
              CharacterAcceptance(
                created.challengeId, accepterPlayer.playerId, fixture.game.gameId, accepterCharacter.characterId,
                fixture.game.roles(1).gameRoleId
              )
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
        fixture <- makeFixture(nickname, externalId)
        created <- challengeService.create(challengeFor(fixture), externalId)
        listed <- challengeService.listByGame(fixture.game.gameId, externalId)
      } yield listed.map(_.challenge.challengeId) == List(created.challengeId) &&
        // Creating a challenge accepts it on the challenger's behalf, so a fresh one is at one.
        listed.map(_.acceptances) == List(1)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // The count is what the UI decides whether to offer a Start from, so it has to follow the
  // acceptances rather than the challenge's requested size.
  property("listByGame counts the acceptances a challenge has so far") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          otherPair <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
          (_, otherCharacter) = otherPair
          created <- challengeService.create(challengeFor(fixture), externalId)
          // The challenger's own acceptance, written when the challenge was created.
          beforeAccept <- challengeService.listByGame(fixture.game.gameId, externalId)
          _ <- challengeService.accept(fixture.game.gameId, created.challengeId, Some(otherCharacter.characterId), fixture.game.roles(1).gameRoleId, otherExternalId)
          afterAccept <- challengeService.listByGame(fixture.game.gameId, externalId)
        } yield beforeAccept.map(_.acceptances) == List(1) &&
          afterAccept.map(_.acceptances) == List(2) &&
          // And each acceptance is a role, so the two agree.
          afterAccept.map(_.takenRoles.size) == List(2)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // A challenge nobody else can join is nobody else's business: an Accept offered on it would
  // only be refused. The players already in it still see it — they are waiting on it, and the
  // challenger is the one who has to start it.
  property("listByGame hides a full challenge from everyone but the players in it") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val thirdExternalId = genUniqueString.sample.get
        val bystanderExternalId = genUniqueString.sample.get
        val result = for {
          fixture <- makeFixture(nickname, externalId)
          otherPair <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
          (_, otherCharacter) = otherPair
          thirdPair <- makeCharacterInGame(fixture.game, genUniqueString.sample.get, thirdExternalId)
          (_, thirdCharacter) = thirdPair
          _ <- registrationService.register(genUniqueString.sample.get, bystanderExternalId)
          // The game has three roles, and the challenger takes the first on creation. Full means
          // all three are gone, so it takes both of the others to get there.
          created <- challengeService.create(challengeFor(fixture), externalId)
          _ <- challengeService.accept(
            fixture.game.gameId, created.challengeId, Some(otherCharacter.characterId),
            fixture.game.roles(1).gameRoleId, otherExternalId
          )
          // Two of three: still a seat free, so everyone can see it.
          whileOpen <- challengeService.listByGame(fixture.game.gameId, bystanderExternalId)
          _ <- challengeService.accept(
            fixture.game.gameId, created.challengeId, Some(thirdCharacter.characterId),
            fixture.game.roles(2).gameRoleId, thirdExternalId
          )
          toChallenger <- challengeService.listByGame(fixture.game.gameId, externalId)
          toAccepter <- challengeService.listByGame(fixture.game.gameId, otherExternalId)
          toBystander <- challengeService.listByGame(fixture.game.gameId, bystanderExternalId)
        } yield
          // Visible to everyone while a role is still free, which is what makes the disappearance
          // below the filling up rather than the filter hiding it all along.
          whileOpen.map(_.challenge.challengeId) == List(created.challengeId) &&
            toChallenger.map(_.challenge.challengeId) == List(created.challengeId) &&
            toAccepter.map(_.challenge.challengeId) == List(created.challengeId) &&
            toBystander.isEmpty
        result.timeout(30.seconds).unsafeRunSync()
    }
  }

  property("listByGame rejects an unregistered caller") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, strangerExternalId) =>
      val result = for {
        fixture <- makeFixture(nickname, externalId)
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
        fixture <- makeFixture(nickname, externalId)
        created <- challengeService.create(challengeFor(fixture), externalId)
        attempt <- challengeService.delete(fixture.game.gameId, created.challengeId, otherExternalId).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
      result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
