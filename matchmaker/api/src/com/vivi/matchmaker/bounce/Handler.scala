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

        val failures = Handler.records(event).flatMap { record =>
            try {
                if (record.events.isEmpty) log(s"nothing to record for ${record.messageId}")
                else {
                    suppression.record(record.events).unsafeRunSync()
                    log(
                      s"recorded ${record.messageId}: " +
                          record.events.map(e => s"${e.reason.code} ${e.email}").mkString(", ")
                    )
                }
                None
            } catch {
                // This message alone, reported through the partial-batch response: the write failed
                // -- a database that is not reachable, a constraint nothing anticipated -- and it is
                // worth retrying, which is the difference between this and a document that will not
                // parse. Without the partial response the whole batch would come back, so the
                // addresses already recorded would be recorded twice and counted twice.
                case NonFatal(error) =>
                    log(s"failed ${record.messageId}: ${error.getMessage}")
                    Some(record.messageId)
            }
        }

        output.write(Handler.response(failures).getBytes(StandardCharsets.UTF_8))
        output.flush()
    }
}

object Handler {

    /** One queue message: the id the batch response names it by, and what SES said in it. */
    case class Record(messageId: String, events: Seq[EmailSuppression.Event])

    /** The suppressions in an SQS event, one entry per message.
      *
      * A message that cannot be read yields an empty `Record` rather than being dropped from the list, so that it is
      * still deleted from the queue: a document we cannot parse is one we will not parse on the next receive either,
      * and the only alternative to accepting it is three more receives and a message in the dead-letter queue. The body
      * is logged where it can be read.
      */
    def records(event: String): Seq[Record] =
        ujson.read(event).objOpt.flatMap(_.get("Records")).flatMap(_.arrOpt).toSeq.flatten.flatMap { record =>
            for {
                obj <- record.objOpt
                messageId <- obj.get("messageId").flatMap(_.strOpt)
                body <- obj.get("body").flatMap(_.strOpt)
            } yield Record(messageId, notification(messageId, body).map(SesEvent.suppressions).getOrElse(Nil))
        }

    /* The SES notification inside the body, through either shape SNS delivers.
     *
     * A plain SNS subscription wraps it: the body is an envelope whose `Message` is the notification
     * as a *string* of JSON, which has to be parsed a second time. With raw message delivery turned
     * on there is no envelope and the body is the notification itself. Both are accepted rather than
     * one being assumed, because which of them arrives is a checkbox on the subscription -- a
     * checkbox someone could reasonably change, and whose effect would otherwise be that every
     * bounce is silently unreadable. */
    private def notification(messageId: String, body: String): Option[ujson.Value] =
        try {
            val parsed = ujson.read(body)
            parsed.objOpt.flatMap(_.get("Message")).flatMap(_.strOpt) match {
                case Some(inner) => Some(ujson.read(inner))
                case None        => Some(parsed)
            }
        } catch {
            case NonFatal(error) =>
                System.err.println(s"bounce: message $messageId is not an SES notification ($error): $body")
                None
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
