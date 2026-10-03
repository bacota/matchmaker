package com.vivi.matchmaker.ui

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite

/** Asking for a list over and over — `Coalescer`, through which the store makes every fetch.
  *
  * What it must do is two things at once: send far fewer requests than it is asked for, and never let an ask be
  * answered by a request that left before it. The second is the one a simpler guard — "skip it, one is already out" —
  * gets wrong, and the one a player notices: the list after a click that does not show the click.
  */
class CoalescerSpec extends FunSuite {

    /** A clock that moves only when told to, and the requests made, each finished by hand. */
    private class Rig(spacing: Double = 1000) {
        var time = 0.0
        private var timers = List.empty[(Double, () => Unit)]
        val coalescer = Coalescer[String](spacing, () => time, (ms, run) => timers = timers :+ (time + ms, run))
        val started = scala.collection.mutable.ArrayBuffer.empty[(String, Promise[Unit])]

        def ask(key: String, name: String): Future[Unit] =
            coalescer(key) { () =>
                val p = Promise[Unit]()
                started += name -> p
                p.future
            }

        def names: List[String] = started.map(_._1).toList

        /** Finishes request `i`, and lets what follows from that run. */
        def finish(i: Int): Future[Unit] = { started(i)._2.trySuccess(()); settle() }

        def advance(ms: Double): Future[Unit] = {
            time += ms
            val (due, rest) = timers.partition(_._1 <= time)
            timers = rest
            due.foreach(_._2())
            settle()
        }

        /** Completions run on the event loop; this waits a turn of it so that what they start has started. */
        def settle(): Future[Unit] = Future(()).flatMap(_ => Future(()))
    }

    test("the first ask goes at once, in the caller's own call") {
        val r = Rig()
        r.ask("games", "a")
        assertEquals(r.names, List("a"))
    }

    test("asks made while a request is out share the one request after it, which runs the latest") {
        val r = Rig()
        val first = r.ask("challenges", "a")
        val second = r.ask("challenges", "b")
        val third = r.ask("challenges", "c")
        assertEquals(r.names, List("a"))
        for {
            _ <- r.finish(0)
            _ = assert(first.isCompleted)
            // Not answered by "a": that left before they were asked.
            _ = assert(!second.isCompleted && !third.isCompleted)
            _ <- r.advance(1000)
            _ = assertEquals(r.names, List("a", "c"))
            _ <- r.finish(1)
        } yield assert(second.isCompleted && third.isCompleted)
    }

    test("a loop asking again as each answer lands sends one request a second, not one a turn") {
        val r = Rig()
        // The fault this exists for: every answer remounts something that asks again at once.
        def loop(): Unit = r.ask("challenges", "again").foreach(_ => loop())
        loop()
        def tick(n: Int): Future[Unit] =
            if (n == 0) Future.unit
            else
                r.finish(r.started.size - 1)
                    .flatMap(_ => r.advance(100))
                    .flatMap(_ => r.advance(0))
                    .flatMap(_ => tick(n - 1))
        // Five seconds of it, in tenths: fifty answers' worth of asking.
        tick(50).map(_ => assert(r.started.size <= 6, s"${r.started.size} requests in five seconds"))
    }

    test("different questions do not wait for each other") {
        val r = Rig()
        r.ask("challenges:1", "one")
        r.ask("challenges:2", "two")
        assertEquals(r.names, List("one", "two"))
    }

    test("a request that fails, or a start that throws, does not wedge the question") {
        val r = Rig()
        val failing = r.coalescer("games")(() => throw new RuntimeException("no network"))
        for {
            _ <- failing
            _ <- r.advance(1000)
            _ = r.ask("games", "after")
        } yield assertEquals(r.names, List("after"))
    }

    test("after a quiet spell, an ask goes at once again") {
        val r = Rig()
        r.ask("games", "a")
        for {
            _ <- r.finish(0)
            _ <- r.advance(5000)
            _ = r.ask("games", "b")
        } yield assertEquals(r.names, List("a", "b"))
    }
}
