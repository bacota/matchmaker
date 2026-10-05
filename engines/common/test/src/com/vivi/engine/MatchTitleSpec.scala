package com.vivi.engine

import munit.FunSuite

/** A match's pages are titled by what players call the game and the match's own message, and never by the match id. */
class MatchTitleSpec extends FunSuite {

    private case class AMatch(
        matchId: String,
        override val gameDisplayName: Option[String],
        override val description: Option[String]
    ) extends MatchLike {
        def isPublic: Boolean = false
        def moveCallbackUrl: Option[String] = None
        def resultsCallbackUrl: Option[String] = None
    }

    private val id = "3f1c2a9e-0b7d-4c55-9a1e-6d2b8f4e7a10"

    test("the game's name and the match's message") {
        assertEquals(MatchTitle.of(AMatch(id, Some("Boxing"), Some("Title fight")), "boxing"), "Boxing — Title fight")
    }

    test("the game's name alone when the match has no message") {
        assertEquals(MatchTitle.of(AMatch(id, Some("Boxing"), Some("  ")), "boxing"), "Boxing")
        assertEquals(MatchTitle.of(AMatch(id, Some("Boxing"), None), "boxing"), "Boxing")
    }

    test("a match that kept no name is titled by the engine's own, and never by its id") {
        val title = MatchTitle.of(AMatch(id, None, None), "boxing")
        assertEquals(title, "boxing")
        assert(!title.contains(id))
        assertEquals(MatchTitle.of(AMatch(id, None, Some("Rematch")), "boxing"), "boxing — Rematch")
    }
}
