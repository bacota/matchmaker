package com.vivi.matchmaker.model

/** Whether a url names a web page, by http or https, and nothing a browser would run instead.
  *
  * Every url an engine gives matchmaker (a match's play, public, status and cancel urls) and a game's character url
  * ends up in an `href` or a tab's address, where a `javascript:` or `data:` url would run its script in matchmaker's
  * origin, with the player's session to hand. So a url is taken only if it starts, exactly, with `http://` or
  * `https://`, case aside. A url with anything before its scheme, even spaces a browser would skip, is refused rather
  * than second-guessed.
  *
  * Shared: the server refuses such a url where it comes in, and the UI checks again before it sends a tab to one.
  */
object WebUrl {

    def isWeb(url: String): Boolean = {
        val lower = url.toLowerCase(java.util.Locale.ROOT)
        lower.startsWith("https://") || lower.startsWith("http://")
    }
}
