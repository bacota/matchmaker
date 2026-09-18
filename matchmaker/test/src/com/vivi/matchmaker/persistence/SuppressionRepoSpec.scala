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

    /* A fresh id per event, which is what a *different* event has. The interesting case -- the same
     * id twice, which is what a redelivery is -- is asserted on its own below, because it is the one
     * the transient threshold's correctness rests on. */
    private def eventId: String = UUID.randomUUID().toString

    private def bounce(email: String, permanent: Boolean): EmailSuppression.Event =
        EmailSuppression.Event(
          eventId,
          email,
          SuppressionReason.Bounce,
          permanent,
          Some("smtp; 550 5.1.1 user unknown")
        )

    private def delay(email: String): EmailSuppression.Event =
        EmailSuppression.Event(eventId, email, SuppressionReason.Delay, false, None)

    private def complaint(email: String): EmailSuppression.Event =
        EmailSuppression.Event(eventId, email, SuppressionReason.Complaint, true, None)

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

    /* The fault this exists to prevent, stated as the bug it was.
     *
     * SQS is at-least-once by design, so a notification arrives more than once as a matter of course
     * -- a visibility timeout that expires mid-write, a function that times out, a retry after a
     * partial-batch failure. With a threshold of three, one duplicate would suppress an address
     * after two genuine failures, and the player it silenced would be perfectly reachable. */
    test("a redelivered event changes nothing at all") {
        val email = address
        val (first, second, row) = withRepo { repo =>
            val event = delay(email)
            for {
                first <- repo.record(event)
                second <- repo.record(event)
                row <- repo.read(email)
            } yield (first, second, row)
        }
        assert(first, "the first receive counted")
        assert(!second, "the second receive was already known")
        assertEquals(row.map(_.occurrences), Some(1))
    }

    test("two real delays and a duplicate do not reach a threshold of three") {
        val email = address
        val (counted, active) = withRepo { repo =>
            val first = delay(email)
            val second = delay(email)
            for {
                a <- repo.record(first)
                b <- repo.record(second)
                // The same notification again, which is the shape of every redelivery.
                c <- repo.record(first)
                d <- repo.record(second)
                active <- repo.activeFor(Set(email))
                row <- repo.read(email)
            } yield (Seq(a, b, c, d), (active, row.map(_.occurrences)))
        }
        assertEquals(counted, Seq(true, true, false, false))
        assertEquals(active, (Set.empty[String], Some(2)), "two failures, and not suppressed")
    }

    // And the other direction: distinct events about the same address still count, so the threshold
    // is not defeated by deduplication.
    test("three distinct events still suppress") {
        val email = address
        val active = withRepo { repo =>
            (1 to 3).toList.traverse_(_ => repo.record(delay(email))) *> repo.activeFor(Set(email))
        }
        assertEquals(active, Set(email))
    }

    /* A redelivery that arrives while the first receive is still being written must not slip past
     * the claim. Both statements are in one transaction and the claim is a primary-key insert, so
     * the second attempt either sees the row or blocks until the first commits and then sees it. */
    test("two concurrent receives of one event count it once") {
        val email = address
        val event = delay(email)
        val (results, row) = TestSession.resource
            .both(TestSession.resource)
            .use { (one, two) =>
                for {
                    results <- List(new SuppressionRepo(one), new SuppressionRepo(two)).parTraverse(_.record(event))
                    row <- new SuppressionRepo(one).read(email)
                } yield (results, row)
            }
            .unsafeRunSync()
        assertEquals(results.count(identity), 1, "exactly one of the two counted")
        assertEquals(row.map(_.occurrences), Some(1))
    }

    test("a delivery delay leaves the row transient, so nothing is permanent until SES says so") {
        val email = address
        val row = withRepo(repo => repo.record(delay(email)) *> repo.read(email))
        assertEquals(row.map(_.permanent), Some(false))
        assertEquals(row.map(_.reason), Some(SuppressionReason.Delay))
    }

    test("releasing a bounce lifts it") {
        val email = address
        val (outcome, active) = withRepo { repo =>
            for {
                _ <- repo.record(bounce(email, permanent = true))
                outcome <- repo.releaseFor(email)
                active <- repo.activeFor(Set(email))
            } yield (outcome, active)
        }
        assertEquals(outcome, SuppressionRepo.Release.Released)
        assertEquals(active, Set.empty[String])
    }

    /* The refusal is the repo's, not only the service's, and it is taken under the row's lock.
     *
     * Asserted here because the safety used to live in a `reason <> 'complaint'` WHERE clause that
     * nothing tested and the service could not see: deleting it would have turned a button into an
     * undo for a spam report, silently. */
    test("releasing forgives the permanence that caused the suppression") {
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
        assertEquals(released, SuppressionRepo.Release.Released)
        assertEquals(afterRelease, Set.empty[String])
        assertEquals(afterNextDelay, (Some(1), Some(false), Set.empty[String]))
    }

    test("releasing a complaint is refused, and leaves it suppressing") {
        val email = address
        val (outcome, active, row) = withRepo { repo =>
            for {
                _ <- repo.record(complaint(email))
                outcome <- repo.releaseFor(email)
                active <- repo.activeFor(Set(email))
                row <- repo.read(email)
            } yield (outcome, active, row)
        }
        assertEquals(outcome, SuppressionRepo.Release.RefusedComplaint)
        assertEquals(active, Set(email))
        assertEquals(row.flatMap(_.releasedAt), None, "the row was not released")
    }

    test("releasing what is not suppressed says so, and says it differently") {
        assertEquals(withRepo(_.releaseFor(address)), SuppressionRepo.Release.NotSuppressed)
        val email = address
        assertEquals(
          withRepo(repo =>
              repo.record(bounce(email, permanent = true)) *> repo.releaseFor(email) *> repo.releaseFor(email)
          ),
          SuppressionRepo.Release.NotSuppressed,
          "a second release has nothing left to do"
        )
    }

    /* The race the lock exists for: a player presses "try again" while the consumer records a
     * complaint about the same address.
     *
     * Either order is a correct outcome -- the complaint may land before the release is decided, or
     * after it commits -- but one combination must never appear: a complaint on the row and
     * released_at set, which is mail flowing again to an address that just reported us as spam. Run
     * repeatedly because the interleaving is the point; a single pass proves little. */
    test("a complaint recorded during a release is never released by it") {
        (1 to 12).foreach { _ =>
            val email = address
            val row = TestSession.resource
                .both(TestSession.resource)
                .use { (one, two) =>
                    val releasing = new SuppressionRepo(one)
                    val recording = new SuppressionRepo(two)
                    for {
                        // A bounce first, so there is something releasable for the release to find.
                        _ <- releasing.record(bounce(email, permanent = true))
                        _ <- (releasing.releaseFor(email), recording.record(complaint(email))).parTupled
                        row <- releasing.read(email)
                    } yield row
                }
                .unsafeRunSync()

            assert(row.isDefined)
            row.foreach { held =>
                assert(
                  !(held.reason == SuppressionReason.Complaint && held.releasedAt.isDefined),
                  s"a complaint was left released: $held"
                )
                // And whichever order it happened in, the address is suppressed: either the
                // complaint won and suppresses on its own, or the release won and the complaint that
                // followed re-suppressed it.
                assert(held.active(Instant.now()), s"mail is flowing to a complained address: $held")
            }
        }
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
