package com.vivi.matchmaker.notify

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/** The words matchmaker's notifications are written in, read from `mail/messages.properties`.
  *
  * Not a template language, deliberately. A value may contain `${name}` and nothing else: no conditionals, no
  * formatting, no expressions. Anything that needs deciding — whether a challenge is ready, whether this player has a
  * deadline, which of two plurals applies — is decided in Scala, which then asks for the key it has decided on. A file
  * that could decide things for itself would be a second program in a language with no compiler and no tests.
  *
  * What that buys is that wording is not code. A typo, a softened sentence, a whole translation: none of them touch the
  * code that decides who is written to, and none of them can break it.
  *
  * Read once, on first use, and held. A file that is missing or unreadable is a packaging mistake rather than a runtime
  * condition, so it throws where it is noticed rather than degrading into mails with no text.
  */
object MailTemplates {

    private val resource = "/mail/messages.properties"

    /** `${name}`, where a name is a word. Deliberately narrow: anything that is not a plain name is not a variable, so
      * a stray `${...}` in the file is left alone and then caught by the check in `render` rather than being quietly
      * interpreted.
      */
    private val variable: Regex = """\$\{([A-Za-z0-9_.]+)\}""".r

    private lazy val templates: Map[String, String] = {
        val stream = Option(getClass.getResourceAsStream(resource))
            .getOrElse(throw new IllegalStateException(s"$resource is not on the classpath"))
        try {
            val properties = new Properties()
            // Through a UTF-8 reader rather than the stream, whose own default is ISO-8859-1: the file
            // holds an em dash, and read the other way it would reach a player as mojibake.
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8))
            properties.asScala.toMap
        } finally stream.close()
    }

    /** Every key in the file, for the spec that renders all of them. */
    def keys: Set[String] = templates.keySet

    /** One template as the file holds it, variables and all. For the spec that checks the file's own shape; everything
      * else wants [[render]].
      */
    def raw(key: String): String =
        templates.getOrElse(key, throw new IllegalArgumentException(s"no mail template '$key'"))

    /** The text for `key`, with each `${name}` replaced by the value given for it.
      *
      * A value may be empty, which is how an optional clause disappears — `mail.quoted` is empty when the challenger
      * wrote no description, and the sentence it sits inside reads correctly without it.
      *
      * A variable the file names and the caller did not supply is a mistake, and so is a key that is not in the file.
      * Both throw rather than producing a mail: a player sent the literal text "${actor}" is worse served than a player
      * sent nothing, and `Notifications.dispatch` turns the throw into a logged line naming the key.
      */
    def render(key: String, values: (String, String)*): String = {
        val template = templates.getOrElse(key, throw new IllegalArgumentException(s"no mail template '$key'"))
        val supplied = values.toMap
        val missing = variable.findAllMatchIn(template).map(_.group(1)).filterNot(supplied.contains).toSeq.distinct

        if (missing.nonEmpty)
            throw new IllegalArgumentException(
              s"mail template '$key' needs ${missing.mkString(", ")}, which ${if (missing.size == 1) "was" else "were"} not given"
            )

        // `quoteReplacement`, because a value is a player's own text: a nickname with a `$` in it is
        // not a backreference, and a description containing `\` is not an escape.
        variable.replaceAllIn(template, m => Regex.quoteReplacement(supplied(m.group(1))))
    }
}
