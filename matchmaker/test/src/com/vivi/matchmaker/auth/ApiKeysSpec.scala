package com.vivi.matchmaker.auth

import munit.FunSuite

class ApiKeysSpec extends FunSuite {

    test("a key is found by the name it is filed under") {
        val keys = ApiKeys(Map("tictactoe" -> "abc", "chess" -> "def"))
        assertEquals(keys.keyFor("tictactoe"), Some("abc"))
        assertEquals(keys.keyFor("chess"), Some("def"))
        assertEquals(keys.keyFor("go"), None)
    }

    test("nothing stored is nobody trusted, rather than everybody") {
        assert(ApiKeys.empty.isEmpty)
        assertEquals(ApiKeys.empty.nameOf("anything"), None)
    }

    test("a presented key resolves to the name it was filed under, and nothing else does") {
        val keys = ApiKeys(Map("tictactoe" -> "abc", "chess" -> "def"))
        assertEquals(keys.nameOf("abc"), Some("tictactoe"))
        assertEquals(keys.nameOf("def"), Some("chess"))
        assertEquals(keys.nameOf("ab"), None)
        assertEquals(keys.nameOf("abcd"), None)
        assertEquals(keys.nameOf(""), None)
    }

    // Two games may share an engine identity, each with a key of its own; either key is that engine.
    test("one name may hold several keys, and each of them resolves to it") {
        val keys = ApiKeys(Seq("boxing" -> "abc", "boxing" -> "def"))
        assertEquals(keys.nameOf("abc"), Some("boxing"))
        assertEquals(keys.nameOf("def"), Some("boxing"))
    }

    test("a name is not a key: only the secret half admits anyone") {
        assertEquals(ApiKeys(Map("tictactoe" -> "abc")).nameOf("tictactoe"), None)
    }
}
