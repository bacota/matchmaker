package com.vivi.matchmaker.bounce

import munit.FunSuite
import com.vivi.matchmaker.model.{EmailSuppression, SuppressionReason}

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

    /* Understood documents, which is most of this suite. A document that cannot be read is a
     * different outcome entirely -- it is failed to the dead-letter queue rather than acknowledged
     * -- so it is asserted on by name below rather than arriving here as an empty list. */
    private def read(document: String): Seq[EmailSuppression.Event] =
        SesEvent.suppressions(ujson.read(document)) match {
            case SesEvent.Reading.Understood(events) => events
            case SesEvent.Reading.Unreadable(why)    => fail(s"expected a readable notification, got: $why")
        }

    private def reading(document: String): SesEvent.Reading = SesEvent.suppressions(ujson.read(document))

    private def unreadable(document: String, clue: String): Unit =
        reading(document) match {
            case SesEvent.Reading.Unreadable(_)      => ()
            case SesEvent.Reading.Understood(events) => fail(s"$clue: expected unreadable, got $events")
        }

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

    /* The identity that keeps the transient threshold honest. SQS is at-least-once, so the same
     * notification arrives more than once as a matter of course; an id that differed between
     * receives would let two real delays plus one redelivery suppress a reachable player. */
    test("the same notification read twice yields the same event id") {
        assertEquals(read(permanentBounce).map(_.eventId), read(permanentBounce).map(_.eventId))
        assertEquals(read(complaint).map(_.eventId), read(complaint).map(_.eventId))
        assertEquals(read(delay).map(_.eventId), read(delay).map(_.eventId))
    }

    test("every id is a fixed-width hash, so no component can smuggle a separator into it") {
        val ids = (read(permanentBounce) ++ read(complaint) ++ read(delay)).map(_.eventId)
        assertEquals(ids.size, 3)
        assert(ids.forall(id => id.length == 64 && id.forall(c => c.isDigit || ('a' to 'f').contains(c))), ids.toString)
    }

    test("two recipients of one notification are two events") {
        val two = permanentBounce.replace(
          "\"emailAddress\": \"player@example.invalid\"",
          "\"emailAddress\": \"one@example.invalid\" }, { \"emailAddress\": \"two@example.invalid\""
        )
        assertEquals(read(two).map(_.eventId).distinct.size, 2)
    }

    /* The things that must *not* collapse into one id: a different kind of event about the same
     * mail, a different mail, and a second genuine delay about the same mail. The last is why a
     * delivery delay's identity uses its timestamp -- it is the only discriminator such a document
     * has. */
    test("different events are different ids") {
        val bounceId = read(permanentBounce).map(_.eventId)
        assertNotEquals(bounceId, read(complaint).map(_.eventId), "same mail and recipient, different kind")

        val otherMail = permanentBounce.replace(
          "0100017b-1234-4abc-8def-0123456789ab-000000",
          "0100017b-9999-4abc-8def-0123456789ab-000000"
        )
        assertNotEquals(read(otherMail).map(_.eventId), bounceId, "a different mail")

        val laterDelay = delay.replace("2026-09-17T18:00:03.000Z", "2026-09-17T19:30:00.000Z")
        assertNotEquals(read(laterDelay).map(_.eventId), read(delay).map(_.eventId), "a second real delay")
    }

    /* A bounce and a complaint carry a feedbackId, which is SES's own identifier for the event and is
     * preferred over the timestamp: it is the field guaranteed not to repeat. */
    test("a bounce with a new feedback id is a new event, even at the same timestamp") {
        val again = permanentBounce.replace("0100017b-aaaa-bbbb-cccc-0123456789ab-000000", "0100017b-bbbb-aaaa")
        assertNotEquals(read(again).map(_.eventId), read(permanentBounce).map(_.eventId))
    }

    /* The distinction the dead-letter queue depends on: a kind we do not act on is understood and
     * acknowledged, where a document we cannot read is failed. Both used to be an empty list, which
     * meant a bounce whose shape had moved was acknowledged and lost with only a log line. */
    test("a recognised kind whose payload cannot be read is unreadable, not empty") {
        unreadable("""{ "eventType": "Bounce" }""", "a bounce with no bounce object")
        unreadable("""{ "eventType": "Bounce", "bounce": { "bounceType": "Permanent" } }""", "no recipients key")
        unreadable("""{ "eventType": "Bounce", "bounce": { "bouncedRecipients": [] } }""", "an empty recipients list")
        unreadable(
          """{ "eventType": "Bounce", "bounce": { "bouncedRecipients": [{}] } }""",
          "a recipient with no address"
        )
        unreadable("""{ "eventType": "Complaint", "complaint": {} }""", "a complaint naming nobody")
        unreadable("""{ "eventType": "DeliveryDelay", "deliveryDelay": {} }""", "a delay naming nobody")
    }

    /* Which is also what a renamed key in a future SES payload looks like from here. The point of
     * failing it is that the consequence is a pile of messages in a queue somebody can see, rather
     * than matchmaker quietly going on mailing every address that bounces. */
    test("a bounce whose recipients key has moved is unreadable") {
        unreadable(permanentBounce.replace("bouncedRecipients", "recipientsThatBounced"), "renamed key")
        unreadable(permanentBounce.replace(""""bounce":""", """"bounceDetail":"""), "renamed payload")
    }

    test("a recipient with a blank address leaves the bounce unattributed, and so unreadable") {
        val blank = permanentBounce.replace(""""emailAddress": "player@example.invalid"""", """"emailAddress": "   """")
        unreadable(blank, "a blank address")
    }

    test("a document that is not an SES notification at all is unreadable") {
        unreadable("{}", "no eventType")
        unreadable("""{ "mail": { "messageId": "x" } }""", "a mail and nothing else")
        unreadable("[]", "an array")
        unreadable("4", "a number")
    }

    /* Understood, and acknowledged with nothing recorded. These are the kinds a configuration set
     * may publish beyond the three asked for, and a kind AWS adds later: filling the dead-letter
     * queue with things that are working as intended is the fastest way to make a full one mean
     * nothing. */
    test("a kind we do not act on is understood, not unreadable") {
        (Seq("Send", "Delivery", "Open", "Click", "Reject", "RenderingFailure", "Subscription") :+ "SomethingNew")
            .foreach { kind =>
                val document = s"""{ "eventType": "$kind", "mail": { "messageId": "x" } }"""
                assertEquals(read(document), Seq.empty, s"$kind should be understood and ignored")
            }
    }
}
