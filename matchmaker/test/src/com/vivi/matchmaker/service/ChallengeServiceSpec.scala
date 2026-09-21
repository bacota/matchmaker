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
    CharacterInvitationRepo,
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

    /* A plain ('P') game and its challenger, for the player-invitation paths: V22's `invitation` is
     * a plain game's alone since V25, so those paths need a game with no characters to test them in. */
    private case class PlainFixture(owner: Player, game: Game)

    private def makePlainGame: IO[Game] =
        TestSession.resource.use(session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "plain game",
                "description",
                "url",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false),
                  GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false),
                  GameRole(GameRoleId(0), GameId.unassigned, "third", optional = false)
                ),
                Seq.empty,
                genUniqueString.sample.get
              )
            )
        )

    private def makePlainFixture(nickname: String, externalId: String): IO[PlainFixture] =
        for {
            owner <- registrationService.register(nickname, externalId)
            game <- makePlainGame
        } yield PlainFixture(owner, game)

    private def plainChallengeFor(fixture: PlainFixture, isOpen: Boolean = true): Challenge =
        PlainChallenge(
          challengeId = ChallengeId(0),
          challenger = fixture.owner.playerId,
          message = "message",
          start = None,
          timeLimit = None,
          settings = "{}",
          gameId = fixture.game.gameId,
          gameRoleId = fixture.game.roles.head.gameRoleId,
          isOpen = isOpen
        )

    private def plainInvitationsOf(game: Game, challenge: ChallengeId): IO[List[Invitation]] =
        TestSession.resource.use(session => new InvitationRepo(session).listForChallenge(game.gameId, challenge))

    private case class Registered(player: Player, externalId: String)

    private def register: IO[Registered] = {
        val externalId = genUniqueString.sample.get
        registrationService.register(genUniqueString.sample.get, externalId).map(Registered(_, externalId))
    }

    private def plainAccept(
        fixture: PlainFixture,
        challenge: ChallengeId,
        role: Int,
        externalId: String
    ): IO[Either[Throwable, Acceptance]] =
        challengeService
            .accept(fixture.game.gameId, challenge, None, fixture.game.roles(role).gameRoleId, externalId)
            .attempt

    private def closedChallengeFor(fixture: Fixture): Challenge =
        challengeFor(fixture) match {
            case c: CharacterChallenge => c.copy(isOpen = false)
            case other                 => other
        }

    private def invitationsOf(game: Game, challenge: ChallengeId): IO[List[CharacterInvitation]] =
        TestSession.resource.use(session =>
            new CharacterInvitationRepo(session)
                .listForGame(game.gameId)
                .map(_.getOrElse(challenge, Nil).map(_.invitation))
        )

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
                      characterInvitations = Seq(CharacterInvite(invited._2.characterId))
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
                      characterInvitations = Seq(CharacterInvite(character.characterId))
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
                      characterInvitations = Seq(CharacterInvite(character.characterId, Some(asked)))
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
                      characterInvitations = Seq(CharacterInvite(invited._2.characterId, Some(held)))
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

    property("create refuses to invite one character twice, or to hold one role for two of them") {
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
                          characterInvitations =
                              Seq(CharacterInvite(first._2.characterId), CharacterInvite(first._2.characterId))
                        )
                        .attempt
                    sameRole <- challengeService
                        .create(
                          challengeFor(fixture),
                          externalId,
                          characterInvitations = Seq(
                            CharacterInvite(first._2.characterId, Some(role)),
                            CharacterInvite(second._2.characterId, Some(role))
                          )
                        )
                        .attempt
                    // And the challenger's own seat is not one they can offer away.
                    ownSeat <- challengeService
                        .create(
                          challengeFor(fixture),
                          externalId,
                          characterInvitations =
                              Seq(CharacterInvite(first._2.characterId, Some(fixture.game.roles.head.gameRoleId)))
                        )
                        .attempt
                } yield (twice, sameRole, ownSeat) match {
                    case (Left(_: ValidationError), Left(_: ValidationError), Left(_: ConflictError)) => true
                    case _                                                                            => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a challenger cannot invite their own character") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makeFixture(nickname, externalId)
                attempt <- challengeService
                    .create(
                      challengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(fixture.character.characterId))
                    )
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
                    (player, character) = other
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    // The invitee is not the challenger, so they cannot invite anybody either.
                    byStranger <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(character.characterId),
                          otherExternalId
                        )
                        .attempt
                    invited <- challengeService.inviteCharacter(
                      fixture.game.gameId,
                      created.challengeId,
                      CharacterInvite(character.characterId, Some(fixture.game.roles(1).gameRoleId)),
                      externalId
                    )
                    twice <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(character.characterId),
                          externalId
                        )
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
                      characterInvitations =
                          Seq(CharacterInvite(first._2.characterId), CharacterInvite(second._2.characterId))
                    )
                    _ <- challengeService.rejectCharacter(
                      fixture.game.gameId,
                      created.challengeId,
                      first._2.characterId,
                      firstExternalId
                    )
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
                } yield left.map(_.characterId) == List(second._2.characterId) &&
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
                      characterInvitations = Seq(CharacterInvite(character.characterId))
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
                        .rejectCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          other._2.characterId,
                          otherExternalId
                        )
                        .attempt
                    // And the challenger cannot take it back from under them either.
                    revoking <- challengeService
                        .revokeCharacter(fixture.game.gameId, created.challengeId, character.characterId, externalId)
                        .attempt
                    stillIn <- TestSession.resource.use(session =>
                        new AcceptanceRepo(session)
                            .hasAccepted(fixture.game.gameId, created.challengeId, player.playerId)
                    )
                    stillInvited <- TestSession.resource.use(session =>
                        new CharacterInvitationRepo(session)
                            .read(fixture.game.gameId, created.challengeId, character.characterId)
                    )
                } yield (attempt, revoking) match {
                    case (Left(rejected: ConflictError), Left(revoked: ConflictError)) =>
                        rejected.message ==
                            "You have already accepted this challenge, so there is no invitation left to turn down. " +
                            "Back out of the challenge instead." &&
                            revoked.message ==
                            "That character has already accepted this challenge. Remove their acceptance instead." &&
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
                      characterInvitations = Seq(CharacterInvite(character.characterId))
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
                    _ <- challengeService.rejectCharacter(
                      fixture.game.gameId,
                      created.challengeId,
                      other._2.characterId,
                      otherExternalId
                    )
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
                        .rejectCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          other._2.characterId,
                          otherExternalId
                        )
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
                    (player, character) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(character.characterId))
                    )
                    byInvitee <- challengeService
                        .revokeCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          character.characterId,
                          otherExternalId
                        )
                        .attempt
                    _ <- challengeService.revokeCharacter(
                      fixture.game.gameId,
                      created.challengeId,
                      character.characterId,
                      externalId
                    )
                    left <- invitationsOf(fixture.game, created.challengeId)
                    // And what is not there cannot be taken back twice.
                    again <- challengeService
                        .revokeCharacter(fixture.game.gameId, created.challengeId, character.characterId, externalId)
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
                    (player, character) = other
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(character.characterId))
                    )
                    // The claim `GameEngineService.start` takes before it calls the engine, written here
                    // directly: what is being tested is that these two refuse a challenge in that state,
                    // not how it got there.
                    _ <- TestSession.resource.use(session =>
                        new ChallengeRepo(session)
                            .claimForStart(fixture.game.gameId, created.challengeId, MatchId("m-1"))
                    )
                    inviting <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(fixture.character.characterId),
                          externalId
                        )
                        .attempt
                    rejecting <- challengeService
                        .rejectCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          other._2.characterId,
                          otherExternalId
                        )
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
                      characterInvitations = Seq(CharacterInvite(character.characterId))
                    )
                    // Revoked by the challenger, after which this player is in the same position as one
                    // who was never asked -- which is what the message has to cover.
                    _ <- challengeService.revokeCharacter(
                      fixture.game.gameId,
                      created.challengeId,
                      character.characterId,
                      externalId
                    )
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
                        .rejectCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          other._2.characterId,
                          otherExternalId
                        )
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
                      characterInvitations = Seq(
                        CharacterInvite(invited._2.characterId, Some(fixture.game.roles(1).gameRoleId)),
                        CharacterInvite(other._2.characterId, Some(fixture.game.roles(2).gameRoleId))
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
                      characterInvitations = Seq(CharacterInvite(invited._2.characterId))
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
                        .exists(_.invitedCharacters.map(_.invitation.characterId) == Seq(invited._2.characterId))
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
                      characterInvitations = Seq(CharacterInvite(invited._2.characterId, Some(role)))
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

    /* A player already in the challenge cannot be invited to it, with or without a seat named.
     *
     * The seat they are sitting in was already refused by `taken` -- an accepted role is not free to
     * offer -- which is what made the role-less case easy to miss: it held nothing, so it passed every
     * check and wrote a row that nothing could then remove. `reject` refuses them because their
     * invitation is what permits their seat, `revoke` refuses the challenger in the same words, and
     * the mail told somebody they had been invited to a challenge they had already joined.
     */
    property("invite refuses a player who has already accepted, seat or no seat") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    // In the challenge, in a seat of their own.
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(other._2.characterId),
                      fixture.game.roles(1).gameRoleId,
                      otherExternalId
                    )
                    // The seat they are in, which `taken` has always refused.
                    toTheirSeat <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(other._2.characterId, Some(fixture.game.roles(1).gameRoleId)),
                          externalId
                        )
                        .attempt
                    // And to no seat at all, which used to be allowed.
                    toAnySeat <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(other._2.characterId),
                          externalId
                        )
                        .attempt
                    // A free seat still goes to somebody who is not in it, so this refuses one player
                    // rather than every invitation to a challenge that has an acceptance in it.
                    third <- makeCharacterInGame(fixture.game, genUniqueString.sample.get, genUniqueString.sample.get)
                    toAnother <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(third._2.characterId, Some(fixture.game.roles(2).gameRoleId)),
                          externalId
                        )
                        .attempt
                    left <- invitationsOf(fixture.game, created.challengeId)
                } yield {
                    def refused(outcome: Either[Throwable, ?]) = outcome match {
                        case Left(_: ConflictError) => true
                        case _                      => false
                    }
                    refused(toTheirSeat) && refused(toAnySeat) && toAnother.isRight &&
                    // Nothing was written for the player who is already seated.
                    left.map(_.characterId) == List(third._2.characterId)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* The other side of "accepting leaves the invitation alone": if the row does not change, the
     * challenger's own view of it has to say which invitees have accepted, or Revoke is offered on
     * every one of them -- including the ones `revoke` refuses, where re-reading the list brings the
     * same unusable row straight back.
     *
     * Asserted on both at once, from the challenger's own listing: the invitee who accepted, and one
     * who was asked and has not answered. A flag that was simply always true would pass a test about
     * the first alone.
     */
    property("listByGame says which of the invited characters have accepted, and as whose acceptance") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    // Asked and still deciding, so nothing about them is an acceptance.
                    waiting <- makeCharacterInGame(fixture.game, genUniqueString.sample.get, genUniqueString.sample.get)
                    created <- challengeService.create(
                      challengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(
                        CharacterInvite(invited._2.characterId, Some(fixture.game.roles(1).gameRoleId)),
                        CharacterInvite(waiting._2.characterId, Some(fixture.game.roles(2).gameRoleId))
                      )
                    )
                    before <- challengeService.listByGame(fixture.game.gameId, externalId)
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(invited._2.characterId),
                      fixture.game.roles(1).gameRoleId,
                      invitedExternalId
                    )
                    after <- challengeService.listByGame(fixture.game.gameId, externalId)
                } yield {
                    def summary(listed: List[ChallengeSummary]) =
                        listed.find(_.challenge.challengeId == created.challengeId).get

                    // Nobody has accepted an invitation yet -- the challenger's own acceptance, written
                    // when they created it, is not an invitation of theirs to have accepted.
                    def acceptedBy(listed: List[ChallengeSummary]) =
                        summary(listed).invitedCharacters
                            .collect { case i if i.acceptedBy.isDefined => (i.invitation.characterId, i.acceptedBy) }
                    acceptedBy(before).isEmpty &&
                    // And afterwards, exactly the one who did -- seated by their owner's acceptance.
                    acceptedBy(after) == Seq((invited._2.characterId, Some(invited._1.playerId))) &&
                    // Both invitations are still listed, which is what makes the flag necessary: the
                    // accepted one is not distinguishable from the waiting one without it.
                    summary(after).invitedCharacters.map(_.invitation.characterId).sortBy(_.value) ==
                        Seq(invited._2.characterId, waiting._2.characterId).sortBy(_.value)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* A character's invitation that has been accepted leaves `invitationsFor`, and comes back if the
     * acceptance is withdrawn -- the row itself is untouched throughout, since it is what permits the
     * seat. Filtered by the server rather than left to the screen, for the reason the next property
     * shows: after a transfer, the screen has no acceptance of its own to filter by. */
    property("an accepted character invitation leaves the invitations list, and returns on backing out") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    (player, character) = invited
                    role = fixture.game.roles(1).gameRoleId
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(character.characterId, Some(role)))
                    )
                    before <- challengeService.invitationsFor(invitedExternalId)
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      role,
                      invitedExternalId
                    )
                    accepted <- challengeService.invitationsFor(invitedExternalId)
                    _ <- TestServices.services.acceptances
                        .delete(fixture.game.gameId, created.challengeId, player.playerId, invitedExternalId)
                    backedOut <- challengeService.invitationsFor(invitedExternalId)
                } yield {
                    def mine(listed: List[ChallengeInvitation]) =
                        listed.filter(_.invitation.challengeId == created.challengeId)
                    mine(before).sizeIs == 1 && mine(accepted).isEmpty && mine(backedOut) == mine(before)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a character transferred after accepting offers its new owner no invitation to answer") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, formerNickname, formerExternalId, newNickname, newExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    former <- makeCharacterInGame(fixture.game, formerNickname, formerExternalId)
                    (_, character) = former
                    _ <- registrationService.register(newNickname, newExternalId)
                    role = fixture.game.roles(1).gameRoleId
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(character.characterId, Some(role)))
                    )
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      role,
                      formerExternalId
                    )
                    _ <- TestServices.services.characters.update(
                      character.characterId,
                      character.name,
                      character.description,
                      newExternalId,
                      formerExternalId
                    )
                    listed <- challengeService.invitationsFor(newExternalId)
                } yield listed.forall(_.invitation.challengeId != created.challengeId)
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("invitationsFor spans every game and names the game, the challenger and the role") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    first <- makeFixture(nickname, externalId)
                    second <- makeFixture(genUniqueString.sample.get, genUniqueString.sample.get)
                    invited <- makeCharacterInGame(first.game, otherNickname, otherExternalId)
                    (invitee, firstCharacter) = invited
                    secondCharacter <- TestSession.resource.use(session =>
                        new CharacterRepo[String](session).create(
                          Character(CharacterId(0), second.game.gameId, "other", "d", "", Some(invitee.playerId))
                        )
                    )
                    role = first.game.roles(1).gameRoleId
                    one <- challengeService.create(
                      closedChallengeFor(first),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(firstCharacter.characterId, Some(role)))
                    )
                    two <- challengeService.create(
                      closedChallengeFor(second),
                      second.owner.externalId,
                      characterInvitations = Seq(CharacterInvite(secondCharacter.characterId))
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
                            // The kind of game, and the character invited (V25): an acceptance in a
                            // character game has to name a character, and this is where the list
                            // learns which one.
                            i.gameType == GameType.Character &&
                            i.character.exists(_.characterId == firstCharacter.characterId) &&
                            // Addressed to the character's current owner.
                            i.invitation.playerId == invitee.playerId &&
                            i.roleName.contains(first.game.roles(1).name) &&
                            i.invitation.gameRoleId.contains(role)
                    ) &&
                    mine.find(_.invitation.challengeId == two.challengeId).exists(_.roleName.isEmpty)
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* The point of V25: an invitation in a character game is to the character, so a character handed
     * to another player takes its invitation with it. The previous owner can neither see it, answer it,
     * nor use it to accept as some other character of theirs; the new owner can do all three. */
    property("a character's invitation follows it to a new owner") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, formerNickname, formerExternalId, newNickname, newExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    former <- makeCharacterInGame(fixture.game, formerNickname, formerExternalId)
                    (_, invitedCharacter) = former
                    // A second character the former owner keeps, which the invitation never covered.
                    kept <- TestSession.resource.use(session =>
                        new CharacterRepo[String](session).create(
                          invitedCharacter.copy(characterId = CharacterId(0), name = "kept")
                        )
                    )
                    newOwner <- registrationService.register(newNickname, newExternalId)
                    role = fixture.game.roles(1).gameRoleId
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(invitedCharacter.characterId, Some(role)))
                    )
                    _ <- TestServices.services.characters.update(
                      invitedCharacter.characterId,
                      invitedCharacter.name,
                      invitedCharacter.description,
                      newExternalId,
                      formerExternalId
                    )
                    formerList <- challengeService.invitationsFor(formerExternalId)
                    newList <- challengeService.invitationsFor(newExternalId)
                    formerRejects <- challengeService
                        .rejectCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          invitedCharacter.characterId,
                          formerExternalId
                        )
                        .attempt
                    formerAccepts <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(kept.characterId),
                          role,
                          formerExternalId
                        )
                        .attempt
                    newAccepts <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(invitedCharacter.characterId),
                      role,
                      newExternalId
                    )
                } yield {
                    def mine(listed: List[ChallengeInvitation]) =
                        listed.filter(_.invitation.challengeId == created.challengeId)
                    mine(formerList).isEmpty &&
                    mine(newList).map(i => (i.invitation.playerId, i.character.map(_.characterId))) ==
                        List((newOwner.playerId, Some(invitedCharacter.characterId))) &&
                        (formerRejects match {
                            case Left(_: UnauthorizedError) => true
                            case _                          => false
                        }) &&
                        (formerAccepts match {
                            case Left(_: UnauthorizedError) => true
                            case _                          => false
                        }) &&
                        newAccepts.playerId == newOwner.playerId
                }
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("an invitation to one character does not admit another character of the same player") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    (_, invitedCharacter) = other
                    uninvited <- TestSession.resource.use(session =>
                        new CharacterRepo[String](session).create(
                          invitedCharacter.copy(characterId = CharacterId(0), name = "uninvited")
                        )
                    )
                    created <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(invitedCharacter.characterId))
                    )
                    attempt <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(uninvited.characterId),
                          fixture.game.roles(1).gameRoleId,
                          otherExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a character game refuses an invitation to a player, on create and on invite") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    other <- makeCharacterInGame(fixture.game, otherNickname, otherExternalId)
                    onCreate <- challengeService
                        .create(challengeFor(fixture), externalId, Seq(Invite(other._1.playerId)))
                        .attempt
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    onInvite <- challengeService
                        .invite(fixture.game.gameId, created.challengeId, Invite(other._1.playerId), externalId)
                        .attempt
                } yield (onCreate, onInvite) match {
                    case (Left(_: ValidationError), Left(_: ValidationError)) => true
                    case _                                                    => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* One seat per character, not only per player. The acceptance names the player who made it, so a
     * character accepted by one owner and transferred to another would otherwise pass the per-player
     * check again and take a second seat -- on any challenge, invited or open. */
    property("a character already seated cannot accept again under a new owner") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, formerNickname, formerExternalId, newNickname, newExternalId) =>
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    former <- makeCharacterInGame(fixture.game, formerNickname, formerExternalId)
                    (_, character) = former
                    _ <- registrationService.register(newNickname, newExternalId)
                    // Open and role-less: nothing about an invitation is involved.
                    created <- challengeService.create(challengeFor(fixture), externalId)
                    _ <- challengeService.accept(
                      fixture.game.gameId,
                      created.challengeId,
                      Some(character.characterId),
                      fixture.game.roles(1).gameRoleId,
                      formerExternalId
                    )
                    _ <- TestServices.services.characters.update(
                      character.characterId,
                      character.name,
                      character.description,
                      newExternalId,
                      formerExternalId
                    )
                    again <- challengeService
                        .accept(
                          fixture.game.gameId,
                          created.challengeId,
                          Some(character.characterId),
                          fixture.game.roles(2).gameRoleId,
                          newExternalId
                        )
                        .attempt
                    seats <- TestSession.resource.use(session =>
                        new AcceptanceRepo(session).rolesForChallenge(fixture.game.gameId, created.challengeId)
                    )
                } yield again match {
                    case Left(_: ConflictError) => seats.size == 2
                    case _                      => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    /* `invitationsFor` reads a plain game's invitations and a character game's from two tables. Made
     * plain, then character, then plain: appending one list to the other would put both plain ones first,
     * so this is only newest-first if the two are merged by when each was made. */
    property("invitationsFor is newest first across plain and character games") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, invitedNickname, invitedExternalId) =>
                def plainChallenge(game: Game, challenger: PlayerId) =
                    PlainChallenge(
                      challengeId = ChallengeId(0),
                      challenger = challenger,
                      message = "plain",
                      start = None,
                      timeLimit = None,
                      settings = "{}",
                      gameId = game.gameId,
                      gameRoleId = game.roles.head.gameRoleId,
                      isOpen = false
                    )
                val result = for {
                    fixture <- makeFixture(nickname, externalId)
                    invited <- makeCharacterInGame(fixture.game, invitedNickname, invitedExternalId)
                    (invitee, character) = invited
                    firstGame <- makePlainGame
                    secondGame <- makePlainGame
                    oldest <- challengeService.create(
                      plainChallenge(firstGame, fixture.owner.playerId),
                      externalId,
                      Seq(Invite(invitee.playerId))
                    )
                    middle <- challengeService.create(
                      closedChallengeFor(fixture),
                      externalId,
                      characterInvitations = Seq(CharacterInvite(character.characterId))
                    )
                    newest <- challengeService.create(
                      plainChallenge(secondGame, fixture.owner.playerId),
                      externalId,
                      Seq(Invite(invitee.playerId))
                    )
                    listed <- challengeService.invitationsFor(invitedExternalId)
                } yield {
                    val ours = Set(oldest.challengeId, middle.challengeId, newest.challengeId)
                    listed.map(_.invitation.challengeId).filter(ours.contains) ==
                        List(newest.challengeId, middle.challengeId, oldest.challengeId)
                }
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Player invitations, in a plain game (V22). The properties above test a character game, whose
    // invitations name characters (V25); these keep the player-invitation paths under test.
    // ---------------------------------------------------------------------------------------------

    property("plain: a closed challenge is accepted by its invited player, as the seat held, and by nobody else") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                invitee <- register
                stranger <- register
                created <- challengeService.create(
                  plainChallengeFor(fixture, isOpen = false),
                  externalId,
                  Seq(Invite(invitee.player.playerId, Some(fixture.game.roles(1).gameRoleId)))
                )
                stranger <- plainAccept(fixture, created.challengeId, 2, stranger.externalId)
                wrongSeat <- plainAccept(fixture, created.challengeId, 2, invitee.externalId)
                accepted <- plainAccept(fixture, created.challengeId, 1, invitee.externalId)
            } yield (stranger, wrongSeat, accepted) match {
                case (Left(_: UnauthorizedError), Left(_: ValidationError), Right(a)) =>
                    a.playerId == invitee.player.playerId && a.isInstanceOf[PlainAcceptance]
                case _ => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: a role held for one player is not free for another, even on an open challenge") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                invitee <- register
                stranger <- register
                created <- challengeService.create(
                  plainChallengeFor(fixture),
                  externalId,
                  Seq(Invite(invitee.player.playerId, Some(fixture.game.roles(1).gameRoleId)))
                )
                held <- plainAccept(fixture, created.challengeId, 1, stranger.externalId)
                free <- plainAccept(fixture, created.challengeId, 2, stranger.externalId)
            } yield (held, free) match {
                case (Left(_: ConflictError), Right(_)) => true
                case _                                  => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: create refuses a player twice, one role for two, the challenger's own seat, and the challenger") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                first <- register
                second <- register
                role = fixture.game.roles(1).gameRoleId
                twice <- challengeService
                    .create(
                      plainChallengeFor(fixture),
                      externalId,
                      Seq(Invite(first.player.playerId), Invite(first.player.playerId))
                    )
                    .attempt
                sameRole <- challengeService
                    .create(
                      plainChallengeFor(fixture),
                      externalId,
                      Seq(Invite(first.player.playerId, Some(role)), Invite(second.player.playerId, Some(role)))
                    )
                    .attempt
                ownSeat <- challengeService
                    .create(
                      plainChallengeFor(fixture),
                      externalId,
                      Seq(Invite(first.player.playerId, Some(fixture.game.roles.head.gameRoleId)))
                    )
                    .attempt
                themselves <- challengeService
                    .create(plainChallengeFor(fixture), externalId, Seq(Invite(fixture.owner.playerId)))
                    .attempt
            } yield (twice, sameRole, ownSeat, themselves) match {
                case (
                      Left(_: ValidationError),
                      Left(_: ValidationError),
                      Left(_: ConflictError),
                      Left(_: ValidationError)
                    ) =>
                    true
                case _ => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: invite adds an invitation, only the challenger may, and not twice or to somebody seated") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                invitee <- register
                seated <- register
                created <- challengeService.create(plainChallengeFor(fixture), externalId)
                _ <- plainAccept(fixture, created.challengeId, 2, seated.externalId)
                byStranger <- challengeService
                    .invite(
                      fixture.game.gameId,
                      created.challengeId,
                      Invite(invitee.player.playerId),
                      invitee.externalId
                    )
                    .attempt
                invited <- challengeService.invite(
                  fixture.game.gameId,
                  created.challengeId,
                  Invite(invitee.player.playerId, Some(fixture.game.roles(1).gameRoleId)),
                  externalId
                )
                twice <- challengeService
                    .invite(
                      fixture.game.gameId,
                      created.challengeId,
                      Invite(invitee.player.playerId),
                      externalId
                    )
                    .attempt
                toSeated <- challengeService
                    .invite(fixture.game.gameId, created.challengeId, Invite(seated.player.playerId), externalId)
                    .attempt
                held <- plainInvitationsOf(fixture.game, created.challengeId)
            } yield (byStranger, twice, toSeated) match {
                case (Left(_: UnauthorizedError), Left(_: ConflictError), Left(_: ConflictError)) =>
                    held == List(invited)
                case _ => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: reject deletes one invitation, and is refused once the player has accepted") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                first <- register
                second <- register
                created <- challengeService.create(
                  plainChallengeFor(fixture, isOpen = false),
                  externalId,
                  Seq(Invite(first.player.playerId), Invite(second.player.playerId))
                )
                _ <- challengeService.reject(fixture.game.gameId, created.challengeId, first.externalId)
                left <- plainInvitationsOf(fixture.game, created.challengeId)
                _ <- plainAccept(fixture, created.challengeId, 1, second.externalId)
                afterAccepting <- challengeService
                    .reject(fixture.game.gameId, created.challengeId, second.externalId)
                    .attempt
                again <- challengeService.reject(fixture.game.gameId, created.challengeId, first.externalId).attempt
            } yield (afterAccepting, again) match {
                case (Left(_: ConflictError), Left(_: NotFoundError)) =>
                    left.map(_.playerId) == List(second.player.playerId)
                case _ => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: revoke is the challenger's alone, and refused once the player has accepted") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                invitee <- register
                accepter <- register
                created <- challengeService.create(
                  plainChallengeFor(fixture, isOpen = false),
                  externalId,
                  Seq(Invite(invitee.player.playerId), Invite(accepter.player.playerId))
                )
                byInvitee <- challengeService
                    .revoke(
                      fixture.game.gameId,
                      created.challengeId,
                      invitee.player.playerId,
                      invitee.externalId
                    )
                    .attempt
                _ <- challengeService.revoke(
                  fixture.game.gameId,
                  created.challengeId,
                  invitee.player.playerId,
                  externalId
                )
                again <- challengeService
                    .revoke(fixture.game.gameId, created.challengeId, invitee.player.playerId, externalId)
                    .attempt
                _ <- plainAccept(fixture, created.challengeId, 1, accepter.externalId)
                seated <- challengeService
                    .revoke(fixture.game.gameId, created.challengeId, accepter.player.playerId, externalId)
                    .attempt
                left <- plainInvitationsOf(fixture.game, created.challengeId)
            } yield (byInvitee, again, seated) match {
                case (Left(_: UnauthorizedError), Left(_: NotFoundError), Left(e: ConflictError)) =>
                    e.message == "That player has already accepted this challenge. Remove their acceptance instead." &&
                    left.map(_.playerId) == List(accepter.player.playerId)
                case _ => false
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("a plain game refuses an invitation to a character, on create and on invite") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId) =>
                val result = for {
                    fixture <- makePlainFixture(nickname, externalId)
                    // A real character, from a character game: what is refused is the kind of
                    // invitation, before anything about the character is looked at.
                    elsewhere <- makeFixture(otherNickname, otherExternalId)
                    onCreate <- challengeService
                        .create(
                          plainChallengeFor(fixture),
                          externalId,
                          characterInvitations = Seq(CharacterInvite(elsewhere.character.characterId))
                        )
                        .attempt
                    created <- challengeService.create(plainChallengeFor(fixture), externalId)
                    onInvite <- challengeService
                        .inviteCharacter(
                          fixture.game.gameId,
                          created.challengeId,
                          CharacterInvite(elsewhere.character.characterId),
                          externalId
                        )
                        .attempt
                } yield (onCreate, onInvite) match {
                    case (Left(_: ValidationError), Left(_: ValidationError)) => true
                    case _                                                    => false
                }
                result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: listByGame shows a closed challenge to its challenger and invitee only, and who accepted") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                accepter <- register
                waiting <- register
                stranger <- register
                created <- challengeService.create(
                  plainChallengeFor(fixture, isOpen = false),
                  externalId,
                  Seq(Invite(accepter.player.playerId), Invite(waiting.player.playerId))
                )
                _ <- plainAccept(fixture, created.challengeId, 1, accepter.externalId)
                mine <- challengeService.listByGame(fixture.game.gameId, externalId)
                theirs <- challengeService.listByGame(fixture.game.gameId, waiting.externalId)
                strangers <- challengeService.listByGame(fixture.game.gameId, stranger.externalId)
            } yield {
                def find(listed: List[ChallengeSummary]) = listed.find(_.challenge.challengeId == created.challengeId)
                find(mine).exists(summary =>
                    summary.challenge.isInstanceOf[PlainChallenge] &&
                        summary.invitations.map(_.playerId).toSet == Set(
                          accepter.player.playerId,
                          waiting.player.playerId
                        ) &&
                        summary.acceptedInvitees == Seq(accepter.player.playerId) &&
                        summary.invitedCharacters.isEmpty
                ) && find(theirs).isDefined && find(strangers).isEmpty
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

    property("plain: invitationsFor lists a player invitation with no character, and keeps it once accepted") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                fixture <- makePlainFixture(nickname, externalId)
                invitee <- register
                role = fixture.game.roles(1).gameRoleId
                created <- challengeService.create(
                  plainChallengeFor(fixture, isOpen = false),
                  externalId,
                  Seq(Invite(invitee.player.playerId, Some(role)))
                )
                before <- challengeService.invitationsFor(invitee.externalId)
                _ <- plainAccept(fixture, created.challengeId, 1, invitee.externalId)
                after <- challengeService.invitationsFor(invitee.externalId)
            } yield {
                def mine(listed: List[ChallengeInvitation]) =
                    listed.filter(_.invitation.challengeId == created.challengeId)
                // Unlike a character's, a plain invitation stays listed once accepted: the acceptance
                // names the same player the invitation does, so the screen filters it against its own.
                mine(before).sizeIs == 1 && mine(after) == mine(before) &&
                mine(before).head.character.isEmpty &&
                mine(before).head.gameType == GameType.Plain &&
                mine(before).head.roleName.contains(fixture.game.roles(1).name)
            }
            result.timeout(20.seconds).unsafeRunSync()
        }
    }

}
