package com.vivi.matchmaker.model

import munit.FunSuite

/** An engine's result summary is HTML from another system, put into matchmaker's page — so what is checked is that
  * nothing but the allowed formatting gets through, however it is dressed up.
  */
class SummaryHtmlSpec extends FunSuite {

    import SummaryHtml.clean

    test("the formatting a summary is written in is kept as it was") {
        val line = "<strong>alice</strong> knocked out <strong>bob</strong> in round <em>4</em>.<br>Well fought."
        assertEquals(clean(line), line)
        assertEquals(clean("<ul><li>one</li><li>two</li></ul>"), "<ul><li>one</li><li>two</li></ul>")
    }

    test("a tag is written back in lower case, and a void one without its slash") {
        assertEquals(clean("<STRONG>a</Strong><br/><BR />"), "<strong>a</strong><br><br>")
    }

    test("a script, a link, an image or any other tag that is not on the list is shown as text") {
        assertEquals(clean("<script>alert(1)</script>"), "&lt;script&gt;alert(1)&lt;/script&gt;")
        assertEquals(clean("<a>x</a>"), "&lt;a&gt;x&lt;/a&gt;")
        assertEquals(clean("<img src=x onerror=alert(1)>"), "&lt;img src=x onerror=alert(1)&gt;")
        assertEquals(clean("<svg><script>x</script></svg>"), "&lt;svg&gt;&lt;script&gt;x&lt;/script&gt;&lt;/svg&gt;")
    }

    test("an allowed tag carrying an attribute is not a tag at all: no attribute ever reaches the page") {
        assertEquals(
          clean("""<strong onclick="alert(1)">a</strong>"""),
          "&lt;strong onclick=&quot;alert(1)&quot;&gt;a"
        )
        assertEquals(clean("<span style='color:red'>a</span>"), "&lt;span style=&#39;color:red&#39;&gt;a")
    }

    test("a tag left open is closed, and a closing tag with nothing to close is dropped") {
        assertEquals(clean("<strong>bold to the end"), "<strong>bold to the end</strong>")
        assertEquals(clean("plain</em> text</strong>"), "plain text")
        // Closing an outer tag closes what is open inside it first.
        assertEquals(clean("<em><strong>a</em>b"), "<em><strong>a</strong></em>b")
    }

    test("a character reference is kept and a bare ampersand or angle bracket is escaped") {
        assertEquals(clean("30&ndash;27 &amp; &#8212; &#x2014;"), "30&ndash;27 &amp; &#8212; &#x2014;")
        assertEquals(clean("Q&A: 3 < 4 > 2"), "Q&amp;A: 3 &lt; 4 &gt; 2")
        assertEquals(clean("&notaref"), "&amp;notaref")
    }

    test("a summary is stored cleaned, and not at all when nothing is left of it or it is too long") {
        assertEquals(SummaryHtml.accept(Some("  <b>alice</b> won  ")), Some("<b>alice</b> won"))
        assertEquals(SummaryHtml.accept(Some("   ")), None)
        assertEquals(SummaryHtml.accept(None), None)
        assertEquals(SummaryHtml.accept(Some("x" * (SummaryHtml.MaxLength + 1))), None)
        assertEquals(SummaryHtml.accept(Some("x" * SummaryHtml.MaxLength)).map(_.length), Some(SummaryHtml.MaxLength))
    }
}
