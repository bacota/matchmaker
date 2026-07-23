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
        Game(GameId(0), "game", "description", "url", active = true, Seq.empty, Seq.empty, gameExternalId)
      )
    }

  property("create creates a character owned for the given player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, state, gameExternalId) =>
        val result = for {
          player <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, gameExternalId)
        } yield created.name == name && created.state == state && created.playerId == Some(player.playerId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects an invalid game externalId") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, state, gameExternalId, wrongGameExternalId) =>
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          attempt <- characterService.create(game.gameId, name, "description", state, externalId, externalId, wrongGameExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a caller acting on behalf of another player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, callerExternalId, name, state, gameExternalId) =>
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          attempt <- characterService.create(game.gameId, name, "description", state, externalId, callerExternalId, gameExternalId).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("update changes name, description, and state when authorized by the current owner and game") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, state, newName, newState, gameExternalId) =>
        val result = for {
          player <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, gameExternalId)
          updated <- characterService.update(
            created.characterId,
            newName,
            "new description",
            newState,
            externalId,
            externalId,
            gameExternalId
          )
        } yield updated.characterId == created.characterId &&
          updated.name == newName &&
          updated.state == newState &&
          updated.playerId == Some(player.playerId)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("update rejects a caller who is not the character's current owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherExternalId, name, state, newState, gameExternalId) =>
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(gameExternalId)
          created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, gameExternalId)
          attempt <- characterService
            .update(created.characterId, name, "description", newState, externalId, otherExternalId, gameExternalId)
            .attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }
        result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
