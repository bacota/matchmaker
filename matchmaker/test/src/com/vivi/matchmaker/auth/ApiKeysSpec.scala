package com.vivi.matchmaker.auth

import munit.FunSuite

class ApiKeysSpec extends FunSuite {

  test("parses name=key entries, and tolerates spacing and a trailing comma") {
    val keys = ApiKeys.parse(Some(" tictactoe=abc , chess=def ,"))
    assertEquals(keys.keyFor("tictactoe"), Some("abc"))
    assertEquals(keys.keyFor("chess"), Some("def"))
    assertEquals(keys.keyFor("go"), None)
  }

  test("a key may contain '=' — only the first one separates it from its name") {
    assertEquals(ApiKeys.parse(Some("tictactoe=YWJjZA==")).keyFor("tictactoe"), Some("YWJjZA=="))
  }

  test("nothing configured is nobody trusted, rather than everybody") {
    assert(ApiKeys.parse(None).isEmpty)
    assert(ApiKeys.parse(Some("")).isEmpty)
    assertEquals(ApiKeys.parse(None).nameOf("anything"), None)
  }

  // Far more likely to be a key pasted without its name than a name meant to be keyless, and a
  // keyless name would be an entry that admits nobody while looking as though it admits someone.
  test("an entry with no name is a startup failure") {
    intercept[IllegalStateException](ApiKeys.parse(Some("loose-key")))
  }

  test("a presented key resolves to the name it was filed under, and nothing else does") {
    val keys = ApiKeys(Map("tictactoe" -> "abc", "chess" -> "def"))
    assertEquals(keys.nameOf("abc"), Some("tictactoe"))
    assertEquals(keys.nameOf("def"), Some("chess"))
    assertEquals(keys.nameOf("ab"), None)
    assertEquals(keys.nameOf("abcd"), None)
    assertEquals(keys.nameOf(""), None)
  }

  test("a name is not a key: only the secret half admits anyone") {
    assertEquals(ApiKeys(Map("tictactoe" -> "abc")).nameOf("tictactoe"), None)
  }
}
