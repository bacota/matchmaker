package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import munit.FunSuite
import skunk.{Query, Void}
import skunk.codec.all.{bool, int4}
import skunk.implicits._
import com.vivi.matchmaker.persistence.TestSession

/** A pooled connection the server has closed while it sat in the pool -- as RDS or the network does to one in a Lambda
  * container frozen between requests -- is replaced as it is borrowed, rather than handed to the caller to fail on.
  *
  * The server closes it here by `pg_terminate_backend`, from a session of the shared pool, waiting until the backend
  * has gone.
  */
class DbSessionSpec extends FunSuite {

    private val backendPid: Query[Void, Int] = sql"SELECT pg_backend_pid()".query(int4)

    private val terminate: Query[Int, Boolean] = sql"SELECT pg_terminate_backend($int4, 5000)".query(bool)

    /* The backend each of `n` sessions of `pool` holds, all borrowed at once so that each is a different one. */
    private def backends(pool: SessionPool, n: Int): IO[List[Int]] =
        List.fill(n)(pool).sequence.use(_.traverse(_.unique(backendPid)))

    private def terminated(pids: List[Int]): IO[Unit] =
        TestSession.resource.use(s => pids.traverse_(pid => s.unique(terminate)(pid).void))

    test("a connection the server closed is replaced by a working one as it is borrowed") {
        val pool = TestServices.poolOf(1)
        val run = for {
            before <- backends(pool = pool, n = 1)
            _ <- terminated(before)
            after <- backends(pool = pool, n = 1)
        } yield assert(after.forall(!before.contains(_)), s"borrowed backend $after again after closing $before")
        run.unsafeRunSync()
    }

    test("every connection of a pool closed at once is replaced, one borrow after another") {
        val pool = TestServices.poolOf(2)
        val run = for {
            before <- backends(pool = pool, n = 2)
            _ <- terminated(before)
            first <- backends(pool = pool, n = 1)
            second <- backends(pool = pool, n = 1)
        } yield {
            val after = first ++ second
            assert(after.forall(!before.contains(_)), s"borrowed backends $after after closing $before")
        }
        run.unsafeRunSync()
    }
}
