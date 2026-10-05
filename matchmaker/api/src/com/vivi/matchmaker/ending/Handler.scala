package com.vivi.matchmaker.ending

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.util.control.NonFatal
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.service.{Services, Settlement}

/** Settles the ends of matches: sees them archived, and tells engines of cancels — see `EndingService`.
  *
  * Handler string: `com.vivi.matchmaker.ending.Handler::handleRequest`, with an SQS event source mapping in front of it
  * draining the queue `MatchEndings` puts each ending on. A further function built from the API's jar, inside the VPC
  * beside it, because it reads and writes the database and calls the engines exactly as the API does — and with the
  * same services, so that the two cannot disagree about what a match owes.
  *
  * A message whose match still owes something is reported as a batch item failure, and the queue delivers it again once
  * its visibility timeout has passed: that is the retry, and after the queue's last one it goes to the dead-letter
  * queue, where it can be read and replayed. A message that cannot be read is failed the same way, so that it lands
  * there too rather than vanishing.
  */
class Handler extends RequestStreamHandler {

    override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
        val event = String(input.readAllBytes(), StandardCharsets.UTF_8)
        val log = (message: String) => Option(context).fold(System.err.println(message))(_.getLogger.log(message))
        // Stop starting on messages with time to spare: one engine call can take ten seconds. What is not reached
        // is failed, and so delivered again.
        val deadline =
            Option(context).map(c => Instant.now().plusMillis(c.getRemainingTimeInMillis.toLong).minus(Handler.margin))

        val failures = Handler.records(event).flatMap {
            case Handler.Record.Unreadable(messageId, why) =>
                log(s"could not read $messageId ($why); failing it to the dead-letter queue")
                Some(messageId)

            case Handler.Record.Understood(messageId, _) if deadline.exists(d => !Instant.now().isBefore(d)) =>
                log(s"no time left for $messageId; it will be delivered again")
                Some(messageId)

            case Handler.Record.Understood(messageId, ended) =>
                val matchId = s"match ${ended.matchId} of game ${ended.gameId}"
                try {
                    Handler.services.ending.settle(GameId(ended.gameId), MatchId(ended.matchId)).unsafeRunSync() match {
                        case Settlement.Settled =>
                            log(s"settled $matchId")
                            None
                        case Settlement.Owed(why) =>
                            log(s"$matchId is still owed something ($why); it will be delivered again")
                            Some(messageId)
                    }
                } catch {
                    case NonFatal(error) =>
                        log(s"settling $matchId failed: $error")
                        Some(messageId)
                }
        }

        output.write(Handler.response(failures).getBytes(StandardCharsets.UTF_8))
        output.flush()
    }
}

object Handler {

    /** The API's services, built the API's way: the same database, engine client and archive store. */
    lazy val services: Services[String] = com.vivi.matchmaker.api.Handler.services

    /** How long before the function's own timeout it stops starting on messages. */
    val margin: Duration = Duration.ofSeconds(30)

    /** One queue message, read or not. */
    enum Record {
        case Understood(id: String, ended: MatchEnded)
        case Unreadable(id: String, why: String)
    }

    /** The messages in an SQS event. One with no id is skipped: a batch item failure names a message by its id, so
      * there is nothing to say about one that has none, and SQS always sends one.
      */
    def records(event: String): Seq[Record] =
        ujson.read(event).objOpt.flatMap(_.get("Records")).flatMap(_.arrOpt).toSeq.flatten.flatMap { record =>
            for {
                obj <- record.objOpt
                messageId <- obj.get("messageId").flatMap(_.strOpt)
            } yield obj.get("body").flatMap(_.strOpt) match {
                case None => Record.Unreadable(messageId, "no body")
                case Some(body) =>
                    MatchEnded.parse(body) match {
                        case Left(why)   => Record.Unreadable(messageId, why)
                        case Right(read) => Record.Understood(messageId, read)
                    }
            }
        }

    /** The partial-batch response for an event source mapping with `ReportBatchItemFailures`. */
    def response(failures: Seq[String]): String =
        upickle.default.write(
          ujson.Obj("batchItemFailures" -> ujson.Arr.from(failures.map(id => ujson.Obj("itemIdentifier" -> id))))
        )

    /** Settles one match by hand, against whatever the environment points at — for a message from the dead-letter
      * queue, or an ending that was never queued: `mill matchmaker.api.runMain com.vivi.matchmaker.ending.Handler
      * <gameId> <matchId>`. Locally that is the local database, with `DB_*` as the API reads them.
      */
    def main(args: Array[String]): Unit = {
        args match {
            case Array(gameId, matchId) if gameId.toIntOption.isDefined =>
                println(services.ending.settle(GameId(gameId.toInt), MatchId(matchId)).unsafeRunSync())
                sys.exit(0)
            case _ =>
                System.err.println("usage: Handler <gameId> <matchId>")
                sys.exit(2)
        }
    }
}
