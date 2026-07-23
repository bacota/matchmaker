package com.vivi.matchmaker.service

import java.security.{KeyPairGenerator, PrivateKey, Signature}
import java.util.Base64
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

class CharacterServiceSpec extends ScalaCheckSuite {
  TestMigration.ensure()

  // RSA key generation is expensive (each property here generates one or two 2048-bit
  // keypairs per check), and these properties don't depend on scanning many random inputs
  // for correctness, so run each one only once instead of munit-scalacheck's default of 100.
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1)

  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val characterService = new CharacterService[String](config)
  private val registrationService = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  private def sign(privateKey: PrivateKey, state: String, externalId: String): String = {
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update((state + externalId).getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(signer.sign())
  }

  /** A fresh RSA keypair, so each property gets its own game/key pair and can't collide with
    * signatures generated for another game.
    */
  private def genKeyPair(): (PrivateKey, String) = {
    val keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair()
    (keyPair.getPrivate, Base64.getEncoder.encodeToString(keyPair.getPublic.getEncoded))
  }

  private def makeCharacterGame(verificationKey: String): IO[CharacterGame] =
    TestSession.resource.use { session =>
      new GameRepo[String](session).create(
        CharacterGame(GameId(0), "game", "description", "url", active = true, Seq.empty, Seq.empty, verificationKey)
      )
    }.map(_.asInstanceOf[CharacterGame])

  property("create creates a character owned by the signed-for player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, name, state) =>
      val (privateKey, verificationKey) = genKeyPair()
      val result = for {
        player <- registrationService.register(nickname, externalId)
        game <- makeCharacterGame(verificationKey)
        signature = sign(privateKey, state, externalId)
        created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, signature)
      } yield created.name == name && created.state == state && created.playerId == Some(player.playerId)

      result.unsafeRunSync()
    }
  }

  property("create rejects an invalid signature") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, name, state) =>
      val (_, verificationKey) = genKeyPair()
      val (wrongPrivateKey, _) = genKeyPair()
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        game <- makeCharacterGame(verificationKey)
        signature = sign(wrongPrivateKey, state, externalId)
        attempt <- characterService.create(game.gameId, name, "description", state, externalId, externalId, signature).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.unsafeRunSync()
    }
  }

  property("create rejects a caller acting on behalf of another player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, callerExternalId, name, state) =>
        val (privateKey, verificationKey) = genKeyPair()
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(verificationKey)
          signature = sign(privateKey, state, externalId)
          attempt <- characterService.create(game.gameId, name, "description", state, externalId, callerExternalId, signature).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }

        result.unsafeRunSync()
    }
  }

  property("update changes name, description, and state when signed and authorized by the current owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, state, newName, newState) =>
        val (privateKey, verificationKey) = genKeyPair()
        val result = for {
          player <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(verificationKey)
          createSignature = sign(privateKey, state, externalId)
          created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, createSignature)
          updateSignature = sign(privateKey, newState, externalId)
          updated <- characterService.update(
            created.characterId,
            newName,
            "new description",
            newState,
            externalId,
            externalId,
            updateSignature
          )
        } yield updated.characterId == created.characterId &&
          updated.name == newName &&
          updated.state == newState &&
          updated.playerId == Some(player.playerId)

        result.unsafeRunSync()
    }
  }

  property("update rejects a caller who is not the character's current owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherExternalId, name, state, newState) =>
        val (privateKey, verificationKey) = genKeyPair()
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(verificationKey)
          createSignature = sign(privateKey, state, externalId)
          created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, createSignature)
          updateSignature = sign(privateKey, newState, externalId)
          attempt <- characterService
            .update(created.characterId, name, "description", newState, externalId, otherExternalId, updateSignature)
            .attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }

        result.unsafeRunSync()
    }
  }
}
