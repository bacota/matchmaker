package com.vivi.matchmaker.model

import munit.FunSuite

/** Which games a cross-game list may show, without a database. */
class GameScopeSpec extends FunSuite {

    private val a = GameId(1)
    private val b = GameId(2)

    test("narrowing to no game leaves the scope as it is") {
        assertEquals(GameScope.All.narrowedTo(None), GameScope.All)
        assertEquals(GameScope.Only(Set(a, b)).narrowedTo(None), GameScope.Only(Set(a, b)))
    }

    test("narrowing every game to one gives that one") {
        assertEquals(GameScope.All.narrowedTo(Some(a)), GameScope.only(a))
    }

    test("narrowing some games to one of them gives that one") {
        assertEquals(GameScope.Only(Set(a, b)).narrowedTo(Some(a)), GameScope.only(a))
    }

    test("narrowing some games to one outside them gives none") {
        assertEquals(GameScope.only(a).narrowedTo(Some(b)), GameScope.Only(Set.empty))
    }

    test("every game shows any game") {
        assert(GameScope.All.isShowing(a))
    }

    test("some games show only those") {
        assert(GameScope.only(a).isShowing(a))
        assert(!GameScope.only(a).isShowing(b))
        assert(!GameScope.Only(Set.empty).isShowing(a))
    }
}
