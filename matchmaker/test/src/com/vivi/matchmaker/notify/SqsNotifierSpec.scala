package com.vivi.matchmaker.notify

import munit.FunSuite

/** Which notifier an environment gets.
  *
  * The sending itself is not tested here — that would need a queue — but the choice in front of it
  * is worth pinning down, because getting it wrong is silent in both directions: an environment
  * that should send mail and quietly does not, or one that has no queue and fails every start's
  * last step trying to reach one.
  */
class SqsNotifierSpec extends FunSuite {

  private def env(pairs: (String, String)*): String => Option[String] = pairs.toMap.get

  test("an environment with no queue gets the notifier that sends nothing") {
    assertEquals(SqsNotifier.fromEnvironment(env()), Notifier.disabled)
    assertEquals(SqsNotifier.fromEnvironment(env("MAIL_QUEUE_URL" -> "   ")), Notifier.disabled)
  }

  // Built without touching AWS: no client is constructed until the first notification, which is
  // also what keeps one out of a SnapStart snapshot.
  test("an environment naming a queue gets a notifier that will send to it") {
    val notifier = SqsNotifier.fromEnvironment(
      env("MAIL_QUEUE_URL" -> "https://sqs.eu-west-2.amazonaws.com/1/matchmaker-dev-mail", "AWS_REGION" -> "eu-west-2")
    )
    assert(notifier.isInstanceOf[SqsNotifier])
  }
}
