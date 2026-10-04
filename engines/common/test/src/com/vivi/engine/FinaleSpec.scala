package com.vivi.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite

/** The picture at the end of a match — [[Finale.script]] — run as the page runs it, in Node, against the few elements
  * it touches. As `SignInHandOffSpec` does, this fails rather than skips without Node.
  */
class FinaleSpec extends FunSuite {

    private val win = Finale.Art("""<svg aria-label="won"></svg>""", "You win!")
    private val lose = Finale.Art("""<svg aria-label="lost"></svg>""", "Not this time.")

    /** What the box looked like after each step: whether it is hidden, its class, its picture and caption, and how many
      * times the picture has been drawn in all.
      */
    private case class Seen(hidden: Boolean, cls: String, art: String, caption: String, draws: Int)

    /** Runs `steps` — JavaScript calling `show(...)` and `close()` — and reports the box after each one. */
    private def run(steps: String*): Seq[Seen] = {
        val program =
            s"""const el = () => ({ hidden: true, className: "", textContent: "", listeners: {},
              addEventListener(type, f) { this.listeners[type] = f; } });
const els = { "finale": el(), "finale-caption": el(), "finale-close": el() };
let draws = 0, art = "";
els["finale-art"] = { set innerHTML(v) { draws++; art = v; }, get innerHTML() { return art; } };
globalThis.document = { getElementById: id => els[id] };
${Finale.script(win, lose)}
const show = finale();
const close = () => els["finale-close"].listeners.click();
const seen = [];
const look = () => seen.push({ hidden: els.finale.hidden, cls: els.finale.className, art,
  caption: els["finale-caption"].textContent, draws });
${steps.map(step => s"$step; look();").mkString("\n")}
console.log(JSON.stringify(seen));
"""
        val file = Files.createTempFile("finale", ".js")
        try {
            Files.writeString(file, program, UTF_8)
            val process =
                try new ProcessBuilder("node", file.toString).redirectErrorStream(true).start()
                catch {
                    case e: java.io.IOException =>
                        fail(s"these tests run the page's script in Node, which is not on the PATH: ${e.getMessage}")
                }
            val output = new String(process.getInputStream.readAllBytes(), UTF_8)
            assertEquals(process.waitFor(), 0, output)
            ujson
                .read(output)
                .arr
                .toSeq
                .map(s => Seen(s("hidden").bool, s("cls").str, s("art").str, s("caption").str, s("draws").num.toInt))
        } finally Files.deleteIfExists(file)
    }

    test("nothing is shown until there is a result, and then the winner's picture or the loser's") {
        val seen = run("show(null)", """show("win")""")
        assert(seen(0).hidden)
        assertEquals(seen(1), Seen(false, "finale win", win.svg, win.caption, 1))

        val lost = run("""show("lose")""").head
        assertEquals((lost.hidden, lost.cls, lost.art, lost.caption), (false, "finale lose", lose.svg, lose.caption))
    }

    test("the same result rendered again is not drawn again, which would restart its animation") {
        val seen = run("""show("win")""", """show("win")""", """show("win")""")
        assertEquals(seen.map(_.draws), Seq(1, 1, 1))
    }

    test("once closed it stays closed, whatever the page renders after") {
        val seen = run("""show("win")""", "close()", """show("win")""", "show(null)", """show("lose")""")
        assert(!seen(0).hidden)
        assert(seen.drop(1).forall(_.hidden))
    }

    test("a picture cannot close the script it is carried in") {
        val script = Finale.script(Finale.Art("</script><svg/>", "x"), lose)
        assert(!script.contains("</script>"))
    }
}
