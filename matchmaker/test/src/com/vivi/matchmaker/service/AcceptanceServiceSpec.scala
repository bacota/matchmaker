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

  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val challengeService = new OpenChallengeService[String](config)
  private val acceptanceService = new AcceptanceService(config)
  private val registrationService = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private case class Fixture(owner: Player, game: Game, character: Character[String])

  private def makeFixture(nickname: String, externalId: String): IO[Fixture] =
    TestSession.resource.use { session =>
      for {
        owner <- registrationService.register(nickname, externalId)
        game <- new GameRepo[String](session).create(
          Game(GameId.unassigned, "game", "description", "url", active = true, Seq.empty, Seq.empty, genUniqueString.sample.get, 2, 4)
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
    OpenChallenge(ChallengeId(0), fixture.owner.playerId, "message", numberOfPlayers.toShort, None, None, "{}", fixture.game.gameId, fixture.character.characterId)

  private def setUp(nickname: String, externalId: String, accepterNickname: String, accepterExternalId: String) =
    for {
      fixture <- makeFixture(nickname, externalId)
      created <- challengeService.create(challengeFor(fixture, 3), externalId)
      accepter <- makeCharacterInGame(fixture.game, accepterNickname, accepterExternalId)
      (accepterPlayer, accepterCharacter) = accepter
      accepted <- challengeService.accept(created.challengeId, accepterCharacter.characterId, accepterExternalId)
    } yield (fixture, created, accepterPlayer, accepted)

  property("delete removes the acceptance when called by the acceptor") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (_, created, accepterPlayer, _) = setup
          _ <- acceptanceService.delete(created.challengeId, accepterPlayer.playerId, accepterExternalId)
          remaining <- TestSession.resource.use(session => new AcceptanceRepo(session).read(created.challengeId, accepterPlayer.playerId))
        } yield remaining.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete removes the acceptance when called by the challenger") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (_, created, accepterPlayer, _) = setup
          _ <- acceptanceService.delete(created.challengeId, accepterPlayer.playerId, externalId)
          remaining <- TestSession.resource.use(session => new AcceptanceRepo(session).read(created.challengeId, accepterPlayer.playerId))
        } yield remaining.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("delete rejects a caller who is neither the acceptor nor the challenger") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, accepterNickname, accepterExternalId, otherExternalId) =>
        val result = for {
          setup <- setUp(nickname, externalId, accepterNickname, accepterExternalId)
          (_, created, accepterPlayer, _) = setup
          attempt <- acceptanceService.delete(created.challengeId, accepterPlayer.playerId, otherExternalId).attempt
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
          attempt <- acceptanceService.delete(created.challengeId, other.playerId, otherExternalId).attempt
        } yield attempt match {
          case Left(_: NotFoundError) => true
          case _                      => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
