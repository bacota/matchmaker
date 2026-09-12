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

  /* The email address. Cognito owns it; this only records what Cognito already accepted, so what
   * these check is that the copy tracks whatever Cognito accepted and that the row's other columns
   * are left alone. Nothing checks uniqueness, because nothing requires it. */

  property("updateEmail records the address and me reads it back, leaving the rest of the row alone") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, local) =>
      val address = s"$local@example.com"
      val result = for {
        registered <- registrationService.register(nickname, externalId)
        returned <- playerService.updateEmail(externalId, address)
        found <- playerService.me(externalId)
      } yield returned == found &&
        found.email.contains(address) &&
        found.playerId == registered.playerId &&
        found.nickname == nickname &&
        found.externalId == externalId &&
        found.isAdmin == registered.isAdmin
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("register records the address it is given, and none when it is not") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val address = s"$externalId@example.com"
        val result = for {
          withEmail <- registrationService.register(nickname, externalId, Some(address))
          without <- registrationService.register(otherNickname, otherExternalId)
        } yield withEmail.email.contains(address) && without.email.isEmpty
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // Called after every confirmation the form sees, and a player who confirms twice has one
  // address, not an error.
  // The reason the repo has a separate writer for the address. A rename goes through the general
  // update, which no longer carries email -- so the address a player signed in with survives it.
  property("renaming does not disturb the address") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, local, renamed) =>
        val address = s"$local@example.com"
        val result = for {
          _ <- registrationService.register(nickname, externalId, Some(address))
          _ <- playerService.updateNickname(externalId, renamed)
          found <- playerService.me(externalId)
        } yield found.nickname == renamed && found.email.contains(address)
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateEmail is idempotent") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, local) =>
      val address = s"$local@example.com"
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        _ <- playerService.updateEmail(externalId, address)
        again <- playerService.updateEmail(externalId, address)
      } yield again.email.contains(address)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateEmail trims what it is given") {
    forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, local) =>
      val address = s"$local@example.com"
      val result = for {
        _ <- registrationService.register(nickname, externalId)
        returned <- playerService.updateEmail(externalId, s"  $address  ")
      } yield returned.email.contains(address)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateEmail refuses what is not an address, leaving the old one in place") {
    val genNotAnAddress = Gen.oneOf("", "   ", "nobody", "@example.com", "someone@", "two@at@example.com", "a b@example.com")

    forAll(genUniqueString, genUniqueString, genUniqueString, genNotAnAddress) { (nickname, externalId, local, bad) =>
      val address = s"$local@example.com"
      val result = for {
        _ <- registrationService.register(nickname, externalId, Some(address))
        outcome <- playerService.updateEmail(externalId, bad).attempt
        found <- playerService.me(externalId)
      } yield outcome.left.exists(_.isInstanceOf[ValidationError]) && found.email.contains(address)
      result.timeout(10.seconds).unsafeRunSync()
    }
  }

  // Two players, one mailbox. Nothing constrains the address, so this is allowed on purpose: a
  // household sharing an inbox is two identities with their own nicknames and their own
  // notifications, which happen to arrive in the same place.
  property("two players may hold the same address") {
    forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
      (nickname, externalId, otherNickname, otherExternalId) =>
        val shared = s"$externalId@example.com"
        val result = for {
          _ <- registrationService.register(nickname, externalId, Some(shared))
          _ <- registrationService.register(otherNickname, otherExternalId)
          _ <- playerService.updateEmail(otherExternalId, shared)
          first <- playerService.me(externalId)
          second <- playerService.me(otherExternalId)
        } yield first.email.contains(shared) && second.email.contains(shared) && first.playerId != second.playerId
        result.timeout(10.seconds).unsafeRunSync()
    }
  }

  property("updateEmail rejects a caller who has never registered") {
    forAll(genUniqueString, genUniqueString) { (externalId, local) =>
      playerService
        .updateEmail(externalId, s"$local@example.com")
        .attempt
        .timeout(10.seconds)
        .unsafeRunSync() match {
        case Left(_: UnauthorizedError) => true
        case _                          => false
      }
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
