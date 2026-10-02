package com.vivi.matchmaker

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.{ExecutionContext, Future}

/** Lets a test that fails something on purpose keep the failure's report off the console while it passes.
  *
  * A test tagged [[Quiet]] runs with stderr captured. If it passes, what it wrote is dropped: the log line or stack
  * trace was the expected consequence of what the test did, and printing it makes a green run look broken. If it fails,
  * everything it wrote is printed after all, since that is when the report is worth reading.
  *
  * A tag rather than a blanket, so that only the tests that cause a failure deliberately are quiet, and anything else
  * that writes a stack trace during a passing run is still seen.
  */
trait QuietTests extends munit.FunSuite {

    val Quiet: munit.Tag = new munit.Tag("Quiet")

    override def munitTestTransforms: List[TestTransform] =
        super.munitTestTransforms :+ new TestTransform(
          "Quiet",
          test =>
              if (test.tags.contains(Quiet))
                  test.withBody(() => QuietTests.capturing(test.body())(using munitExecutionContext))
              else test
        )
}

object QuietTests {

    /** Runs `body` with stderr captured, putting stderr back once its future settles, and replaying what was captured
      * if it failed — including by throwing before it had a future to return.
      *
      * System-wide rather than per thread, because what is being kept quiet is often written from another one. That is
      * safe here because a JVM runs its suites one at a time.
      */
    def capturing(body: => Future[Any])(using ExecutionContext): Future[Any] = {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true))

        def restore(failed: Boolean): Unit = {
            System.err.flush()
            System.setErr(original)
            if (failed) original.print(captured.toString)
        }

        val started =
            try body
            catch {
                case error: Throwable =>
                    restore(failed = true)
                    throw error
            }
        started.transform { outcome =>
            restore(failed = outcome.isFailure)
            outcome
        }
    }
}
