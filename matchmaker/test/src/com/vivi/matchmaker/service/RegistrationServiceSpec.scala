package com.vivi.matchmaker.service

import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import org.scalacheck.Gen

class RegistrationServiceSpec extends ScalaCheckSuite {
  private val config = DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))
  private val service = new RegistrationService(config)

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  property("register creates a non-admin player with the given nickname and externalId") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      service.register(nickname, externalId).unsafeRunSync() match {
        case p => p.nickname == nickname && p.externalId == externalId && !p.isAdmin
      }
    }
  }

  property("register rejects a blank nickname") {
    forAll(genUniqueString) { externalId =>
      service.register("", externalId).attempt.unsafeRunSync() match {
        case Left(_: ValidationError) => true
        case _                        => false
      }
    }
  }

  property("register rejects a blank externalId") {
    forAll(genUniqueString) { nickname =>
      service.register(nickname, "").attempt.unsafeRunSync() match {
        case Left(_: ValidationError) => true
        case _                        => false
      }
    }
  }

  property("register rejects a duplicate externalId") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname1, nickname2, externalId) =>
      val result = for {
        _ <- service.register(nickname1, externalId)
        second <- service.register(nickname2, externalId).attempt
      } yield second
      result.unsafeRunSync() match {
        case Left(_: ConflictError) => true
        case _                      => false
      }
    }
  }

  property("register rejects a duplicate nickname") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId1, externalId2) =>
      val result = for {
        _ <- service.register(nickname, externalId1)
        second <- service.register(nickname, externalId2).attempt
      } yield second
      result.unsafeRunSync() match {
        case Left(_: ConflictError) => true
        case _                      => false
      }
    }
  }
}
