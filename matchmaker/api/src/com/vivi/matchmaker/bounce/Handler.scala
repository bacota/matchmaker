package com.vivi.matchmaker.bounce

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.vivi.matchmaker.model.EmailSuppression
import com.vivi.matchmaker.service.{DbConfig, Services, SuppressionService}

/** Records what SES says happened to the mail we sent: bounces, complaints and delivery delays.
  *
  * Handler string: `com.vivi.matchmaker.bounce.Handler::handleRequest`, with an SQS event source mapping in front of
  * it. The path an event takes is SES configuration set -> event destination -> SNS topic -> queue -> here. SNS is in
  * the middle because an SES v2 event destination cannot name a queue, not because anything wants a fan-out.
  *
  * A second function built from the same jar as the API, rather than a second handler in the mailer, and that split is
  * the whole shape of this. The mailer is deliberately outside the VPC — it reaches SES over a public endpoint, and
  * needs no NAT gateway and no interface endpoint to do it — and giving it a database would undo that. This one is
  * inside, where the database is, and has no business talking to SES at all.
  *
  * It knows nothing about players, matches or mail. It reads addresses out of a document and writes them down.
  */
class Handler extends RequestStreamHandler {

    // Once per container, as the API's handler does it: a Lambda serves one invocation at a time
    // and the same container serves many, so the pool outlives the request.
    private lazy val suppression: SuppressionService = Handler.suppression

    override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
        val event = String(input.readAllBytes(), StandardCharsets.UTF_8)
        val log = (message: String) =>
            Option(context) match {
                case Some(c) => c.getLogger.log(message)
                case None    => System.err.println(message)
            }

        val failures = Handler.records(event).flatMap {
            /* Failed on purpose, so it is redelivered and then reaches the dead-letter queue.
             *
             * Retrying will not make it parse -- that much of the old argument for dropping it was
             * true -- but the DLQ is not a retry mechanism here, it is where a lost suppression
             * becomes visible: a queue depth to alarm on, a terraform output naming it, and a
             * message that can be replayed once the reader is fixed. The thing being lost is not a
             * message, it is matchmaker going on mailing an address SES told us to stop mailing,
             * and for a complaint that is worse than a nuisance. Three wasted receives is a cheap
             * price for that being noticed. */
            case Handler.Record.Unreadable(messageId, why) =>
                log(s"could not read $messageId ($why); failing it to the dead-letter queue")
                Some(messageId)

            case Handler.Record.Understood(messageId, events) =>
                try {
                    if (events.isEmpty) log(s"nothing to record for $messageId")
                    else {
                        val counted = suppression.record(events).unsafeRunSync()
                        // "2 of 2" and "0 of 2" are different facts: the second is a redelivery,
                        // which the queue produces routinely and which advances nothing by design.
                        log(
                          s"recorded $counted of ${events.size} for $messageId: " +
                              events.map(e => s"${e.reason.code} ${e.email}").mkString(", ")
                        )
                    }
                    None
                } catch {
                    // This message alone, reported through the partial-batch response: the write
                    // failed -- a database that is not reachable, a constraint nothing anticipated
                    // -- and unlike an unreadable document it is worth retrying. Without the
                    // partial response the whole batch would come back, and the addresses already
                    // recorded would be claimed again -- harmless now that an event id is what
                    // advances the count, but still a write per address for nothing.
                    case NonFatal(error) =>
                        log(s"failed $messageId: ${error.getMessage}")
                        Some(messageId)
                }
        }

        output.write(Handler.response(failures).getBytes(StandardCharsets.UTF_8))
        output.flush()
    }
}

object Handler {

    /** One queue message, read or not.
      *
      * An ADT rather than a `Record` with an empty event list, because the two outcomes get opposite treatment: an
      * understood message is acknowledged whether or not it had anything to record, and an unreadable one is failed so
      * that it is redelivered and then lands in the dead-letter queue. Collapsing them — which this did — acknowledged
      * a bounce nobody could read, and the only trace was a log line.
      */
    enum Record {
        case Understood(id: String, events: Seq[EmailSuppression.Event])
        case Unreadable(id: String, why: String)

        /** The id `batchItemFailures` names a message by, whichever it turned out to be. */
        def messageId: String = this match {
            case Understood(id, _) => id
            case Unreadable(id, _) => id
        }
    }

    /** The messages in an SQS event, each read as far as it can be.
      *
      * A message with no id is skipped entirely and not reported: `batchItemFailures` names messages by id, so there is
      * nothing to say about one that has none, and SQS always sends it — a record without one is not a record this was
      * given.
      */
    def records(event: String): Seq[Record] =
        ujson.read(event).objOpt.flatMap(_.get("Records")).flatMap(_.arrOpt).toSeq.flatten.flatMap { record =>
            for {
                obj <- record.objOpt
                messageId <- obj.get("messageId").flatMap(_.strOpt)
            } yield obj.get("body").flatMap(_.strOpt) match {
                case None => Record.Unreadable(messageId, "no body")
                case Some(body) =>
                    notification(body) match {
                        case Left(why) => Record.Unreadable(messageId, why)
                        case Right(notification) =>
                            SesEvent.suppressions(notification) match {
                                case SesEvent.Reading.Understood(events) => Record.Understood(messageId, events)
                                case SesEvent.Reading.Unreadable(why)    => Record.Unreadable(messageId, why)
                            }
                    }
            }
        }

    /* The SES notification inside the body, through either shape SNS delivers.
     *
     * A plain SNS subscription wraps it: the body is an envelope whose `Message` is the notification
     * as a *string* of JSON, which has to be parsed a second time. With raw message delivery turned
     * on there is no envelope and the body is the notification itself. Both are accepted rather than
     * one being assumed, because which of them arrives is a checkbox on the subscription -- a
     * checkbox someone could reasonably change, and whose effect would otherwise be that every
     * bounce is silently unreadable.
     *
     * `Left` rather than `None`, so the reason travels with the failure into the log beside the
     * message id that will appear in the dead-letter queue. */
    private def notification(body: String): Either[String, ujson.Value] =
        try {
            val parsed = ujson.read(body)
            parsed.objOpt.flatMap(_.get("Message")).flatMap(_.strOpt) match {
                case Some(inner) => Right(ujson.read(inner))
                case None        => Right(parsed)
            }
        } catch {
            case NonFatal(error) => Left(s"not JSON: $error")
        }

    /** The partial-batch response Lambda expects when the event source mapping is configured with
      * `ReportBatchItemFailures`. An empty list means the whole batch is done.
      */
    def response(failures: Seq[String]): String =
        upickle.default.write(
          ujson.Obj("batchItemFailures" -> ujson.Arr.from(failures.map(id => ujson.Obj("itemIdentifier" -> id))))
        )

    /** Built once per container: a pool of its own, and the one service that uses it.
      *
      * `Services.poolResource` rather than the whole service graph, because this function records addresses and does
      * nothing else — the graph would have it construct an engine client and a queue notifier that nothing here ever
      * calls. The pool's finalizer is dropped for the same reason the API's is: the pool should live exactly as long as
      * the container, and there is no shutdown hook that would run it at a useful moment.
      */
    lazy val suppression: SuppressionService = {
        val pool = Services.poolResource(DbConfig.fromEnvironment(), poolSize).allocated.unsafeRunSync()._1
        new SuppressionService(pool)
    }

    private def poolSize: Int =
        sys.env.get("DB_POOL_SIZE").flatMap(_.toIntOption).getOrElse(Services.defaultPoolSize)
}
