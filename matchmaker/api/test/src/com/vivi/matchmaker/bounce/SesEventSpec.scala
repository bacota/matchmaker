package com.vivi.matchmaker.bounce

import munit.FunSuite
import com.vivi.matchmaker.model.SuppressionReason

/** Reading SES's notifications, against documents shaped like the ones AWS documents.
  *
  * The fixtures are deliberately whole — headers, message ids, timestamps, the `mail` object and all — rather than the
  * four keys the parser looks at. A document trimmed to what the parser reads cannot catch the mistake this most
  * plausibly makes, which is reading the wrong nesting level and finding something that happens to be there.
  */
class SesEventSpec extends FunSuite {

    private def envelope(notificationType: String, detail: String): String =
        s"""{
          "$notificationType": $detail,
          "eventType": "${notificationType.capitalize}",
          "mail": {
            "timestamp": "2026-09-17T18:00:00.000Z",
            "source": "matchmaker@example.com",
            "sourceArn": "arn:aws:ses:us-east-1:123456789012:identity/matchmaker@example.com",
            "sendingAccountId": "123456789012",
            "messageId": "0100017b-1234-4abc-8def-0123456789ab-000000",
            "destination": ["player@example.invalid"],
            "headersTruncated": false,
            "commonHeaders": {
              "from": ["matchmaker@example.com"],
              "to": ["player@example.invalid"],
              "subject": "Your turn in tic-tac-toe"
            }
          }
        }"""

    private val permanentBounce = envelope(
      "bounce",
      """{
        "feedbackId": "0100017b-aaaa-bbbb-cccc-0123456789ab-000000",
        "bounceType": "Permanent",
        "bounceSubType": "General",
        "bouncedRecipients": [{
          "emailAddress": "player@example.invalid",
          "action": "failed",
          "status": "5.1.1",
          "diagnosticCode": "smtp; 550 5.1.1 user unknown"
        }],
        "timestamp": "2026-09-17T18:00:01.000Z",
        "reportingMTA": "dsn; a8-70.smtp-out.amazonses.com"
      }"""
    )

