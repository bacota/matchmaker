package com.vivi.matchmaker.notify

import cats.effect.IO
import java.net.URI
import upickle.default.write
import com.vivi.matchmaker.util.{AwsCredentials, SigV4}

/** Puts a mail on the queue the mailer function drains.
  *
  * Deliberately not a durable handoff: a `SendMessage` that fails raises, and the one caller
  * treats that as survivable. What this buys over sending the mail inline is that the API's
  * request is not waiting on SES, and that delivery gets the queue's retries and its dead-letter
  * queue rather than one attempt on the request path.
  *
  * The JSON protocol (`AmazonSQS.SendMessage`) rather than the older query protocol: one JSON
  * body, the same shape as every other modern AWS call, and nothing to form-encode.
  */
class SqsNotifier(queueUrl: String, sigV4: SigV4) extends Notifier {

  /* The service endpoint is the queue's own origin -- scheme and host, with the account and queue
   * name dropped -- so that a VPC endpoint's DNS name is used when the queue url carries one, and
   * the public endpoint when it does not. The queue itself is named in the body. */
  private val endpoint: String = {
    val uri = URI.create(queueUrl)
    s"${uri.getScheme}://${uri.getHost}/"
  }

  def enqueue(message: MailMessage): IO[Unit] =
    IO.blocking {
      sigV4.post(
        url = endpoint,
        body = write(ujson.Obj("QueueUrl" -> queueUrl, "MessageBody" -> write(message))),
        service = "sqs",
        headers = Map(
          "content-type" -> "application/x-amz-json-1.0",
          "x-amz-target" -> "AmazonSQS.SendMessage"
        )
      )
    }.void
}

object SqsNotifier {

  /** The notifier a deployment gets, or `Notifier.disabled` when the environment does not name a
    * queue.
    *
    * Disabled rather than failing, because an environment without a queue is a working
    * environment: the local server has none, and neither did any deployment before notifications
    * existed. A missing queue means no mail, which is visible and harmless; a constructor that
    * threw would mean no API at all.
    */
  def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): Notifier =
    env("MAIL_QUEUE_URL").map(_.trim).filter(_.nonEmpty) match {
      case None => Notifier.disabled
      case Some(queueUrl) =>
        val region = env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
        new SqsNotifier(queueUrl, new SigV4(AwsCredentials.fromEnvironment(env), region))
    }
}
