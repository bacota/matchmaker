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
     * matches nothing else in a database every other suite is also registering players in.
     *
     * Which holds only for a prefix long enough to be that unique. The uniqueness is in the UUID at
     * the *end* of the generated string, so a short prefix is a slice of the random characters before
     * it, and those collide: a four-character one did, about once a run. Where a property asserts on
     * exactly the players it registered, the prefix it searches for is twenty characters or more. */

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

    /* Case insensitive: the search compares a normalized nickname -- lowercased, whitespace
     * collapsed, trimmed -- and V21 indexes that same expression. Nickname uniqueness is still case
     * sensitive, so "Ash" and "ash" are two players, and a search that folds case offers both. */
    property("search ignores the case of the prefix and of the nickname") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (suffix, externalId, callerId) =>
            val nickname = s"A$suffix"
            val result = for {
                registered <- registrationService.register(nickname, externalId)
                _ <- registrationService.register(callerId, callerId)
                // Twenty characters of the suffix rather than four, for the reason written out on the
                // folded search below: the shared test database holds well over a hundred thousand
                // players, four random alphanumerics have fourteen million combinations, and the
                // arithmetic of those two is a percent or so per case -- which is why this property
                // failed about once a run while passing whenever it was looked at on its own. Nothing
                // about case folding needs a short prefix; what it needs is the same prefix in three
                // spellings.
                same <- playerService.search(callerId, s"A${suffix.take(20)}")
                flipped <- playerService.search(callerId, s"a${suffix.take(20)}")
                shouted <- playerService.search(callerId, s"A${suffix.take(20)}".toUpperCase)
            } yield same.players.map(_.playerId) == List(registered.playerId) &&
                flipped.players.map(_.playerId) == List(registered.playerId) &&
                // Whatever the prefix was written as, the row says the nickname as it was registered.
                same.players.map(_.nickname) == List(nickname) &&
                shouted.players.map(_.playerId) == List(registered.playerId)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* Both players are findable, and each is spelled as they registered: this is the consequence of
     * folding case in a register where "Ash" and "ash" are two names. */
    property("a folded search finds every player whose name differs only in case") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (suffix, upperId, lowerId, callerId) =>
                val upper = s"A$suffix"
                val lower = s"a$suffix"
                val result = for {
                    first <- registrationService.register(upper, upperId)
                    second <- registrationService.register(lower, lowerId)
                    _ <- registrationService.register(callerId, callerId)
                    // A long slice of the suffix, not a short one: the shared test database holds a
                    // hundred thousand players, and a five-character prefix has already matched a third
                    // player registered by another property -- which falsified this one, since it asserts
                    // the result is exactly the two names it made. Twenty random characters cannot
                    // collide, and it is still a proper prefix of both, which is what this is about.
                    found <- playerService.search(callerId, s"a${suffix.take(20)}")
                } yield found.players.map(_.playerId).toSet == Set(first.playerId, second.playerId) &&
                    found.players.map(_.nickname).toSet == Set(upper, lower)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* Whitespace is normalized on both sides, so how a name was spaced is not something a searcher
     * has to guess: any run of it is one space, and the ends do not count. Every case here is a
     * nickname somebody could register -- nothing trims a nickname on the way in. */
    property("search treats any run of whitespace as a single space") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (base, externalId, callerId) =>
            val spaced = s"$base \t\n  tail"
            val result = for {
                registered <- registrationService.register(spaced, externalId)
                _ <- registrationService.register(callerId, callerId)
                single <- playerService.search(callerId, s"$base tail")
                tabbed <- playerService.search(callerId, s"$base\ttail")
                many <- playerService.search(callerId, s"$base     tail")
            } yield single.players.map(_.playerId) == List(registered.playerId) &&
                tabbed.players.map(_.playerId) == List(registered.playerId) &&
                many.players.map(_.playerId) == List(registered.playerId)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* And the ends are trimmed, on the stored name as well as on the prefix: " bob" is a nickname
     * somebody has, and "bob" is how anybody would look for them. */
    property("search ignores whitespace at the ends of the nickname") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (base, externalId, callerId) =>
            val padded = s"  $base  "
            val result = for {
                registered <- registrationService.register(padded, externalId)
                _ <- registrationService.register(callerId, callerId)
                found <- playerService.search(callerId, base.take(8))
            } yield found.players.map(_.playerId) == List(registered.playerId) &&
                // Said back as it is stored, padding and all: normalizing is how it is found, not how
                // it is spelled.
                found.players.map(_.nickname) == List(padded)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* The prefix is text, not a pattern. The search is a LIKE underneath -- which is what lets it use
     * the index of V20 -- so every character LIKE reads as a wildcard has to be escaped on the way
     * in: unescaped, the `%` and the `_` below would match the other player too, and the searcher has
     * no way to say they meant the character. */
    property("a wildcard character in the prefix matches itself") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (base, percentId, underscoreId, otherId, callerId) =>
                val percent = s"$base%z"
                val underscore = s"${base}_z"
                val other = s"${base}xz"
                val result = for {
                    withPercent <- registrationService.register(percent, percentId)
                    withUnderscore <- registrationService.register(underscore, underscoreId)
                    _ <- registrationService.register(other, otherId)
                    _ <- registrationService.register(callerId, callerId)
                    forPercent <- playerService.search(callerId, s"$base%")
                    forUnderscore <- playerService.search(callerId, s"${base}_")
                    // And the plain prefix finds all three, so the two above are narrower than it
                    // rather than simply broken.
                    forBase <- playerService.search(callerId, base)
                } yield forPercent.players.map(_.playerId) == List(withPercent.playerId) &&
                    forUnderscore.players.map(_.playerId) == List(withUnderscore.playerId) &&
                    forBase.players.length == 3 && !forBase.more
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* A backslash is LIKE's escape character, so it is the one that has to survive being escaped
     * itself -- and a nickname may contain one. */
    property("a backslash in the prefix matches itself") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (base, slashId, callerId) =>
            val withSlash = s"$base\\z"
            val result = for {
                registered <- registrationService.register(withSlash, slashId)
                _ <- registrationService.register(callerId, callerId)
                found <- playerService.search(callerId, s"$base\\")
            } yield found.players.map(_.playerId) == List(registered.playerId)
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
