package com.vivi.matchmaker.notify

import cats.effect.IO
import upickle.default.write
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/** Puts a mail on the queue the mailer function drains.
  *
  * Deliberately not a durable handoff: a `SendMessage` that fails raises, and the one caller treats
  * that as survivable — while logging it, because a notification nobody is owed and a notification
  * nobody could be sent look identical from anywhere else. What this buys over sending the mail
  * inline is that the player's request is not waiting on SES, and that delivery gets the queue's
  * retries and its dead-letter queue rather than one attempt on the request path.
  *
  * The SDK client, where everything else in this repository signs its own requests with the SDK's
  * signer and the JDK's HTTP client. That trade was made to keep the Lambda jar small, and it is
  * the wrong trade here for one reason: SnapStart. Signing by hand means reading the execution
  * role's credentials from `System.getenv`, Java fixes that map at JVM start, and under SnapStart
  * JVM start is publish time — so a restored function signs with credentials that were never there
  * or have long expired, and SQS refuses it. The client's own credential provider is built for
  * this; a call to `System.getenv` cannot be.
  */
class SqsNotifier(queueUrl: String, client: () => SqsClient) extends Notifier {

  def enqueue(message: MailMessage): IO[Unit] =
    IO.blocking {
      client().sendMessage(
        SendMessageRequest.builder().queueUrl(queueUrl).messageBody(write(message)).build()
      )
    }.void
}

object SqsNotifier {

  /** The notifier a deployment gets, or `Notifier.disabled` when the environment does not name a
    * queue.
    *
    * Disabled rather than failing, because an environment without a queue is a working environment:
    * the local server has none, and neither does a deployment with `deploy_mail` off. A missing
    * queue means no mail, which is visible and harmless; a constructor that threw would mean no API
    * at all.
    */
  def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): Notifier =
    env("MAIL_QUEUE_URL").map(_.trim).filter(_.nonEmpty) match {
      case None => Notifier.disabled
      case Some(queueUrl) =>
        val region = env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
        new SqsNotifier(queueUrl, lazily(() => client(region)))
    }

  /** Builds the client on first use and keeps it, rather than building one per call or one at
    * startup.
    *
    * Not at startup, which is the part that matters under SnapStart: anything constructed while the
    * services are being assembled risks being inside the snapshot, and a client that resolved its
    * credentials at publish time is a client holding credentials that do not exist. The `Handler`
    * already keeps the whole service graph behind a `lazy val` for the database's sake — this is
    * the same argument for the same reason, stated again here because the two could drift apart.
    *
    * Not per call either: a client sets up a connection pool and a credential provider, and doing
    * that for every notification would put a fresh handshake in front of each one.
    */
  private def lazily(build: () => SqsClient): () => SqsClient = {
    lazy val instance = build()
    () => instance
  }

  /* The JDK's URLConnection transport rather than Netty or Apache: this makes one small JSON call
   * per notification, with nothing to stream and no concurrency to pool for, and the other two are
   * megabytes of jar apiece on a function whose cold start a player waits through. */
  private def client(region: String): SqsClient =
    SqsClient
      .builder()
      .region(Region.of(region))
      .httpClientBuilder(UrlConnectionHttpClient.builder())
      .build()
}
