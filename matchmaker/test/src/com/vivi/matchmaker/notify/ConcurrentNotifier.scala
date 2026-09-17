package com.vivi.matchmaker.notify

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import cats.effect.IO

/** A `RecordingNotifier` that answers only once `expected` mails are being sent at the same time.
  *
  * How a test asserts that the mails of one event overlap rather than queue up behind one another: each enqueue records
  * its mail as usual and then waits at a barrier, so `arrived` reaches `expected` only if that many sends were in
  * flight together. Sent one at a time, the first waits alone, times out, and the rest are never attempted — so a
  * sequential dispatch leaves `arrived` at zero after `timeout` rather than hanging the suite.
  *
  * `IO.blocking`, because a thread parked on a barrier is exactly what that is for. On the compute pool it would be
  * holding one of the few threads the runtime needs in order to run the sibling fibers at all, and the barrier could
  * never fill.
  *
  * Disarmed until `arm`, because the fixtures a test like this needs send mail while they are being built — accepting a
  * challenge writes to the challenger — and a barrier that those had to fill would be one every fixture stalled on.
  */
class ConcurrentNotifier(expected: Int, timeout: FiniteDuration) extends RecordingNotifier {
    private val barrier = new CyclicBarrier(expected)
    private val passed = new AtomicInteger(0)
    @volatile private var armed = false

    /** From here on, a send waits for its siblings. Called once the fixture is built and its own mail is spent. */
    def arm(): Unit = armed = true

    override def enqueue(message: MailMessage): IO[Unit] =
        if (!armed) super.enqueue(message)
        else
            super.enqueue(message) *> IO.blocking {
                barrier.await(timeout.toMillis, TimeUnit.MILLISECONDS)
                passed.incrementAndGet()
            }.void

    /** How many sends met the others at the barrier: `expected` when they overlapped, zero when they did not. */
    def arrived: Int = passed.get()
}
