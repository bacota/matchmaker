package com.vivi.matchmaker.notify

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/** The few things every notification says the same way.
  *
  * Deliberately small. What each mail says is written out in its own template, because prose shared between two
  * notifications ends up saying neither of them well — but a deadline rendered one way in one mail and another way in
  * the next is not style, it is a mistake, and so is a link that leads somewhere slightly different.
  */
object MailText {

    /** To the minute and stamped UTC, matching how the UI shows every other time: a deadline quoted to the second
      * invites a precision the engine's clock does not promise, and one quoted with no zone at all is read in whichever
      * zone the reader assumes.
      */
    def at(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(instant) + " UTC"

    /** Where to go. The engine's own link first when there is one: it is where the game is actually played, and the
      * home screen is a list this match is one row of. Both, because the engine's link is not matchmaker's to guarantee
      * and a player who cannot use it still has somewhere to go.
      */
    def links(uiBaseUrl: String, playUrl: Option[String]): String =
        playUrl.fold(s"Open matchmaker: $uiBaseUrl")(url => s"Play: $url\nOpen matchmaker: $uiBaseUrl")

    /** The challenger's own words, when they wrote any: that is what a player recognises their challenge by, and a
      * challenge with an empty description must not produce a mail quoting nothing.
      */
    def described(description: String): Option[String] =
        Some(description.trim).filter(_.nonEmpty).map(text => s"\"$text\"")

    /** A list of names as a sentence: "you", "you and Ada", "you, Ada and Grace". */
    def and(names: Seq[String]): String = names.toList match {
        case Nil           => ""
        case single :: Nil => single
        case many          => s"${many.init.mkString(", ")} and ${many.last}"
    }
}
