package com.vivi.matchmaker.model

/** A game engine's one-line HTML account of how a match ended — see `MatchResults.summary` — made safe to put in a
  * page.
  *
  * The engine is another system, so what it sends is treated as untrusted: the only markup that survives is a short
  * list of formatting tags, written exactly — `<strong>`, `</em>`, `<br>` — with no attributes, since an attribute is
  * where a script or a link would ride in. Everything else, a tag that is not on the list or one that carries anything
  * at all, is kept as the text it was, escaped. So the output is safe by construction, whatever the input: it contains
  * no markup this did not write itself.
  *
  * Tags are also balanced, so a summary cannot leave the rest of the row in bold: a closing tag with nothing open to
  * close is dropped, and whatever is still open at the end is closed.
  *
  * Shared with the UI, which cleans again before it renders — the server stores only what this produced, but the page
  * should not be the place that has to trust that.
  */
object SummaryHtml {

    /** The longest summary kept, once cleaned. A summary is a line, not a page; a longer one is not shown at all, and
      * the result table is shown instead.
      */
    val MaxLength = 2000

    private val Allowed =
        Set("b", "strong", "i", "em", "u", "s", "small", "sub", "sup", "br", "p", "span", "ul", "ol", "li")
    private val Void = Set("br")

    // A whole tag, and nothing else: no attributes, at most a trailing slash.
    private val Tag = "<(/?)([a-zA-Z][a-zA-Z0-9]*)\\s*/?>".r
    // A character reference written out in full: named, decimal or hex.
    private val Entity = "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});".r

    /** What an engine sent, as it is stored: cleaned, and `None` when there is nothing left of it or too much. */
    def accept(raw: Option[String]): Option[String] =
        raw
            .filter(_.length <= MaxLength * 4)
            .map(clean(_).trim)
            .filter(s => s.nonEmpty && s.length <= MaxLength)

    /** `html` with every tag that is not an allowed one, exactly written, turned into text. */
    def clean(html: String): String = {
        val out = new StringBuilder
        var open = List.empty[String]
        var i = 0

        // Looked for in a short window: a tag or a reference is never long, and the input is not to be scanned from
        // every '<' to its end.
        def window(from: Int) = html.subSequence(from, math.min(html.length, from + 48))

        while (i < html.length) {
            html.charAt(i) match {
                case '<' =>
                    Tag.findPrefixMatchOf(window(i)) match {
                        case Some(m) if Allowed(m.group(2).toLowerCase) =>
                            val name = m.group(2).toLowerCase
                            if (Void(name)) out ++= s"<$name>"
                            else if (m.group(1).isEmpty) {
                                out ++= s"<$name>"
                                open = name :: open
                            } else if (open.contains(name)) {
                                val (inner, rest) = open.span(_ != name)
                                (inner :+ name).foreach(t => out ++= s"</$t>")
                                open = rest.drop(1)
                            }
                            i += m.end
                        case _ =>
                            out ++= "&lt;"
                            i += 1
                    }
                case '&' =>
                    Entity.findPrefixMatchOf(window(i)) match {
                        case Some(m) =>
                            out ++= m.matched
                            i += m.end
                        case None =>
                            out ++= "&amp;"
                            i += 1
                    }
                case '>'  => out ++= "&gt;"; i += 1
                case '"'  => out ++= "&quot;"; i += 1
                case '\'' => out ++= "&#39;"; i += 1
                case c    => out += c; i += 1
            }
        }
        open.foreach(t => out ++= s"</$t>")
        out.toString
    }
}
