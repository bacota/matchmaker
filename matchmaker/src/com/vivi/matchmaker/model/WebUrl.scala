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

    /** `http://` to this machine itself (`localhost`, `127.0.0.1` or `[::1]`, with or without a port), as the local
      * stack's engines are. Anything else between the scheme and the path, such as a user name before an `@`, or a host
      * that merely starts `localhost`, is not.
      */
    private val loopbackHttp = """(?i)http://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?([/?#].*)?""".r

    /** Whether a page at `url` reaches the browser as it was sent: an https url, or http to this machine.
      *
      * A sign-in hand-off code goes only to such a page (`Auth.handOff`). The code in the url's fragment never crosses
      * the network, but a script injected into a plain-http page by someone in the network path can read it from
      * `location.hash` and spend it at the engine's open `POST /api/handoff`, where the code alone is the credential.
      * Loopback is allowed because local runs serve their engines over plain http and no network lies in between.
      */
    def isSecure(url: String): Boolean =
        url.toLowerCase(java.util.Locale.ROOT).startsWith("https://") || loopbackHttp.matches(url)
}
