package com.vivi.matchmaker.mailer

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}

/** Drains the mail queue into SES.
  *
  * Handler string: `com.vivi.matchmaker.mailer.Handler::handleRequest`, with an SQS event source mapping in front of it
  * — which is the poller: Lambda long-polls the queue and hands this batches of messages.
  *
  * This function knows nothing about games, matches or players. It receives four strings and sends them, which is what
  * lets every later kind of notification reuse it untouched.
  */
class Handler extends RequestStreamHandler {

    private lazy val sender: MailSender = Handler.sender

    override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
        val event = String(input.readAllBytes(), StandardCharsets.UTF_8)
        val log = (message: String) =>
            Option(context) match {
                case Some(c) => c.getLogger.log(message)
                case None    => System.err.println(message)
            }

        val failures = Handler.records(event).flatMap { record =>
            try {
                sender.send(record.message)
                log(s"sent ${record.messageId} to ${record.message.recipient}")
                None
            } catch {
                // Reported as a failure of this message alone. Without the partial-batch response the
                // whole batch would be redelivered, so nine mails that were already sent would be sent
                // again to make one more attempt at the tenth.
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

    /** One queue message: the id the batch response names it by, and what it says. */
    case class Record(messageId: String, message: MailMessage)

    /** The messages in an SQS event.
      *
      * A record whose body is not a `MailMessage` is dropped rather than failed. Redelivering it cannot help — it will
      * not parse the next time either — so the only outcomes are to drop it now or to have the queue retry it to its
      * dead-letter queue three attempts later. Dropping is the quieter of the two, and the parse failure is logged
      * where the body can be read.
      */
    def records(event: String): Seq[Record] =
        ujson.read(event).objOpt.flatMap(_.get("Records")).flatMap(_.arrOpt).toSeq.flatten.flatMap { record =>
            for {
                obj <- record.objOpt
                messageId <- obj.get("messageId").flatMap(_.strOpt)
                body <- obj.get("body").flatMap(_.strOpt)
                message <-
                    try Some(upickle.default.read[MailMessage](body))
                    catch {
                        case NonFatal(error) =>
                            System.err.println(s"mailer: message $messageId is not a mail ($error): $body")
                            None
                    }
            } yield Record(messageId, message)
        }

    /** The partial-batch response Lambda expects when the event source mapping is configured with
      * `ReportBatchItemFailures`. An empty list means the whole batch is done.
      */
    def response(failures: Seq[String]): String =
        upickle.default.write(
          ujson.Obj("batchItemFailures" -> ujson.Arr.from(failures.map(id => ujson.Obj("itemIdentifier" -> id))))
        )

    /** Built once per container. The region is the one the function runs in; the credentials are the execution role's,
      * from the variables the runtime sets.
      *
      * `MAIL_CONFIG_SET` is the one variable this function is given, and the module comment in the terraform used to
      * say there were none. It names the SES configuration set every send is attributed to, which is how a bounce or a
      * complaint finds its way back to the queue the bounce consumer drains. Unset -- an environment with no bounce
      * handling, and the local server -- means the send omits it and behaves exactly as it did before.
      */
    lazy val sender: MailSender = {
        val region = sys.env.get("AWS_REGION").orElse(sys.env.get("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
        val configurationSet = sys.env.get("MAIL_CONFIG_SET").map(_.trim).filter(_.nonEmpty)
        new SesSender(region, new SigV4(AwsCredentials.fromEnvironment(), region), configurationSet)
    }
}
