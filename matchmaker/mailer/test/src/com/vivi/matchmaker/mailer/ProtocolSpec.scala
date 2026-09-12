package com.vivi.matchmaker.mailer

import munit.FunSuite
import com.vivi.matchmaker.notify.{MailMessage => Queued}

/** The one thing this module's tests depend on matchmaker for: that the mail matchmaker writes is
  * the mail this function reads.
  *
  * `MailMessage` is stated twice on purpose — see the class comment on this module's copy — so
  * this reads each side's JSON with the other side's class. A field renamed on either side fails
  * here, rather than as messages that go round the queue three times and die in the dead-letter
  * queue.
  */
class ProtocolSpec extends FunSuite {

  private val fields = MailMessage("matchmaker@example.com", "alice@example.com", "subject", "body")
  private val queued = Queued("matchmaker@example.com", "alice@example.com", "subject", "body")

  test("a mail matchmaker enqueues is read by the mailer with every field intact") {
    val read = upickle.default.read[MailMessage](upickle.default.write(queued))
    assertEquals(read, fields)
  }

  test("and the other way round, so neither side can drift unnoticed") {
    val read = upickle.default.read[Queued](upickle.default.write(fields))
    assertEquals(read, queued)
  }
}
