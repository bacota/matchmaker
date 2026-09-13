package com.vivi.matchmaker.mailer

import munit.FunSuite

/** The request SES is sent.
  *
  * Worth pinning down because none of it is visible afterwards: a mail rejected for a malformed
  * `Content` and a mail that was never sent look the same from here.
  */
class SesSpec extends FunSuite {

  private val message = MailMessage("matchmaker@example.com", "alice@example.com", "subject", "line one\nline two")

  test("the body is a v2 SendEmail with a simple text part") {
    val json = ujson.read(Ses.sendEmailBody(message))
    assertEquals(json("FromEmailAddress").str, "matchmaker@example.com")
    assertEquals(json("Destination")("ToAddresses").arr.map(_.str).toList, List("alice@example.com"))
    assertEquals(json("Content")("Simple")("Subject")("Data").str, "subject")
    assertEquals(json("Content")("Simple")("Body")("Text")("Data").str, "line one\nline two")
  }

  // SES defaults to 7-bit ASCII, which would turn a player called "Zoë" into one called "Zo".
  test("both parts declare UTF-8") {
    val json = ujson.read(Ses.sendEmailBody(message))
    assertEquals(json("Content")("Simple")("Subject")("Charset").str, "UTF-8")
    assertEquals(json("Content")("Simple")("Body")("Text")("Charset").str, "UTF-8")
  }

  test("the endpoint is the running region's") {
    assertEquals(Ses.endpoint("eu-west-2"), "https://email.eu-west-2.amazonaws.com/v2/email/outbound-emails")
  }
}
