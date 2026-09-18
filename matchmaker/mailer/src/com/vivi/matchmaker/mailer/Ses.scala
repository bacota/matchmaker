package com.vivi.matchmaker.mailer

/** Sending one mail through SES v2.
  *
  * Building the request is separated from making it, and is a pure function, because the request body is the part that
  * is easy to get subtly wrong and impossible to see afterwards: a mail that SES rejects for a malformed `Content`
  * looks exactly like one it never received.
  */
object Ses {

    /** The v2 API's `SendEmail`, as JSON.
      *
      * `Simple` content with a text part only. There is no HTML alternative to offer -- every notification matchmaker
      * sends is a few facts and a link -- and a multipart mail with one part is a heavier thing that renders
      * identically.
      *
      * UTF-8 declared explicitly on both parts. SES defaults to 7-bit ASCII, which is not what a nickname is: a player
      * called "Zoë" would otherwise arrive as one called "Zo".
      *
      * `configurationSet`, when there is one, is what makes SES report back. A send under a configuration set publishes
      * its bounces, complaints and delivery delays to that set's event destinations; a send without one is delivered
      * just the same and tells us nothing afterwards. So this is the single line on which bounce handling depends --
      * `matchmaker-<env>-mail` in a deployment, and nothing at all where `MAIL_CONFIG_SET` is unset.
      */
    def sendEmailBody(message: MailMessage, configurationSet: Option[String] = None): String = {
        val request = ujson.Obj(
          "FromEmailAddress" -> message.sender,
          "Destination" -> ujson.Obj("ToAddresses" -> ujson.Arr(message.recipient)),
          "Content" -> ujson.Obj(
            "Simple" -> ujson.Obj(
              "Subject" -> ujson.Obj("Data" -> message.subject, "Charset" -> "UTF-8"),
              "Body" -> ujson.Obj("Text" -> ujson.Obj("Data" -> message.body, "Charset" -> "UTF-8"))
            )
          )
        )

        /* Omitted rather than sent empty when there is no configuration set, which is what makes
         * bounce handling optional infrastructure rather than a prerequisite: an environment without
         * one sends exactly the request it sent before, and SES does not have to be asked to publish
         * events nobody is listening for. `MAIL_CONFIG_SET` unset is that environment. */
        configurationSet.foreach(name => request("ConfigurationSetName") = name)
        upickle.default.write(request)
    }

    /** The regional endpoint for the v2 API. Not configurable: a function that mailed through some other region's SES
      * because a variable was wrong would be a puzzle to find, and the region is already decided by where this function
      * runs.
      */
    def endpoint(region: String): String = s"https://email.$region.amazonaws.com/v2/email/outbound-emails"
}

/** What the handler uses to send, so that a test can assert on the request without a network. */
trait MailSender {
    def send(message: MailMessage): Unit
}

/** The real one: a signed POST to SES.
  *
  * The function is deliberately *outside* the VPC, so this reaches SES over its public endpoint with no NAT gateway and
  * no interface endpoint to pay for. It has no database to reach and nothing else to talk to -- every fact it needs is
  * in the message.
  */
class SesSender(region: String, sigV4: SigV4, configurationSet: Option[String] = None) extends MailSender {
    def send(message: MailMessage): Unit = {
        sigV4.post(
          url = Ses.endpoint(region),
          body = Ses.sendEmailBody(message, configurationSet),
          service = "ses",
          headers = Map("content-type" -> "application/json")
        )
        ()
    }
}
