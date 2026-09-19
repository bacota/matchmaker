package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}

class PlayerServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val playerService = TestServices.services.players
    private val registrationService = TestServices.services.registration

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

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

    /* Searching. Every case here uses a nickname built from a UUID, so the prefix it searches for
     * matches nothing else in a database every other suite is also registering players in. */

    property("search finds a player by a prefix of their nickname") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                registered <- registrationService.register(nickname, externalId)
                found <- playerService.search(externalId, nickname.dropRight(1))
            } yield found.players.map(_.playerId) == List(registered.playerId) &&
                found.players.map(_.nickname) == List(nickname) &&
                !found.more
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* A prefix, not a substring: searching for the middle of a nickname finds nothing, which is
     * what makes the search something a player can predict the results of. */
    property("search does not match the middle of a nickname") {
        forAll(genUniqueString, genUniqueString) { (nickname, externalId) =>
            val result = for {
                _ <- registrationService.register(nickname, externalId)
                found <- playerService.search(externalId, nickname.drop(4))
            } yield found.players.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* Case sensitive, as specified -- and as the unique index on nickname already is: "Ash" and
     * "ash" are two registrable names, so a search that folded case would offer each as the other. */
    property("search is case sensitive") {
        forAll(genUniqueString, genUniqueString) { (suffix, externalId) =>
            val nickname = s"A$suffix"
            val result = for {
                _ <- registrationService.register(nickname, externalId)
                same <- playerService.search(externalId, s"A${suffix.take(4)}")
                flipped <- playerService.search(externalId, s"a${suffix.take(4)}")
            } yield same.players.map(_.nickname) == List(nickname) && flipped.players.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* The prefix is text, not a pattern. Under LIKE the `%` below would be a wildcard and this
     * search would return both players; `starts_with` has no pattern language to escape. */
    property("a wildcard character in the prefix matches itself") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (base, literalId, otherId) =>
            val literal = s"$base%z"
            val other = s"${base}xz"
            val result = for {
                registered <- registrationService.register(literal, literalId)
                _ <- registrationService.register(other, otherId)
                found <- playerService.search(literalId, s"$base%")
            } yield found.players.map(_.playerId) == List(registered.playerId) && !found.more
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* A page, and the reply says so. One `test` rather than a property: it registers a player per
     * row, and the thing being checked is a boundary, not a range of inputs. */
    test("search shows at most the limit and says when there are more") {
        val base = s"prefix-${java.util.UUID.randomUUID()}"
        val caller = genUniqueString.sample.get

        val result = for {
            _ <- registrationService.register(caller, caller)
            // One more than the limit, so the page is full *and* something is left over.
            registered <- (1 to PlayerService.searchLimit + 1).toList
                .traverse(n => registrationService.register(f"$base-$n%03d", genUniqueString.sample.get))
            full <- playerService.search(caller, base)
            // A prefix that matches exactly one of them says the opposite, which is the half of this
            // that would still pass if `more` were hard-coded true.
            one <- playerService.search(caller, registered.head.nickname)
        } yield full.players.length == PlayerService.searchLimit && full.more &&
            // Nickname order, and the limit applied after it: the first page is the first names.
            full.players.map(_.nickname) == registered.map(_.nickname).sorted.take(PlayerService.searchLimit) &&
            one.players.length == 1 && !one.more

        assert(result.timeout(60.seconds).unsafeRunSync())
    }

    property("search refuses a blank prefix") {
        forAll(genUniqueString) { externalId =>
            val result = for {
                _ <- registrationService.register(externalId, externalId)
                outcome <- playerService.search(externalId, "   ").attempt
            } yield outcome match {
                case Left(_: ValidationError) => true
                case _                        => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("search rejects a caller who has never registered") {
        forAll(genUniqueString) { externalId =>
            playerService.search(externalId, "a").attempt.timeout(10.seconds).unsafeRunSync() match {
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
        val genNotAnAddress =
            Gen.oneOf("", "   ", "nobody", "@example.com", "someone@", "two@at@example.com", "a b@example.com")

        forAll(genUniqueString, genUniqueString, genUniqueString, genNotAnAddress) {
            (nickname, externalId, local, bad) =>
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
                } yield first.email
                    .contains(shared) && second.email.contains(shared) && first.playerId != second.playerId
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
