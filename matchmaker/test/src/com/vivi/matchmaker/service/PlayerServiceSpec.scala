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

  /* Renaming. The rest of the row matters as much as the nickname does: the service copies
   * isAdmin and externalId from what is stored rather than from the caller, and a regression
   * there is a privilege escalation rather than a cosmetic bug. */

  property("updateNickname renames the caller and the new name is what me reads back") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, renamed, externalId) =>
      val result = for {
        registered <- registrationService.register(nickname, externalId)
        returned <- playerService.updateNickname(externalId, renamed)
        found <- playerService.me(externalId)
      } yield returned == found &&
        found.nickname == renamed &&
        found.playerId == registered.playerId &&
        found.externalId == externalId &&
        found.isAdmin == registered.isAdmin
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateNickname trims what it is given") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, renamed, externalId) =>
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        returned <- playerService.updateNickname(externalId, s"  $renamed  ")
      } yield returned.nickname == renamed
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateNickname refuses a blank nickname, leaving the old one in place") {
    val genBlank = Gen.listOf(Gen.oneOf(' ', '\t', '\n')).map(_.mkString)

    forAll(genUniqueString, genUniqueString, genBlank) { (nickname, externalId, blank) =>
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        outcome <- playerService.updateNickname(externalId, blank).attempt
        found <- playerService.me(externalId)
      } yield outcome.isLeft && outcome.left.exists(_.isInstanceOf[ValidationError]) && found.nickname == nickname
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateNickname refuses a nickname another player already has") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val result = for {
          _ <- registrationService.register(nickname, externalId)
          _ <- registrationService.register(otherNickname, otherExternalId)
          outcome <- playerService.updateNickname(externalId, otherNickname).attempt
          found <- playerService.me(externalId)
        } yield outcome.left.exists(_.isInstanceOf[ConflictError]) && found.nickname == nickname
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // Renaming is scoped to the caller, so an identity with no player has nothing to rename — the
  // same UnauthorizedError me gives, not a NotFoundError for a player that was never named.
  property("updateNickname rejects a caller who has never registered") {
    forAll(genUniqueString, genUniqueString) { (externalId, renamed) =>
      playerService.updateNickname(externalId, renamed).attempt.timeout(10.seconds).unsafeRunSync() match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
    }
  }
}
