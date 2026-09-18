package com.vivi.matchmaker.persistence

import java.time.Instant
import java.util.UUID
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}

/** The arithmetic in `email_suppression`: what one event does to a row, and when a row starts holding mail back.
  *
  * Not a property suite. Every assertion here is about a specific sequence of events — a complaint after a delay, a
  * third delay, an event after a release — and a generator would only obscure which sequence failed. The addresses are
  * generated, so cases cannot collide with each other or with a previous run's rows.
  *
  * The last test is the one that earns its keep over time: [[EmailSuppression.active]] and the `WHERE` clause in
  * `SuppressionRepo.activeFor` state the same rule twice, in Scala and in SQL, and nothing but this stops them from
  * drifting apart.
  */
class SuppressionRepoSpec extends FunSuite {
    TestMigration.ensure()

    private def address: String = s"bounce-${UUID.randomUUID()}@example.com"

    private def withRepo[A](use: SuppressionRepo => IO[A]): A =
        TestSession.resource.use(session => use(new SuppressionRepo(session))).unsafeRunSync()

    private def bounce(email: String, permanent: Boolean): EmailSuppression.Event =
        EmailSuppression.Event(email, SuppressionReason.Bounce, permanent, Some("smtp; 550 5.1.1 user unknown"))

    private def delay(email: String): EmailSuppression.Event =
        EmailSuppression.Event(email, SuppressionReason.Delay, false, None)

    private def complaint(email: String): EmailSuppression.Event =
        EmailSuppression.Event(email, SuppressionReason.Complaint, true, None)

    test("a permanent bounce suppresses on the first event") {
        val email = address
        val (row, active) = withRepo { repo =>
            for {
                _ <- repo.record(bounce(email, permanent = true))
                row <- repo.read(email)
                active <- repo.activeFor(Set(email))
            } yield (row, active)
        }
        assertEquals(row.map(_.reason), Some(SuppressionReason.Bounce))
        assertEquals(row.map(_.permanent), Some(true))
        assertEquals(row.map(_.occurrences), Some(1))
        assertEquals(row.flatMap(_.diagnostic), Some("smtp; 550 5.1.1 user unknown"))
        assertEquals(active, Set(email))
    }

    test("a complaint suppresses on the first event, and outranks whatever follows it") {
        val email = address
        val (row, active) = withRepo { repo =>
            for {
                _ <- repo.record(complaint(email))
                // A transient delay afterwards must not demote it: the screen says something
                // different for a complaint, and there is no button on it.
                _ <- repo.record(delay(email))
                row <- repo.read(email)
                active <- repo.activeFor(Set(email))
            } yield (row, active)
        }
        assertEquals(row.map(_.reason), Some(SuppressionReason.Complaint))
        assertEquals(row.map(_.permanent), Some(true))
        assertEquals(active, Set(email))
    }

    test("transient failures suppress at the threshold and not before") {
        val email = address
        val states = withRepo { repo =>
            (1 to EmailSuppression.transientThreshold).toList.traverseState(repo, email)
        }
        val expected = (1 to EmailSuppression.transientThreshold).map { attempt =>
            (attempt, attempt >= EmailSuppression.transientThreshold)
        }
        assertEquals(states, expected.toList)
    }

    test("a delivery delay leaves the row transient, so nothing is permanent until SES says so") {
        val email = address
        val row = withRepo(repo => repo.record(delay(email)) *> repo.read(email))
        assertEquals(row.map(_.permanent), Some(false))
        assertEquals(row.map(_.reason), Some(SuppressionReason.Delay))
    }

