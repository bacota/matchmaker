package com.vivi.matchmaker.sweep

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.service.{Services, SweepReport}

/** The archive sweep, on a schedule: see `SweepService`.
  *
  * Handler string: `com.vivi.matchmaker.sweep.Handler::handleRequest`, invoked by an EventBridge schedule whose event
  * says nothing this reads. A third function built from the API's jar, inside the VPC beside it, because it reads and
  * writes the database and calls the engines exactly as the API does — and with the same services, so the two cannot
  * disagree about what a match owes.
  */
class Handler extends RequestStreamHandler {

    override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
        input.readAllBytes()
        val report = Handler.services.sweep.run().unsafeRunSync()
        val summary = Handler.describe(report)
        Option(context).fold(System.err.println(summary))(_.getLogger.log(summary))
        output.write(summary.getBytes(StandardCharsets.UTF_8))
        output.flush()
    }
}

object Handler {

    /** The API's services, built the API's way: the same database, engine client and archive store. */
    lazy val services: Services[String] = com.vivi.matchmaker.api.Handler.services

    def describe(report: SweepReport): String =
        ujson.write(
          ujson.Obj(
            "prompted" -> report.prompted,
            "stillUnarchived" -> report.stillUnarchived.map(_.value),
            "released" -> report.released,
            "stillUnreleased" -> report.stillUnreleased.map(_.value)
          )
        )

    /** One run by hand, against whatever the environment points at: `mill matchmaker.api.runMain
      * com.vivi.matchmaker.sweep.Handler`. Locally that is the local database, with `DB_*` as the API reads them.
      */
    def main(args: Array[String]): Unit = {
        println(describe(services.sweep.run().unsafeRunSync()))
        sys.exit(0)
    }
}
