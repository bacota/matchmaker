package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    AcceptanceRepo,
    ChallengeRepo,
    CharacterRepo,
    GameRepo,
    InvitationRepo,
    TestSession
}

class ChallengeServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val challengeService = TestServices.services.challenges
    private val registrationService = TestServices.services.registration

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private case class Fixture(owner: Player, game: Game, character: Character[String])

    private def makeFixture(nickname: String, externalId: String): IO[Fixture] =
        TestSession.resource.use { session =>
            for {
                owner <- registrationService.register(nickname, externalId)
                game <- new GameRepo[String](session).create(
                  Game(
                    GameId.unassigned,
                    GameType.Character,
                    "game",
                    "description",
                    "url",
                    active = true,
                    // Three roles: every acceptance names one and no two acceptances of a challenge may
                    // name the same one, so a challenger plus two accepters need one each -- and the
                    // capacity check below has to be reachable without running out of roles first.
                    Seq(
                      GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false),
                      GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false),
                      GameRole(GameRoleId(0), GameId.unassigned, "third", optional = false)
                    ),
                    Seq.empty,
                    genUniqueString.sample.get
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

    private def challengeFor(fixture: Fixture): Challenge =
        CharacterChallenge(
          ChallengeId(0),
          fixture.owner.playerId,
          "message",
          None,
          None,
          "{}",
          fixture.game.gameId,
          fixture.character.characterId,
          isPublic = false,
          gameRoleId = fixture.game.roles.head.gameRoleId
        )

    property("create creates a challenge when the caller owns the character") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makeFixture(nickname, externalId)
                created <- challengeService.create(challengeFor(fixture), externalId)
            } yield created match {
                case c: CharacterChallenge => c.characterId == fixture.character.characterId; case _ => false
            }
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

    // The challenger's role is not a column on challenge — it is stored on the acceptance
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
                    case c: CharacterChallenge => c.copy(gameRoleId = role)
                    case other                 => other
                }
                created <- challengeService.create(challenge, externalId)
                acceptance <- TestSession.resource.use { session =>
                    new AcceptanceRepo(session).read(game.gameId, created.challengeId, base.owner.playerId)
                }
                listed <- challengeService.listByGame(game.gameId, externalId)
                // The join query used to authorize deleting an acceptance rebuilds the challenge too, so
                // it has to reach the challenger's role the same way.
                joined <- TestSession.resource.use { session =>
                    new AcceptanceRepo(session).readWithChallengeAndPlayers(
                      game.gameId,
                      created.challengeId,
                      base.owner.playerId
                    )
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
                        case c: CharacterChallenge => c.copy(challenger = otherPlayer.playerId)
                        case c                     => c
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
                    accepted <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(accepterCharacter.characterId),
                      fixture.game.roles(1).gameRoleId,
                      accepterExternalId
                    )
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
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(accepterCharacter.characterId),
                          fixture.game.roles(1).gameRoleId,
                          otherExternalId
                        )
                        .attempt
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
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(otherGameFixture.character.characterId),
                          fixture.game.roles(1).gameRoleId,
                          accepterExternalId
                        )
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
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(first._2.characterId),
                      fixture.game.roles(1).gameRoleId,
                      firstExternalId
                    )
                    second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
                    // The same role the first accepter took, not the third one still going free.
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(second._2.characterId),
                          fixture.game.roles(1).gameRoleId,
                          secondExternalId
                        )
                        .attempt
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
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(accepterCharacter.characterId),
                      fixture.game.roles(1).gameRoleId,
                      accepterExternalId
                    )
                    // A different, free role and room to spare, so the only thing that can refuse this is
                    // the rule that a player takes one seat per challenge -- which since V5 is the
                    // application's to enforce and not the key's.
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(accepterCharacter.characterId),
                          fixture.game.roles(2).gameRoleId,
                          accepterExternalId
                        )
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
                            created.challengeId,
                            accepterPlayer.playerId,
                            fixture.game.gameId,
                            accepterCharacter.characterId,
                            fixture.game.roles(1).gameRoleId
                          )
                        )
                    }
                    _ <- challengeService.delete(fixture.game.gameId, created.challengeId, externalId)
                    remainingChallenge <- TestSession.resource.use(session =>
                        new com.vivi.matchmaker.persistence.ChallengeRepo(session)
                            .read(fixture.game.gameId, created.challengeId)
                    )
                    remainingAcceptance <- TestSession.resource.use(session =>
                        new AcceptanceRepo(session)
                            .read(fixture.game.gameId, created.challengeId, accepterPlayer.playerId)
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
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(otherCharacter.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
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
                      fixture.game.gameId,
                      created.challengeId,
                      Some(otherCharacter.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
                    // Two of three: still a seat free, so everyone can see it.
                    whileOpen <- challengeService.listByGame(fixture.game.gameId, bystanderExternalId)
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(thirdCharacter.characterId),
                      fixture.game.roles(2).gameRoleId,
                      thirdExternalId
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

    // ---------------------------------------------------------------------------
    // Invitations (V22)
    // ---------------------------------------------------------------------------

    private def closedChallengeFor(fixture: Fixture): Challenge =
        challengeFor(fixture) match {
            case c: CharacterChallenge => c.copy(isOpen = false)
            case other                 => other
        }

    private def invitationsOf(game: Game, challenge: ChallengeId): IO[List[Invitation]] =
        TestSession.resource.use(session => new InvitationRepo(session).listForChallenge(game.gameId, challenge))

    property("a challenge that is not open cannot be accepted by a player who was not invited") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId, strangerNickname, strangerExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    stranger <- makeCharacterInGame(fixture.game, strangerNickname, strangerExternalId)
                    // Closed, and the invitation goes to somebody else -- so the challenge is joinable, and
                    // the caller below is simply not one of the people it was addressed to.
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(invited._1.playerId))
                    )
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(stranger._2.characterId),
                          fixture.game.roles(1).gameRoleId,
                          strangerExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("an invited player can accept a challenge that is not open") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, character) = invited
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    accepted <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
                } yield accepted.playerId == player.playerId
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("an invitation that names a role is an offer to play that role and no other") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, character) = invited
                    asked = fixture.game.roles(1).gameRoleId
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId, Some(asked)))
                    )
                    // The third role is free, and taking it is still refused: what was offered was the
                    // second.
                    wrongSeat <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(character.characterId),
                          fixture.game.roles(2).gameRoleId,
                          otherExternalId
                        )
                        .attempt
                    accepted <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      asked,
                      otherExternalId
                    )
                } yield (wrongSeat, accepted.gameRoleId) match {
                    case (Left(_: ValidationError), taken) => taken == asked
                    case _                                 => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("a role held for one player is not free for another, even on an open challenge") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId, strangerNickname, strangerExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    stranger <- makeCharacterInGame(fixture.game, strangerNickname, strangerExternalId)
                    (invitee, _) = invited
                    (_, strangerCharacter) = stranger
                    held = fixture.game.roles(1).gameRoleId
                    // Open, so the stranger is welcome -- but not in the seat somebody else was asked to
                    // play. Otherwise a role on an invitation would mean something on a closed challenge
                    // and nothing on an open one.
                    created <- challengeService.create(
                      challengeFor(fixture),
                      externalId,
                      Seq(Invite(invitee.playerId, Some(held)))
                    )
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(strangerCharacter.characterId),
                          held,
                          strangerExternalId
                        )
                        .attempt
                    // And the seats nobody is holding are still open to them.
                    free <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(strangerCharacter.characterId),
                          fixture.game.roles(2).gameRoleId,
                          strangerExternalId
                        )
                        .attempt
                } yield (attempt, free) match {
                    case (Left(_: ConflictError), Right(_)) => true
                    case _                                  => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a challenge that is not open must invite somebody") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makeFixture(nickname, externalId)
                attempt <- challengeService.create(closedChallengeFor(fixture), externalId).attempt
            } yield attempt match {
                case Left(_: ValidationError) => true
                case _                        => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create refuses to invite one player twice, or to hold one role for two of them") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, firstNickname, firstExternalId, secondNickname, secondExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    first <- makeCharacterInGame(fixture.game, firstNickname, firstExternalId)
                    second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
                    role = fixture.game.roles(1).gameRoleId
                    twice <- challengeService
                        .create(
                          challengeFor(fixture),
                          externalId,
                          Seq(Invite(first._1.playerId), Invite(first._1.playerId))
                        )
                        .attempt
                    sameRole <- challengeService
                        .create(
                          challengeFor(fixture),
                          externalId,
                          Seq(Invite(first._1.playerId, Some(role)), Invite(second._1.playerId, Some(role)))
                        )
                        .attempt
                    // And the challenger's own seat is not one they can offer away.
                    ownSeat <- challengeService
                        .create(
                          challengeFor(fixture),
                          externalId,
                          Seq(Invite(first._1.playerId, Some(fixture.game.roles.head.gameRoleId)))
                        )
                        .attempt
                } yield (twice, sameRole, ownSeat) match {
                    case (Left(_: ValidationError), Left(_: ValidationError), Left(_: ConflictError)) => true
                    case _                                                                            => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a challenger cannot invite themselves") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makeFixture(nickname, externalId)
                attempt <- challengeService
                    .create(challengeFor(fixture), externalId, Seq(Invite(fixture.owner.playerId)))
                    .attempt
            } yield attempt match {
                case Left(_: ValidationError) => true
                case _                        => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("invite adds an invitation to an existing challenge, and only the challenger may") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, _) = other
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    // The invitee is not the challenger, so they cannot invite anybody either.
                    byStranger <- challengeService
                        .invite(fixture.game.gameId, created.challengeId, Invite(player.playerId), otherExternalId)
                        .attempt
                    invited <- challengeService.invite(
                      fixture.game.gameId,
                      created.challengeId,
                      Invite(player.playerId, Some(fixture.game.roles(1).gameRoleId)),
                      externalId
                    )
                    twice <- challengeService
                        .invite(fixture.game.gameId, created.challengeId, Invite(player.playerId), externalId)
                        .attempt
                    held <- invitationsOf(fixture.game, created.challengeId)
                } yield (byStranger, twice) match {
                    case (Left(_: UnauthorizedError), Left(_: ConflictError)) =>
                        held == List(invited) && invited.gameRoleId.contains(fixture.game.roles(1).gameRoleId)
                    case _ => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("reject deletes the invitation and leaves the challenge and the other invitations alone") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, firstNickname, firstExternalId, secondNickname, secondExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    first <- makeCharacterInGame(fixture.game, firstNickname, firstExternalId)
                    second <- makeCharacterInGame(fixture.game, secondNickname, secondExternalId)
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(first._1.playerId), Invite(second._1.playerId))
                    )
                    _ <- challengeService.reject(fixture.game.gameId, created.challengeId, firstExternalId)
                    left <- invitationsOf(fixture.game, created.challengeId)
                    // The challenge is still there, and the player who did not reject can still accept it.
                    still <- challengeService.listByGame(fixture.game.gameId, externalId)
                    accepted <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(second._2.characterId),
                          fixture.game.roles(1).gameRoleId,
                          secondExternalId
                        )
                        .attempt
                } yield left.map(_.playerId) == List(second._1.playerId) &&
                    still.exists(_.challenge.challengeId == created.challengeId) &&
                    accepted.isRight
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a player who has already accepted cannot turn the invitation down, and keeps their seat") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, character) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
                    // Refused: deleting the invitation would leave them in a seat they are no longer
                    // permitted to hold, and would tell the challenger it was free to offer again.
                    attempt <- challengeService
                        .reject(fixture.game.gameId, created.challengeId, otherExternalId)
                        .attempt
                    // And the challenger cannot take it back from under them either.
                    revoking <- challengeService
                        .revoke(fixture.game.gameId, created.challengeId, player.playerId, externalId)
                        .attempt
                    stillIn <- TestSession.resource.use(session =>
                        new AcceptanceRepo(session)
                            .hasAccepted(fixture.game.gameId, created.challengeId, player.playerId)
                    )
                    stillInvited <- TestSession.resource.use(session =>
                        new InvitationRepo(session).read(fixture.game.gameId, created.challengeId, player.playerId)
                    )
                } yield (attempt, revoking) match {
                    case (Left(rejected: ConflictError), Left(revoked: ConflictError)) =>
                        rejected.message ==
                            "You have already accepted this challenge, so there is no invitation left to turn down. " +
                            "Back out of the challenge instead." &&
                            revoked.message ==
                            "That player has already accepted this challenge. Remove their acceptance instead." &&
                            // Neither refusal took anything away.
                            stillIn && stillInvited.isDefined
                    case _ => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("backing out first leaves the invitation to be turned down") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, character) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
                    // The invitation outlives the acceptance, which is what makes this the route the
                    // refusal above points at rather than a dead end.
                    _ <- TestServices.services.acceptances
                        .delete(fixture.game.gameId, created.challengeId, player.playerId, otherExternalId)
                    _ <- challengeService.reject(fixture.game.gameId, created.challengeId, otherExternalId)
                    left <- invitationsOf(fixture.game, created.challengeId)
                } yield left.isEmpty
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a player with no invitation has nothing to reject") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    attempt <- challengeService
                        .reject(fixture.game.gameId, created.challengeId, otherExternalId)
                        .attempt
                } yield attempt match {
                    case Left(_: NotFoundError) => true
                    case _                      => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("revoke takes an invitation back, and only the challenger may") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, _) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    byInvitee <- challengeService
                        .revoke(fixture.game.gameId, created.challengeId, player.playerId, otherExternalId)
                        .attempt
                    _ <- challengeService.revoke(fixture.game.gameId, created.challengeId, player.playerId, externalId)
                    left <- invitationsOf(fixture.game, created.challengeId)
                    // And what is not there cannot be taken back twice.
                    again <- challengeService
                        .revoke(fixture.game.gameId, created.challengeId, player.playerId, externalId)
                        .attempt
                } yield (byInvitee, again) match {
                    case (Left(_: UnauthorizedError), Left(_: NotFoundError)) => left.isEmpty
                    case _                                                    => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a challenge already being started can no longer be invited to or rejected") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, _) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    // The claim `GameEngineService.start` takes before it calls the engine, written here
                    // directly: what is being tested is that these two refuse a challenge in that state,
                    // not how it got there.
                    _ <- TestSession.resource.use(session =>
                        new ChallengeRepo(session)
                            .claimForStart(fixture.game.gameId, created.challengeId, MatchId("m-1"))
                    )
                    inviting <- challengeService
                        .invite(fixture.game.gameId, created.challengeId, Invite(fixture.owner.playerId), externalId)
                        .attempt
                    rejecting <- challengeService
                        .reject(fixture.game.gameId, created.challengeId, otherExternalId)
                        .attempt
                } yield (inviting, rejecting) match {
                    case (Left(_: ConflictError), Left(_: ConflictError)) => true
                    case _                                                => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* The three refusals a player can actually walk into, and what they are told.
     *
     * The wording is asserted, not just the type: these sentences are shown to whoever pressed the
     * button -- the UI prints a 404 or 409 message verbatim -- so they are part of the interface, and a
     * developer-facing string with ids in it would reach a player's screen the day somebody reworded
     * one without knowing that. */
    property("accepting a challenge whose match has started says so, without naming ids") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    _ <- TestSession.resource.use(session =>
                        new ChallengeRepo(session)
                            .claimForStart(fixture.game.gameId, created.challengeId, MatchId("m-1"))
                    )
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(other._2.characterId),
                          fixture.game.roles(1).gameRoleId,
                          otherExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(e: ConflictError) =>
                        e.message == "The match has already started. The challenge is closed."
                    case _ => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("accepting after an invitation is withdrawn says the invitation is gone") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (player, character) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(player.playerId))
                    )
                    // Revoked by the challenger, after which this player is in the same position as one
                    // who was never asked -- which is what the message has to cover.
                    _ <- challengeService.revoke(fixture.game.gameId, created.challengeId, player.playerId, externalId)
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(character.characterId),
                          fixture.game.roles(1).gameRoleId,
                          otherExternalId
                        )
                        .attempt
                    // And turning down an invitation that is already gone says its own thing.
                    rejecting <- challengeService
                        .reject(fixture.game.gameId, created.challengeId, otherExternalId)
                        .attempt
                } yield (attempt, rejecting) match {
                    case (Left(e: UnauthorizedError), Left(r: NotFoundError)) =>
                        e.message == "This challenge is open only to players invited to it. " +
                            "If you were invited, the invitation has been withdrawn." &&
                            r.message == "That invitation is no longer there. It may have been withdrawn."
                    case _ => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("accepting a challenge its challenger has withdrawn says it is gone") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    _ <- challengeService.delete(fixture.game.gameId, created.challengeId, externalId)
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(other._2.characterId),
                          fixture.game.roles(1).gameRoleId,
                          otherExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(e: NotFoundError) =>
                        e.message == "That challenge is no longer there. Whoever offered it has withdrawn it."
                    case _ => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("listByGame hides an open challenge whose every remaining role is held for other players") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId, strangerNickname, strangerExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    other <- makeCharacterInGame(fixture.game, genUniqueString.sample.get, genUniqueString.sample.get)
                    _ <- makeCharacterInGame(fixture.game, strangerNickname, strangerExternalId)
                    // Open, and every seat is spoken for: the challenger holds the first, and the other
                    // two are reserved by invitations to two other players. A stranger can accept none of
                    // them, so listing it would offer them an Accept the service refuses -- which is what
                    // this clause has always been for.
                    created <- challengeService.create(
                      challengeFor(fixture),
                      externalId,
                      Seq(
                        Invite(invited._1.playerId, Some(fixture.game.roles(1).gameRoleId)),
                        Invite(other._1.playerId, Some(fixture.game.roles(2).gameRoleId))
                      )
                    )
                    strangers <- challengeService.listByGame(fixture.game.gameId, strangerExternalId)
                    // The invitee still sees it: one of those seats is theirs, and a challenge being held
                    // open for somebody is the last thing to hide from them.
                    theirs <- challengeService.listByGame(fixture.game.gameId, invitedExternalId)
                    // As does the challenger, through the acceptance creating it wrote.
                    mine <- challengeService.listByGame(fixture.game.gameId, externalId)
                } yield {
                    def has(summaries: List[ChallengeSummary]) =
                        summaries.exists(_.challenge.challengeId == created.challengeId)
                    !has(strangers) && has(theirs) && has(mine)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("listByGame shows a challenge that is not open only to its challenger and its invitees") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId, strangerNickname, strangerExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    _ <- makeCharacterInGame(fixture.game, strangerNickname, strangerExternalId)
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(invited._1.playerId))
                    )
                    mine <- challengeService.listByGame(fixture.game.gameId, externalId)
                    theirs <- challengeService.listByGame(fixture.game.gameId, invitedExternalId)
                    strangers <- challengeService.listByGame(fixture.game.gameId, strangerExternalId)
                } yield {
                    def has(summaries: List[ChallengeSummary]) =
                        summaries.exists(_.challenge.challengeId == created.challengeId)
                    // And the summary says who was invited, which is how a screen draws the row at all.
                    val said = mine
                        .find(_.challenge.challengeId == created.challengeId)
                        .exists(_.invitations.map(_.playerId) == Seq(invited._1.playerId))
                    has(mine) && has(theirs) && !has(strangers) && said
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* A game being deactivated does not withdraw the invitations to challenges in it, nor stop them
     * being accepted.
     *
     * `active` decides what `GameService.list` offers -- it is how an admin stops a game being picked
     * for something new -- and nothing in this service reads it. So a player invited before the
     * deactivation is still invited afterwards, and accepting still works.
     *
     * Written down because a browser holds only the *active* games, which makes the opposite
     * assumption easy to make and quiet when made: a screen that decided what an invitation may do by
     * looking its game up in that list would withdraw the button from exactly these challenges, and
     * the server would have honoured the click. `ChallengeInvitation.gameType` exists so that no
     * screen has to look.
     */
    property("an invitation outlives its game being deactivated, and can still be accepted") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    role = fixture.game.roles(1).gameRoleId
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      Seq(Invite(invited._1.playerId, Some(role)))
                    )
                    // Withdrawn from the catalogue, after the invitation was sent and before it is
                    // answered -- which is the whole of what a deactivation is.
                    _ <- TestSession.resource.use(session =>
                        new GameRepo[String](session).update(fixture.game.copy(active = false))
                    )
                    listed <- challengeService.invitationsFor(invitedExternalId)
                    accepted <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(invited._2.characterId),
                          role,
                          invitedExternalId
                        )
                        .attempt
                } yield {
                    val mine = listed.filter(_.invitation.challengeId == created.challengeId)
                    // Still listed, still named, and still acceptable.
                    mine.sizeIs == 1 &&
                    mine.head.gameName == fixture.game.name &&
                    mine.head.gameType == GameType.Character &&
                    accepted.isRight
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("invitationsFor spans every game and names the game, the challenger and the role") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    first <- makeFixture(nickname, externalId)
                    second <- makeFixture(genUniqueString.sample.get, genUniqueString.sample.get)
                    invitee <- registrationService.register(otherNickname, otherExternalId)
                    role = first.game.roles(1).gameRoleId
                    one <- challengeService.create(
                      closedChallengeFor(first),
                      externalId,
                      Seq(Invite(invitee.playerId, Some(role)))
                    )
                    two <- challengeService.create(
                      closedChallengeFor(second),
                      second.owner.externalId,
                      Seq(Invite(invitee.playerId))
                    )
                    listed <- challengeService.invitationsFor(otherExternalId)
                } yield {
                    val mine =
                        listed.filter(i => Set(one.challengeId, two.challengeId).contains(i.invitation.challengeId))
                    val named = mine.find(_.invitation.challengeId == one.challengeId)
                    mine.sizeIs == 2 &&
                    named.exists(i =>
                        i.challengerNickname == nickname &&
                            i.gameName == first.game.name &&
                            // The kind of game, which is what the row reads to know whether accepting
                            // from the list can work at all -- an acceptance in a character game has to
                            // name a character, and the list holds none. The fixtures are character
                            // games, so this is the value that must not arrive defaulted.
                            i.gameType == GameType.Character &&
                            i.roleName.contains(first.game.roles(1).name) &&
                            i.invitation.gameRoleId.contains(role)
                    ) &&
                    mine.find(_.invitation.challengeId == two.challengeId).exists(_.roleName.isEmpty)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

}
