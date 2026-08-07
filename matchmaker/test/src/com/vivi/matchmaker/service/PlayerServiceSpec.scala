package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}

class PlayerServiceSpec extends PropertySuite {
  TestMigration.ensure()

  private val playerService = TestServices.services.players
  private val registrationService = TestServices.services.registration

  private def genUniqueString: Gen[String] =
    Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)).map(s => s"$s-${java.util.UUID.randomUUID()}")

  property("me returns the player registered under the caller's externalId") {
    forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
      val result = for {
        registered <- registrationService.register(nickname, externalId)
        found <- playerService.me(externalId)
      } yield found == registered
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("me rejects an externalId that has never registered") {
    forAll(genUniqueString) { externalId =>
      playerService.me(externalId).attempt.timeout(10.seconds).unsafeRunSync() match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
    }
  }
}
