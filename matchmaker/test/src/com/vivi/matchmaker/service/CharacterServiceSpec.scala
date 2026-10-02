package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

class CharacterServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val characterService = TestServices.services.characters
    private val registrationService = TestServices.services.registration

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private def makeCharacterGame(gameExternalId: String): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Character,
                "game",
                "description",
                "url",
                active = true,
                Seq.empty,
                Seq.empty,
                gameExternalId
              )
            )
        }

    private def makePlainGame(gameExternalId: String): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "game",
                "description",
                "url",
                active = true,
                Seq.empty,
                Seq.empty,
                gameExternalId
              )
            )
        }

    property("create records a character the game made, owned by the given player with the state it was given") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, state, gameExternalId) =>
                val result = for {
                    player <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, state, gameExternalId)
                    found <- characterService.listForGame(game.gameId, externalId)
                } yield created.name == name && created.state == state && created.playerId == Some(player.playerId) &&
                    found.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects a player making a character for themselves: characters are made by their game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    attempt <- characterService
                        .create(name, "description", externalId, "", externalId)
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create puts the character in the game whose identity made it, not another") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId, otherGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    other <- makeCharacterGame(otherGameExternalId)
                    created <- characterService.create(name, "description", externalId, "", otherGameExternalId)
                    inGame <- characterService.listForGame(game.gameId, externalId)
                    inOther <- characterService.listForGame(other.gameId, externalId)
                } yield created.gameId == other.gameId && inGame.isEmpty &&
                    inOther.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects a character in a game not played with characters") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makePlainGame(gameExternalId)
                    attempt <- characterService
                        .create(name, "description", externalId, "", gameExternalId)
                        .attempt
                } yield attempt match {
                    case Left(_: ValidationError) => true
                    case _                        => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects an owner who has not registered with matchmaker") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (unknownExternalId, name, gameExternalId) =>
            val result = for {
                game <- makeCharacterGame(gameExternalId)
                attempt <- characterService
                    .create(name, "description", unknownExternalId, "", gameExternalId)
                    .attempt
            } yield attempt match {
                case Left(_: NotFoundError) => true
                case _                      => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("update changes name and description but not state when authorized by the current owner") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newName, gameExternalId) =>
                val result = for {
                    player <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, "", gameExternalId)
                    updated <- characterService.update(
                      created.characterId,
                      newName,
                      "new description",
                      externalId,
                      externalId
                    )
                } yield updated.characterId == created.characterId &&
                    updated.name == newName &&
                    updated.state == created.state &&
                    updated.playerId == Some(player.playerId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("update rejects a caller who is not the character's current owner") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherExternalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, "", gameExternalId)
                    attempt <- characterService
                        .update(created.characterId, name, "description", externalId, otherExternalId)
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("updateState changes the state when authorized by the character's game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newState, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, "", gameExternalId)
                    updated <- characterService.updateState(created.characterId, newState, gameExternalId)
                } yield updated.characterId == created.characterId && updated.state == newState
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("updateState rejects a caller whose externalId does not match the character's game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newState, gameExternalId, wrongGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, "", gameExternalId)
                    attempt <- characterService.updateState(created.characterId, newState, wrongGameExternalId).attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame returns the caller's characters in that game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(name, "description", externalId, "", gameExternalId)
                    found <- characterService.listForGame(game.gameId, externalId)
                } yield found.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame shows a player nothing of another player's characters") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname, externalId)
                    _ <- registrationService.register(otherNickname, otherExternalId)
                    game <- makeCharacterGame(gameExternalId)
                    _ <- characterService.create(name, "description", externalId, "", gameExternalId)
                    found <- characterService.listForGame(game.gameId, otherExternalId)
                } yield found.isEmpty
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame rejects a caller with no player") {
        forAll(genUniqueString, genUniqueString) { (unknownExternalId, gameExternalId) =>
            val result = for {
                game <- makeCharacterGame(gameExternalId)
                attempt <- characterService.listForGame(game.gameId, unknownExternalId).attempt
            } yield attempt match {
                case Left(_: UnauthorizedError) => true
                case _                          => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }
}