    private val transientBounce = permanentBounce
        .replace(""""bounceType": "Permanent"""", """"bounceType": "Transient"""")
        .replace(""""bounceSubType": "General"""", """"bounceSubType": "MailboxFull"""")

    private val complaint = envelope(
      "complaint",
      """{
        "feedbackId": "0100017b-dddd-eeee-ffff-0123456789ab-000000",
        "complaintSubType": null,
        "complainedRecipients": [{ "emailAddress": "player@example.invalid" }],
        "timestamp": "2026-09-17T18:00:02.000Z",
        "userAgent": "Amazon SES Mailbox Simulator",
        "complaintFeedbackType": "abuse",
        "arrivalDate": "2026-09-17T18:00:02.000Z"
      }"""
    )

    private val delay = s"""{
      "eventType": "DeliveryDelay",
      "deliveryDelay": {
        "timestamp": "2026-09-17T18:00:03.000Z",
        "delayType": "TransientCommunicationFailure",
        "expirationTime": "2026-09-18T18:00:03.000Z",
        "delayedRecipients": [{
          "emailAddress": "player@example.invalid",
          "status": "4.4.1",
          "diagnosticCode": "smtp; 450 4.4.1 connection timed out"
        }]
      },
      "mail": { "messageId": "0100017b-1234-4abc-8def-0123456789ab-000000" }
    }"""

    private def read(document: String) = SesEvent.suppressions(ujson.read(document))

    test("a permanent bounce is permanent, and keeps what SES said about it") {
        val events = read(permanentBounce)
        assertEquals(events.map(_.email), Seq("player@example.invalid"))
        assertEquals(events.map(_.reason), Seq(SuppressionReason.Bounce))
        assertEquals(events.map(_.permanent), Seq(true))
        assertEquals(events.head.diagnostic, Some("smtp; 550 5.1.1 user unknown"))
    }

    test("a transient bounce is not permanent") {
        val events = read(transientBounce)
        assertEquals(events.map(_.reason), Seq(SuppressionReason.Bounce))
        assertEquals(events.map(_.permanent), Seq(false))
    }

    /* Undetermined is SES saying the remote server said something it could not classify, which is at
     * least as often a badly behaved mail server as a dead mailbox. Treated as transient: guessing
     * wrong this way costs two more bounces before the threshold suppresses it anyway, where
     * guessing wrong the other way silently cuts off a playing player. */
    test("an undetermined bounce is treated as transient") {
        val events = read(permanentBounce.replace(""""bounceType": "Permanent"""", """"bounceType": "Undetermined""""))
        assertEquals(events.map(_.permanent), Seq(false))
    }

    test("a complaint is permanent, and carries the feedback type") {
        val events = read(complaint)
        assertEquals(events.map(_.email), Seq("player@example.invalid"))
        assertEquals(events.map(_.reason), Seq(SuppressionReason.Complaint))
        assertEquals(events.map(_.permanent), Seq(true))
        assertEquals(events.head.diagnostic, Some("abuse"))
    }

    test("a delivery delay is recorded, transient, with the delay type") {
        val events = read(delay)
        assertEquals(events.map(_.reason), Seq(SuppressionReason.Delay))
        assertEquals(events.map(_.permanent), Seq(false))
        assertEquals(events.head.diagnostic, Some("TransientCommunicationFailure"))
    }

    test("every recipient of one notification is recorded, not just the first") {
        val two = permanentBounce.replace(
          """{
          "emailAddress": "player@example.invalid",
          "action": "failed",
          "status": "5.1.1",
          "diagnosticCode": "smtp; 550 5.1.1 user unknown"
        }""",
          """{ "emailAddress": "one@example.invalid", "diagnosticCode": "smtp; 550 5.1.1 user unknown" },
             { "emailAddress": "two@example.invalid" }"""
        )
        assertEquals(read(two).map(_.email), Seq("one@example.invalid", "two@example.invalid"))
        assertEquals(
          read(two).map(_.diagnostic),
          Seq(Some("smtp; 550 5.1.1 user unknown"), Some("bounce Permanent/General"))
        )
    }

    /* `notificationType` is what an identity's own SNS topic calls the key; `eventType` is what a
     * configuration set's event destination calls it. The deployment uses the second, and both are
     * read because the first is what every example of this document on the internet has in it. */
    test("the older notificationType spelling is read too") {
        val events = read(permanentBounce.replace(""""eventType": "Bounce"""", """"notificationType": "Bounce""""))
        assertEquals(events.map(_.reason), Seq(SuppressionReason.Bounce))
    }

    test("the kinds that say nothing about the address say nothing here") {
        val ignored = Seq("Send", "Delivery", "Open", "Click", "Reject", "RenderingFailure", "Subscription")
        ignored.foreach { kind =>
            val document =
                s"""{ "eventType": "$kind", "mail": { "messageId": "x" },
                      "delivery": { "recipients": ["player@example.invalid"] } }"""
            assertEquals(read(document), Seq.empty, s"$kind should be ignored")
        }
    }

    test("a document that is not one of these is no events, not an error") {
        assertEquals(read("""{}"""), Seq.empty)
        assertEquals(read("""{ "eventType": "Bounce" }"""), Seq.empty)
        assertEquals(read("""{ "eventType": "Bounce", "bounce": { "bounceType": "Permanent" } }"""), Seq.empty)
        assertEquals(read("""{ "eventType": "Bounce", "bounce": { "bouncedRecipients": [{}] } }"""), Seq.empty)
        assertEquals(read("""[]"""), Seq.empty)
        assertEquals(read("""4"""), Seq.empty)
    }

    test("a recipient with a blank address is skipped, since there is nothing to key a row by") {
        val blank = permanentBounce.replace(""""emailAddress": "player@example.invalid"""", """"emailAddress": "   """")
        assertEquals(read(blank), Seq.empty)
    }
}
