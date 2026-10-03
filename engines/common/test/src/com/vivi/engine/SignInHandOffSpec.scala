package com.vivi.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite

/** Taking over the session matchmaker's UI hands a play page in its url fragment — `takeHandOff` in
  * [[SignIn.authScript]].
  *
  * The script is the page's own JavaScript, so it is run as it is, in Node, with the address bar, its history and
  * `sessionStorage` stood in for. What is checked is what a mistake here would quietly do: leave the credential in the
  * address bar, store something other than the token handed over, keep a session that the link said to replace, or
  * touch a fragment that was never a hand-off. Matchmaker's half — what it puts there — is `HandOffSpec` in the UI's
  * tests, which writes the same two names, `idToken` and `refreshToken`.
  *
  * Node is what the UI's own tests already run in. A machine without it fails here rather than skipping, since a
  * skipped check of a credential's handling is one nobody notices is missing.
  */
class SignInHandOffSpec extends FunSuite {

    private val signIn = new SignIn("stratego")
    private val path = "/matches/m-1/play"
    private val search = "?as=alice"

    /** What the page left behind: `sessionStorage`, and every `history.replaceState` it made. */
    private case class After(storage: Map[String, String], replaced: Seq[(ujson.Value, String)])

    /** Loads the page's sign-in script with `hash` — a JavaScript expression — as `location.hash`, and with `stored`
      * already in `sessionStorage`.
      */
    private def load(hash: String, stored: Map[String, String] = Map.empty): After = {
        val program =
            s"""const store = new Map(Object.entries(${ujson.write(
                  ujson.Obj.from(stored.view.mapValues(ujson.Str(_)))
                )}));
globalThis.sessionStorage = {
  getItem: k => store.has(k) ? store.get(k) : null,
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: k => store.delete(k)
};
const replaced = [];
globalThis.location = { hash: $hash, pathname: "$path", search: "$search" };
globalThis.history = { state: { kept: true }, replaceState: (state, title, url) => replaced.push([state, url]) };
${signIn.authScript(None)}
console.log(JSON.stringify({ storage: Object.fromEntries(store), replaced }));
"""
        val file = Files.createTempFile("signin-handoff", ".js")
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
            val json = ujson.read(output)
            After(
              json("storage").obj.view.mapValues(_.str).toMap,
              json("replaced").arr.toSeq.map(r => (r(0), r(1).str))
            )
        } finally Files.deleteIfExists(file)
    }

    /** A fragment written the way matchmaker's UI writes it, by `URLSearchParams`. */
    private def handedHash(params: (String, String)*): String =
        "\"#\" + new URLSearchParams(" + ujson.write(ujson.Obj.from(params.map((k, v) => k -> ujson.Str(v)))) +
            ").toString()"

    test("a handed-over session is stored, and the fragment is gone from the address bar") {
        val after = load(handedHash("idToken" -> "id-1", "refreshToken" -> "r-1"))
        assertEquals(after.storage, Map("stratego.idToken" -> "id-1", "stratego.refreshToken" -> "r-1"))
        // The path and the query stay — `?as=` is who the player is locally — and only the fragment goes.
        assertEquals(after.replaced.map(_._2), Seq(path + search))
        assertEquals(after.replaced.map(_._1), Seq(ujson.Obj("kept" -> true)))
    }

    // Whatever base64 can hold — `+`, `/`, `=` — and worse besides, read back as exactly what was handed.
    test("tokens are read back exactly as they were encoded") {
        val id = "eyJ+a/b=.c_d-e"
        val refresh = "x&y=z+w/v==%20 q"
        val after = load(handedHash("idToken" -> id, "refreshToken" -> refresh))
        assertEquals(after.storage.get("stratego.idToken"), Some(id))
        assertEquals(after.storage.get("stratego.refreshToken"), Some(refresh))
    }

    test("the session the tab held is replaced, not merged with") {
        val held = Map(
          "stratego.idToken" -> "someone-else",
          "stratego.accessToken" -> "their-access",
          "stratego.refreshToken" -> "their-refresh",
          "stratego.pkceVerifier" -> "v",
          "stratego.authState" -> "s"
        )
        val after = load(handedHash("idToken" -> "id-1"), held)
        assertEquals(after.storage, Map("stratego.idToken" -> "id-1"))
    }

    test("a refresh token on its own is enough to hand over") {
        val after = load(handedHash("refreshToken" -> "r-1"))
        assertEquals(after.storage, Map("stratego.refreshToken" -> "r-1"))
        assertEquals(after.replaced.size, 1)
    }

    test("a fragment that is not a hand-off is left where it is, and so is the session") {
        val held = Map("stratego.idToken" -> "mine")
        val after = load("\"#rules\"", held)
        assertEquals(after.storage, held)
        assertEquals(after.replaced, Seq.empty)
    }

    test("no fragment changes nothing") {
        val held = Map("stratego.idToken" -> "mine", "stratego.refreshToken" -> "r")
        val after = load("\"\"", held)
        assertEquals(after.storage, held)
        assertEquals(after.replaced, Seq.empty)
    }
}