    test("releasing stops the suppression, and forgives the permanence that caused it") {
        val email = address
        val (released, afterRelease, afterNextDelay) = withRepo { repo =>
            for {
                _ <- repo.record(bounce(email, permanent = true))
                released <- repo.releaseFor(email)
                afterRelease <- repo.activeFor(Set(email))
                // One transient delay after a release must not re-suppress: the count starts over
                // at one, and the old permanence does not come back to decide it.
                _ <- repo.record(delay(email))
                row <- repo.read(email)
                afterNextDelay <- repo.activeFor(Set(email))
            } yield (released, afterRelease, (row.map(_.occurrences), row.map(_.permanent), afterNextDelay))
        }
        assert(released, "there was a row to release")
        assertEquals(afterRelease, Set.empty[String])
        assertEquals(afterNextDelay, (Some(1), Some(false), Set.empty[String]))
    }

    test("releasing an address nothing was held back from says so") {
        assertEquals(withRepo(_.releaseFor(address)), false)
        val email = address
        assertEquals(
          withRepo { repo =>
              repo.record(complaint(email)) *> repo.releaseFor(email) *> repo.releaseFor(email)
          },
          false,
          "a second release has nothing left to do"
        )
    }

    test("a second permanent bounce after a release suppresses again") {
        val email = address
        val active = withRepo { repo =>
            for {
                _ <- repo.record(bounce(email, permanent = true))
                _ <- repo.releaseFor(email)
                _ <- repo.record(bounce(email, permanent = true))
                active <- repo.activeFor(Set(email))
            } yield active
        }
        assertEquals(active, Set(email))
    }

    test("addresses are matched and answered in lower case") {
        val email = address
        val active = withRepo { repo =>
            repo.record(complaint(email.toUpperCase)) *> repo.activeFor(Set(email.toUpperCase, address))
        }
        assertEquals(active, Set(email))
    }

    test("asking about no addresses asks the database nothing") {
        assertEquals(withRepo(_.activeFor(Set.empty)), Set.empty[String])
        assertEquals(withRepo(_.activeFor(Set("", "   "))), Set.empty[String])
    }

    test("one query answers for a whole event's recipients, suppressed ones only") {
        val suppressed = address
        val delayed = address
        val fine = address
        val active = withRepo { repo =>
            for {
                _ <- repo.record(complaint(suppressed))
                _ <- repo.record(delay(delayed))
                active <- repo.activeFor(Set(suppressed, delayed, fine))
            } yield active
        }
        assertEquals(active, Set(suppressed))
    }

    /* The duplication check. `EmailSuppression.active` is the rule the screens and the model ask;
     * the WHERE clause in `activeFor` is the same rule asked of Postgres, which is the one the send
     * path uses. They are written twice because they are asked in two places, and this is what says
     * they still agree. */
    test("the model's rule and the query's rule agree, row for row") {
        val cases: List[(String, List[EmailSuppression.Event])] = List(
          address -> Nil,
          address -> List(delay(address)),
          address -> List(delay(address), delay(address)),
          address -> List(delay(address), delay(address), delay(address)),
          address -> List(bounce(address, permanent = false)),
          address -> List(bounce(address, permanent = true)),
          address -> List(complaint(address))
        )
        withRepo { repo =>
            cases.traverse_ { (email, events) =>
                for {
                    _ <- events.traverse_(event => repo.record(event.copy(email = email)))
                    row <- repo.read(email)
                    active <- repo.activeFor(Set(email))
                } yield {
                    val now = Instant.now()
                    assertEquals(
                      row.exists(_.active(now)),
                      active.contains(email.toLowerCase),
                      s"disagreed about $email after ${events.map(_.reason.code).mkString(", ")}"
                    )
                }
            }
        }
    }

    extension (attempts: List[Int])
        /** Records one delay per attempt, and reports the row's count and whether it is suppressed after each. */
        private def traverseState(repo: SuppressionRepo, email: String): IO[List[(Int, Boolean)]] =
            attempts.traverse { _ =>
                for {
                    _ <- repo.record(delay(email))
                    row <- repo.read(email)
                    active <- repo.activeFor(Set(email))
                } yield (row.map(_.occurrences).getOrElse(0), active.contains(email))
            }
}
