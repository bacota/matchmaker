package com.vivi.matchmaker.ending

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.util.control.NonFatal
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.model.{GameId, MatchId}
import com.vivi.matchmaker.service.{Services, Settlement}

/** Settles the ends of matches: sees them archived, tells engines of cancels, and puts leaderboards in order — see
  * `EndingService`. And puts a leaderboard in order when its game's ratings have moved with no match ending.
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

        // The batch at once, up to `parallelism` at a time: each message is one match, and the engine is what any
        // of them waits on (tournament-plan D5). Settling an ending touches only its own match's rows, so endings
        // run beside each other as safely as the matches a round start queues.
        val failures = IO
            .parTraverseN(Handler.parallelism)(Handler.records(event).toList)(Handler.process(_, deadline, log))
            .map(_.flatten)
            .unsafeRunSync()

        output.write(Handler.response(failures).getBytes(StandardCharsets.UTF_8))
        output.flush()
    }
}

object Handler {

    /** The API's services, built the API's way: the same database, engine client and archive store. */
    lazy val services: Services[String] = com.vivi.matchmaker.api.Handler.services

    /** How long before the function's own timeout it stops starting on messages. */
    val margin: Duration = Duration.ofSeconds(30)

    /** How many of a batch's messages are worked on at once: a bound on engine calls in flight from one copy. */
    val parallelism: Int = 8

    /** How long a match that could not be made waits before it is tried again: half an hour, rather than the queue's
      * hour and a half, since a round is waiting on it. SQS caps a send's delay at fifteen minutes, so the message's
      * own visibility is moved instead.
      */
    val makeAgainAfterSeconds: Int = 1800

    /** One queue message, read or not. `receipt` is what changing its visibility names it by. */
    enum Record {
        case Understood(id: String, message: MatchEnded.Message, receipt: Option[String] = None)
        case Unreadable(id: String, why: String)
    }

    /** One message's work, answering its id if it is to be delivered again. */
    def process(record: Record, deadline: Option[Instant], log: String => Unit): IO[Option[String]] =
        record match {
            case Record.Unreadable(messageId, why) =>
                IO(log(s"could not read $messageId ($why); failing it to the dead-letter queue")).as(Some(messageId))

            case Record.Understood(messageId, _, _) if deadline.exists(d => !Instant.now().isBefore(d)) =>
                IO(log(s"no time left for $messageId; it will be delivered again")).as(Some(messageId))

            case Record.Understood(messageId, message, receipt) =>
                val (what, work) = message match {
                    case ended: MatchEnded =>
                        (
                          s"match ${ended.matchId} of game ${ended.gameId}",
                          services.ending.settle(GameId(ended.gameId), MatchId(ended.matchId))
                        )
                    case changed: RatingsChanged =>
                        (s"the leaderboard of game ${changed.gameId}", services.ending.rank(GameId(changed.gameId)))
                    case due: MatchDue =>
                        (
                          s"match ${due.matchNo} of pool ${due.fixtureId} of tournament ${due.tournamentId}",
                          services.tournamentPlay.createMatch(due)
                        )
                    case check: MatchCheck =>
                        (
                          s"the check of match ${check.matchId}",
                          services.tournamentPlay.check(GameId(check.gameId), MatchId(check.matchId))
                        )
                }
                val failed: IO[Option[String]] = message match {
                    // A match a round is waiting on is tried again sooner than the queue would.
                    case _: MatchDue => receipt.traverse_(makeAgainSooner(_, log)).as(Some(messageId))
                    case _           => IO.pure(Some(messageId))
                }
                work.attempt.flatMap {
                    case Right(Settlement.Settled) => IO(log(s"settled $what")).as(None)
                    case Right(Settlement.Owed(why)) =>
                        IO(log(s"$what is still owed something ($why); it will be delivered again")) *> failed
                    case Left(error) => IO(log(s"settling $what failed: $error")) *> failed
                }
        }

    /* The queue the listener drains, as the environment names it, and a client for it. */
    private lazy val queueUrl: Option[String] =
        Option(System.getenv("MATCH_ENDED_QUEUE_URL")).map(_.trim).filter(_.nonEmpty)

    private lazy val sqs = com.vivi.matchmaker.notify.SqsNotifier.lazily(() =>
        com.vivi.matchmaker.notify.SqsNotifier.client(
          Option(System.getenv("AWS_REGION")).orElse(Option(System.getenv("AWS_DEFAULT_REGION"))).getOrElse("us-east-1")
        )
    )

    /* Moves a failed message's next delivery to half an hour from now. If this fails too, the message is delivered
     * again on the queue's own timeout, which is slower but still right. */
    private def makeAgainSooner(receipt: String, log: String => Unit): IO[Unit] =
        queueUrl.traverse_ { url =>
            IO.blocking(
              sqs()
                  .changeMessageVisibility(
                    software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
                        .builder()
                        .queueUrl(url)
                        .receiptHandle(receipt)
                        .visibilityTimeout(makeAgainAfterSeconds)
                        .build()
                  )
            ).void
                .handleError(e => log(s"could not bring the retry forward: $e"))
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
                        case Left(why) => Record.Unreadable(messageId, why)
                        case Right(read) =>
                            Record.Understood(messageId, read, obj.get("receiptHandle").flatMap(_.strOpt))
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
