package com.vivi.matchmaker.service

import java.security.{KeyFactory, PrivateKey, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
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

  private def sign(privateKey: PrivateKey, state: String, externalId: String): String = {
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update((state + externalId).getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(signer.sign())
  }

  // Hardcoded 512-bit RSA keypairs (test-only; too weak for real use) so properties don't pay
  // for keypair generation on every check. Two distinct pairs let "wrong key" tests use a
  // different, equally-valid key rather than a private key that doesn't match any public key.
  private def decodePrivateKey(base64: String): PrivateKey =
    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder.decode(base64)))

  private val keyPair1PrivateBase64 =
    "MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEAmk8EL5ldgkSbaU8SBsIztvk6wpbetXDN0G0Lihmnt8iALU30BspEvW1+ctXmnu/LLvsBDX3NsbvjFDLHh2rqzQIDAQABAkAV1C28ag6vWfM+P4BGUnysWq90TZFty2piHLrwK1btiYc4I2GSEd4C7vgFe7Ixgl9pn8RlWu45igR7Ab8piIBhAiEAovfT7PTk9V8cMLlKrkiqoS124/ishUU0Jre3G/GXuIUCIQDyZbMxzWdION/7LVU3zoJUOeyn42+84e8XNRK1aGIfqQIgRDTfMMxqSzvsS4QxenIVX/HsUYuRgRGuuwmnDH331xUCIFeIJieL1wobj7Zybl2SszmbGTyfQtBgfihRQApGQXjRAiEAjvFFagAOIlryh+H0CUz7c0d/tHa8T6NUAwz8M74FwxU="
  private val keyPair1PublicBase64 =
    "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAJpPBC+ZXYJEm2lPEgbCM7b5OsKW3rVwzdBtC4oZp7fIgC1N9AbKRL1tfnLV5p7vyy77AQ19zbG74xQyx4dq6s0CAwEAAQ=="
  private val keyPair2PrivateBase64 =
    "MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEA40uiAP7qB3WoruyT1s62rkYeQQNSMdo8rgaRYoKBfHGI1if33unkt0aIjBFa6dX/6g7onoJtCIdriHPqJI5NwwIDAQABAkAMs1c0EwpkrFBmpdWE9TwD9OsP2u2m13j4iGlrRbuShnKLtalgNFvEoEwOcw6rsV48+Luvi8UAo+kTI9F6DlcRAiEA41//eoj4skt675RPglfhSlZaR8Rcaz4dMQyOAC270hUCIQD/6RIu6MQJ8ib9Bczz69qRnvZvVp8MP4P5ZZruKtiOdwIgdCt8EFMjHZVK/lU8OlBEHwL3pWtB/NkDeSf89UJoj/ECIQC+QjW2km9NRa8e5jUeE/eH1Ds7Q5czr/UaciPhdhFSuQIgHjB2ngVGGt2gD9W0rbuZ9pL1/t8YthBFGBHGnL+RPW8="
  private val keyPair2PublicBase64 =
    "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAONLogD+6gd1qK7sk9bOtq5GHkEDUjHaPK4GkWKCgXxxiNYn997p5LdGiIwRWunV/+oO6J6CbQiHa4hz6iSOTcMCAwEAAQ=="

  private val keyPair1: (PrivateKey, String) = (decodePrivateKey(keyPair1PrivateBase64), keyPair1PublicBase64)
  private val keyPair2: (PrivateKey, String) = (decodePrivateKey(keyPair2PrivateBase64), keyPair2PublicBase64)

  private def makeCharacterGame(verificationKey: String): IO[CharacterGame] =
    TestSession.resource.use { session =>
      new GameRepo[String](session).create(
        CharacterGame(GameId(0), "game", "description", "url", active = true, Seq.empty, Seq.empty, verificationKey)
      )
    }.map(_.asInstanceOf[CharacterGame])

  property("create creates a character owned by the signed-for player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, name, state) =>
      val (privateKey, verificationKey) = keyPair1
      val result = for {
        player <- registrationService.register(nickname, externalId)
        game <- makeCharacterGame(verificationKey)
        signature = sign(privateKey, state, externalId)
        created <- characterService.create(game.gameId, name, "description", state, externalId, externalId, signature)
      } yield created.name == name && created.state == state && created.playerId == Some(player.playerId)

      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects an invalid signature") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, name, state) =>
      val (_, verificationKey) = keyPair1
      val (wrongPrivateKey, _) = keyPair2
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        game <- makeCharacterGame(verificationKey)
        signature = sign(wrongPrivateKey, state, externalId)
        attempt <- characterService.create(game.gameId, name, "description", state, externalId, externalId, signature).attempt
      } yield attempt match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }

      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("create rejects a caller acting on behalf of another player") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, callerExternalId, name, state) =>
        val (privateKey, verificationKey) = keyPair1
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          game <- makeCharacterGame(verificationKey)
          signature = sign(privateKey, state, externalId)
          attempt <- characterService.create(game.gameId, name, "description", state, externalId, callerExternalId, signature).attempt
        } yield attempt match {
          case Left(_: UnauthorizedError) => true
          case _                          => false
        }

        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("update changes name, description, and state when signed and authorized by the current owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, name, state, newName, newState) =>
        val (privateKey, verificationKey) = keyPair1
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

        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("update rejects a caller who is not the character's current owner") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherExternalId, name, state, newState) =>
        val (privateKey, verificationKey) = keyPair1
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

        result.timeout(10.seconds).unsafeRunSync()
    }
  }
}
