package com.vivi.matchmaker.notify

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import com.vivi.matchmaker.model.Player

/** The few things every notification puts together the same way.
  *
  * None of the words are here — they are in `mail/messages.properties`, and this asks `MailTemplates` for them. What is
  * here is the handful of decisions about *which* words: whether there is a game to link to, whether the challenger
  * wrote a description, how many names are in a list. Those are the things a properties file deliberately cannot
  * decide.
  */
object MailText {

    /** One notification, laid out: who it is to, what it has to say, and where to go about it.
      *
      * Every mail matchmaker sends has this shape, which is the argument for assembling it once. It is also very little
      * — a greeting, a blank line, whatever the template wrote, and the links — because these are plain-text mails
      * whose every line is a fact, so the layout is not where the thinking is.
      */
    def letter(recipient: Player, body: String, uiBaseUrl: String, playUrl: Option[String] = None): String =
        MailTemplates.render(
          "mail.letter",
          "nickname" -> recipient.nickname,
          "body" -> body,
          "links" -> links(uiBaseUrl, playUrl)
        )

    /** To the minute and stamped UTC, matching how the UI shows every other time: a deadline quoted to the second
      * invites a precision the engine's clock does not promise, and one quoted with no zone at all is read in whichever
      * zone the reader assumes.
      *
      * A format rather than a phrase, which is why it is here and not in the properties file.
      */
    def at(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(instant) + " UTC"

    /** Where to go: both links when the engine gave one, and matchmaker's alone when it did not. */
    def links(uiBaseUrl: String, playUrl: Option[String]): String =
        playUrl.fold(MailTemplates.render("mail.links", "uiBaseUrl" -> uiBaseUrl))(url =>
            MailTemplates.render("mail.links.play", "playUrl" -> url, "uiBaseUrl" -> uiBaseUrl)
        )

    /** The challenger's own words, when they wrote any: that is what a player recognises their challenge by.
      *
      * Empty when there are none, which is how the clause disappears from the sentence it sits inside — a challenge
      * with no description must not produce a mail quoting nothing. The space in front of it belongs to the clause and
      * is in the properties file with it.
      */
    def quoted(description: String): String =
        Some(description.trim)
            .filter(_.nonEmpty)
            .fold("")(text => MailTemplates.render("mail.quoted", "description" -> text))

    /** A list of names as a sentence: "Ada", "Ada and Grace", "Ada, Alan and Grace". */
    def and(names: Seq[String]): String = names.toList match {
        case Nil                    => ""
        case single :: Nil          => single
        case first :: second :: Nil => MailTemplates.render("mail.list.two", "first" -> first, "second" -> second)
        case many =>
            MailTemplates.render("mail.list.many", "others" -> many.init.mkString(", "), "last" -> many.last)
    }
}
