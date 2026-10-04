package com.vivi.engine

/** The pieces a game's [[Game.summary]] is written from. */
object ResultText {

    /** A player, in bold, by the nickname matchmaker sent — escaped, since a nickname is whatever its player typed — or
      * by `fallback` (their side, their mark) for a match created before nicknames were kept.
      */
    def name(nickname: Option[String], fallback: String): String =
        "<strong>" + HtmlText.escape(nickname.filter(_.trim.nonEmpty).getOrElse(fallback)) + "</strong>"
}
