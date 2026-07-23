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

  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val characterService = new CharacterService[String](config)
  private val registrationService = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private def makeCharacterGame(gameExternalId: String): IO[Game] =
    TestSession.resource.use { session =>
      new GameRepo[String](session).create(
        Game(GameId(0), "game", "description", "url", active = true, Seq.empty, Seq.empty, gameExternalId, minPlayers = 2, maxPlayers = 4)
      )
    }

  property("create creates a character owned by the given player with empty state") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, gameExternalId) =>
        val result = for {
          player <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          created <- characterService.create(game.gameId, name, "description", externalId, externalId)
        } yield created.name == name && created.state == "" && created.playerId == Some(player.playerId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a caller acting on behalf of another player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, callerExternalId, name, gameExternalId) =>
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          attempt <- characterService.create(game.gameId, name, "description", externalId, callerExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
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
          created <- characterService.create(game.gameId, name, "description", externalId, externalId)
          updated <- characterService.update(created.characterId, newName, "new description", externalId, externalId)
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
          created <- characterService.create(game.gameId, name, "description", externalId, externalId)
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
          created <- characterService.create(game.gameId, name, "description", externalId, externalId)
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
          created <- characterService.create(game.gameId, name, "description", externalId, externalId)
          attempt <- characterService.updateState(created.characterId, newState, wrongGameExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
