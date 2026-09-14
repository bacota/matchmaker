package com.vivi.matchmaker.notify

import munit.FunSuite

/** The file, and the rules about reading it.
  *
  * What each template *says* is `ChallengeMailSpec` and `MatchMailSpec`, which assert the sentences a player actually
  * receives. Between them those two render every kind down every branch — described and not, with a role and without,
  * on a clock and off it — so a variable named in the file that no caller supplies makes one of them throw. This spec
  * is the other half: that the file is well formed, and that the substitution behaves where it is asked to do something
  * it should refuse.
  */
class MailTemplatesSpec extends FunSuite {

    test("the file is on the classpath and is not empty") {
        assert(MailTemplates.keys.nonEmpty)
        // Every key is prefixed, so a stray property from somewhere else would show up here.
        assert(MailTemplates.keys.forall(_.startsWith("mail.")), MailTemplates.keys.filterNot(_.startsWith("mail.")))
    }

    // Read through a UTF-8 reader rather than the stream, whose own default is ISO-8859-1. Read the
    // other way, this dash reaches a player as mojibake.
    test("the file is read as UTF-8") {
        assert(MailTemplates.render("mail.match.ended.forfeited").contains("—"))
    }

    test("a backslash-n in the file is a line break") {
        val body = MailTemplates.render("mail.match.turn.body", "moved" -> "a", "next" -> "b")
        assertEquals(body, "a\nb")
    }

    /* A value may begin with a space, written "\ " in the file, because some clauses are spliced
     * straight after a word. Properties would otherwise drop it, and the mail would read
     * "your Chess challenge"best of three"". */
    test("a clause that must begin with a space does") {
        assertEquals(MailTemplates.render("mail.quoted", "description" -> "best of three"), " \"best of three\"")
    }

    // How an optional clause disappears: the sentence is one template, and the clause is empty.
    test("an empty variable leaves the sentence reading correctly") {
        val withoutEither = MailTemplates.render(
          "mail.challenge.accepted.body",
          "actor" -> "bob",
          "game" -> "Chess",
          "quoted" -> "",
          "role" -> "",
          "roster" -> "Every role is now taken."
        )
        assertEquals(withoutEither, "bob has accepted your Chess challenge.\nEvery role is now taken.")
    }

    // A player's own text is not a template. A nickname with a dollar in it used to be a
    // backreference as far as the regex was concerned.
    test("a value containing a dollar or a backslash is text, not a substitution") {
        val body = MailTemplates.render("mail.match.turn.next", "next" -> """a$1b\c""")
        assertEquals(body, """It is now a$1b\c's turn.""")
    }

    /* Both refusals. A mail that reaches a player saying "Hello ${nickname}," is worse than one that
     * never arrives, so neither of these degrades quietly -- and `Notifications.dispatch` turns the
     * throw into a logged line naming the template. */
    test("a key that is not in the file is refused, by name") {
        val failure = intercept[IllegalArgumentException](MailTemplates.render("mail.no.such.thing"))
        assert(failure.getMessage.contains("mail.no.such.thing"), failure.getMessage)
    }

    test("a variable the file names and the caller did not supply is refused, by name") {
        val failure = intercept[IllegalArgumentException](MailTemplates.render("mail.links", "wrong" -> "x"))
        assert(failure.getMessage.contains("mail.links"), failure.getMessage)
        assert(failure.getMessage.contains("uiBaseUrl"), failure.getMessage)
    }

    /* Every `${` in the file has to be a variable this can actually substitute: the pattern is
     * deliberately narrow, so `${a b}` or an unclosed `${a` would be left in the text and mailed out
     * as it stands. Checked over the whole file rather than trusted to review. */
    test("every placeholder in the file is a plain name") {
        val placeholder = """\$\{[^}]*\}?""".r
        val variable = """\$\{[A-Za-z0-9_.]+\}""".r

        val malformed = MailTemplates.keys.toSeq.sorted.flatMap { key =>
            val template = MailTemplates.render(key, namesIn(key).map(_ -> "x").toSeq*)
            placeholder.findAllIn(template).toSeq.map(found => s"$key: $found")
        }
        // Rendering above replaced every well-formed variable, so anything still matching is not one.
        assertEquals(malformed, Seq.empty)

        // And the narrow pattern really is narrower than the loose one, so the check above has teeth.
        assert(variable.findFirstIn("${a b}").isEmpty)
    }

    /* The variables one template names, found the same way `render` finds them. Used only by the test
     * above, which needs to supply them all in order to see what is left behind. */
    private def namesIn(key: String): Set[String] =
        """\$\{([A-Za-z0-9_.]+)\}""".r.findAllMatchIn(MailTemplates.raw(key)).map(_.group(1)).toSet
}
