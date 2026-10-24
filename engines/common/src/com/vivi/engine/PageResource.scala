package com.vivi.engine

import java.nio.charset.StandardCharsets.UTF_8

/** A page's own styles or script, kept as a plain `.css` or `.js` file under the engine's `resources/` and inlined into
  * the page, which stays one response with nothing else to fetch.
  *
  * Read once, by the object that holds the page, so a missing file fails the engine as it starts rather than on the
  * first page view.
  */
object PageResource {

    /** The text of the resource at `path` (`"stratego/play.js"`), without its final newline. */
    def text(path: String): String = {
        val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
            .getOrElse(throw IllegalStateException(s"page resource $path is missing"))
        try String(stream.readAllBytes(), UTF_8).stripSuffix("\n")
        finally stream.close()
    }
}
