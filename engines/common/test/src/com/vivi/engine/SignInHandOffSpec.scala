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
  * touch a fragment that was never a hand-off. Then the line that says whose account the tab is signed in to, which is
  * what lets a player notice a hand-off made with someone else's tokens. Matchmaker's half — what it puts there — is
  * `HandOffSpec` in the UI's tests, which writes the same two names, `idToken` and `refreshToken`.
  *
  * Node is what the UI's own tests already run in. A machine without it fails here rather than skipping, since a
  * skipped check of a credential's handling is one nobody notices is missing.
  */
class SignInHandOffSpec extends FunSuite {

    private val signIn = new SignIn("stratego")
    private val path = "/matches/m-1/play"
    private val search = "?as=alice"

    private val login = LoginConfig(
      hostedLoginUrl = "https://auth.example.com",
      clientId = "client",
      redirectUri = "https://engine.example.com/auth/callback",
      region = "us-west-2"
    )

    /** What the page left behind: `sessionStorage`, every `history.replaceState` it made, the signed-in line — `None`
      * while hidden — and whether it reloaded.
      */
    private case class After(
        storage: Map[String, String],
        replaced: Seq[(ujson.Value, String)],
        signedInAs: Option[String],
        isReloaded: Boolean,
        fetched: Seq[Fetched] = Nil,
        asked: Seq[String] = Nil,
        problem: Option[String] = None
    )

    /** A request the page made: where, with what body and cache mode, and how many address-bar replacements came first.
      */
    private case class Fetched(url: String, body: String, cache: Option[String], replacedBefore: Int)

    /** Loads the page's sign-in script with `hash` — a JavaScript expression — as `location.hash`, and with `stored`
      * already in `sessionStorage`. The page has the `#signed-in-as` line; `isSignOutPressed` presses its button once
      * the script has run. `then` is JavaScript run after that, and awaited, before what is left is read. `hasForm`
      * loads the sign-in form's script too, with a `#signin` box to draw into, so `then` can sign in on the page.
      */
    private def load(
        hash: String,
        stored: Map[String, String] = Map.empty,
        login: Option[LoginConfig] = None,
        isSignOutPressed: Boolean = false,
        `then`: String = "",
        hasForm: Boolean = false,
        handOffAnswer: String = "null",
        isSwitchConfirmed: Boolean = true,
        path: String = path
    ): After = {
        val form =
            if (hasForm) s"""${HtmlText.escapeScript}
${signIn.signInScript}
function signedIn() {}"""
            else ""

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
let reloaded = false;
// What the page posted, with how many replaceStates had been made by then; and what it asked.
const fetched = [];
const asked = [];
globalThis.confirm = question => { asked.push(question); return $isSwitchConfirmed; };
globalThis.fetch = (url, init) => {
  fetched.push({ url, init, replacedBefore: replaced.length });
  const answer = $handOffAnswer;
  return answer === null ? Promise.reject(new TypeError("no answer")) : Promise.resolve(answer);
};
globalThis.location = { hash: $hash, pathname: "$path", search: "$search", reload: () => { reloaded = true; } };
globalThis.history = { state: { kept: true }, replaceState: (state, title, url) => replaced.push([state, url]) };
const listeners = {};
const elements = {
  "signed-in-as": { hidden: true },
  "signed-in-who": { textContent: "" },
  "sign-out": { addEventListener: (type, f) => { listeners[type] = f; } },
  "signin": {
    innerHTML: "",
    dataset: {},
    contains: () => false,
    querySelector: () => control()
  }
};
// The form's own inputs and buttons, when it is loaded.
const control = () => ({ addEventListener: () => {}, focus: () => {}, value: "" });
globalThis.document = { activeElement: null, getElementById: id => elements[id] || ($hasForm ? control() : null) };
${signIn.authScript(login)}
$form
if ($isSignOutPressed) listeners.click();
(async () => {
  while (handingOff) await handingOff;
  ${`then`}
  const line = elements["signed-in-as"].hidden ? null : elements["signed-in-who"].textContent;
  const problemShown = typeof problem === "undefined" ? null : problem;
  console.log(JSON.stringify({ storage: Object.fromEntries(store), replaced, line, reloaded, fetched, asked,
    problem: problemShown }));
})();
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
              storage = json("storage").obj.view.mapValues(_.str).toMap,
              replaced = json("replaced").arr.toSeq.map(r => (r(0), r(1).str)),
              signedInAs = json("line").strOpt,
              isReloaded = json("reloaded").bool,
              fetched = json("fetched").arr.toSeq.map(f =>
                  Fetched(
                    url = f("url").str,
                    body = f("init")("body").str,
                    cache = f("init").obj.get("cache").map(_.str),
                    replacedBefore = f("replacedBefore").num.toInt
                  )
              ),
              asked = json("asked").arr.toSeq.map(_.str),
              problem = json("problem").strOpt
            )
        } finally Files.deleteIfExists(file)
    }

    /** A fragment written the way matchmaker's UI writes it, by `URLSearchParams`. */
    private def handedHash(params: (String, String)*): String =
        "\"#\" + new URLSearchParams(" + ujson.write(ujson.Obj.from(params.map((k, v) => k -> ujson.Str(v)))) +
            ").toString()"

    /** The engine's server answering `POST api/handoff` with `status` and `body`, as `fetch` resolves. */
    private def answered(status: Int, body: ujson.Value): String =
        s"""({ ok: ${status >= 200 && status < 300}, status: $status, json: async () => (${ujson.write(body)}) })"""

    private def tokens(idToken: String, refreshToken: Option[String] = None): String =
        answered(
          200,
          ujson.Obj.from(Seq("idToken" -> ujson.Str(idToken)) ++ refreshToken.map(r => "refreshToken" -> ujson.Str(r)))
        )

    test("a handed-over code is redeemed by this engine's server, and its tokens stored") {
        val after = load(handedHash("handoff" -> "c0de"), handOffAnswer = tokens("id-1", Some("r-1")))
        assertEquals(after.storage, Map("stratego.idToken" -> "id-1", "stratego.refreshToken" -> "r-1"))
        assertEquals(
          after.fetched.map(f => (f.url, ujson.read(f.body))),
          Seq(("/api/handoff", ujson.Obj("code" -> "c0de")))
        )
        assertEquals(after.fetched.map(_.cache), Seq(Some("no-store")))
    }

    test("the fragment is gone from the address bar before the code is sent") {
        val after = load(handedHash("handoff" -> "c0de"), handOffAnswer = tokens("id-1"))
        // The path and the query stay — `?as=` is who the player is locally — and only the fragment goes.
        assertEquals(after.replaced.map(_._2), Seq(path + search))
        assertEquals(after.replaced.map(_._1), Seq(ujson.Obj("kept" -> true)))
        assertEquals(after.fetched.map(_.replacedBefore), Seq(1))
    }

    test("behind a stage prefix, the code goes to the engine's root under it") {
        val after =
            load(handedHash("handoff" -> "c0de"), handOffAnswer = tokens("id-1"), path = "/dev/matches/m-1/play")
        assertEquals(after.fetched.map(_.url), Seq("/dev/api/handoff"))
    }

    // Whatever base64url can hold, and worse besides, sent exactly as it was handed.
    test("the code is sent exactly as it was encoded") {
        val code = "a+b/c=d&e f"
        val after = load(handedHash("handoff" -> code), handOffAnswer = tokens("id-1"))
        assertEquals(after.fetched.map(f => ujson.read(f.body)("code").str), Seq(code))
    }

    test("the session the tab held is replaced, not merged with") {
        val held = Map(
          "stratego.idToken" -> "not-a-jwt",
          "stratego.accessToken" -> "their-access",
          "stratego.refreshToken" -> "their-refresh",
          "stratego.pkceVerifier" -> "v",
          "stratego.authState" -> "s"
        )
        val after = load(handedHash("handoff" -> "c0de"), held, handOffAnswer = tokens("id-1"))
        assertEquals(after.storage, Map("stratego.idToken" -> "id-1"))
        assertEquals(after.asked, Seq.empty)
    }

    test("a code the server does not redeem leaves the tab as it was, and the form says why") {
        val held = Map("stratego.refreshToken" -> "mine")
        val after = load(
          handedHash("handoff" -> "spent"),
          held,
          login = Some(login),
          hasForm = true,
          handOffAnswer = answered(404, ujson.Obj("error" -> "that sign-in code is spent, expired or unknown"))
        )
        assertEquals(after.storage, held)
        assertEquals(after.problem, Some(ErrorText.signIn.handOffFailed))
        // Out of the address bar all the same.
        assertEquals(after.replaced.map(_._2), Seq(path + search))
    }

    test("an engine that cannot be reached leaves the tab signed out") {
        val after = load(handedHash("handoff" -> "c0de"), login = Some(login), hasForm = true)
        assertEquals(after.storage, Map.empty[String, String])
        assertEquals(after.signedInAs, None)
        assertEquals(after.problem, Some(ErrorText.signIn.handOffFailed))
    }

    test("while the code is out the tab counts as signed in, and asking for a token waits for it") {
        val id = idToken("email" -> "alice@example.com", "exp" -> inAnHour)
        val waited = load(
          "\"\"",
          login = Some(login),
          `then` = s"""
            let answer;
            globalThis.fetch = () => new Promise(resolve => { answer = resolve; });
            handingOff = redeemHandOff("c0de").finally(() => { handingOff = null; renderSignedInAs(); });
            renderSignedInAs();
            if (!isSignedIn()) throw new Error("not signed in while handing off");
            if (elements["signed-in-who"].textContent !== "Signing in…") throw new Error("not shown as signing in");
            const token = freshIdToken();
            answer(${tokens(id)});
            if (await token !== "$id") throw new Error("the token was asked for before it was stored");
          """
        )
        assertEquals(waited.storage.get("stratego.idToken"), Some(id))
        assertEquals(waited.signedInAs, Some("Signed in as alice@example.com — not you?"))
    }

    test("a tab signed in as someone else asks before switching, and keeps its session if told not to") {
        val alice = idToken("sub" -> "a", "email" -> "alice@example.com", "exp" -> inAnHour)
        val bob = idToken("sub" -> "b", "email" -> "bob@example.com", "exp" -> inAnHour)
        val held = Map("stratego.idToken" -> alice, "stratego.refreshToken" -> "r-alice")
        val kept = load(
          handedHash("handoff" -> "c0de"),
          held,
          handOffAnswer = tokens(bob, Some("r-bob")),
          isSwitchConfirmed = false
        )
        assertEquals(kept.asked, Seq("Signed in here as alice@example.com. Switch to bob@example.com?"))
        assertEquals(kept.storage, held)

        val switched = load(handedHash("handoff" -> "c0de"), held, handOffAnswer = tokens(bob, Some("r-bob")))
        assertEquals(switched.storage, Map("stratego.idToken" -> bob, "stratego.refreshToken" -> "r-bob"))
    }

    test("a hand-off for the account the tab already holds does not ask") {
        val first = idToken("sub" -> "a", "exp" -> inAnHour)
        val again = idToken("sub" -> "a", "exp" -> inAnHour, "iat" -> ujson.Num(2))
        val after =
            load(handedHash("handoff" -> "c0de"), Map("stratego.idToken" -> first), handOffAnswer = tokens(again))
        assertEquals(after.asked, Seq.empty)
        assertEquals(after.storage, Map("stratego.idToken" -> again))
    }

    test("tokens in the fragment, as matchmaker used to hand them, are not taken") {
        val after = load(handedHash("idToken" -> "id-1", "refreshToken" -> "r-1"))
        assertEquals(after.storage, Map.empty[String, String])
        assertEquals(after.fetched, Seq.empty)
        assertEquals(after.replaced, Seq.empty)
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

    /** An ID token whose claims are `claims`, unsigned: the page reads it and never checks the signature. */
    private def idToken(claims: (String, ujson.Value)*): String = {
        def part(json: ujson.Value) =
            java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(ujson.write(json).getBytes(UTF_8))
        part(ujson.Obj("alg" -> "none")) + "." + part(ujson.Obj.from(claims)) + ".sig"
    }

    private val inAnHour = ujson.Num((System.currentTimeMillis() / 1000 + 3600).toDouble)

    test("a handed-over session says whose account it is") {
        val id = idToken("email" -> "mallory@example.com", "exp" -> inAnHour)
        val after = load(handedHash("handoff" -> "c0de"), login = Some(login), handOffAnswer = tokens(id, Some("r-1")))
        assertEquals(after.signedInAs, Some("Signed in as mallory@example.com — not you?"))
    }

    // An address beyond ASCII is read as UTF-8, not as the bytes `atob` gives.
    test("the address is read as UTF-8") {
        val id = idToken("email" -> "zoë@example.com", "exp" -> inAnHour)
        val after = load("\"\"", Map("stratego.idToken" -> id), login = Some(login))
        assertEquals(after.signedInAs, Some("Signed in as zoë@example.com — not you?"))
    }

    // The address does not change with a refresh, so an expired ID token beside a refresh token still names it.
    test("an expired ID token still names the account while a refresh token holds the session") {
        val id = idToken("email" -> "alice@example.com", "exp" -> 1)
        val after = load("\"\"", Map("stratego.idToken" -> id, "stratego.refreshToken" -> "r-1"), login = Some(login))
        assertEquals(after.signedInAs, Some("Signed in as alice@example.com — not you?"))
    }

    test("a session whose token names no address is still shown as signed in") {
        val after = load("\"\"", Map("stratego.refreshToken" -> "r-1"), login = Some(login))
        assertEquals(after.signedInAs, Some("Signed in."))
    }

    test("the line is hidden with no session") {
        assertEquals(load("\"\"", login = Some(login)).signedInAs, None)
    }

    // A page with no login is one where `?as=` says who the player is, and there is no session to name.
    test("the line is hidden on a page with no login") {
        assertEquals(load("\"\"", Map("stratego.refreshToken" -> "r-1")).signedInAs, None)
    }

    test("signing out drops the session and reloads the page") {
        val id = idToken("email" -> "mallory@example.com", "exp" -> inAnHour)
        val after = load(
          "\"\"",
          Map("stratego.idToken" -> id, "stratego.refreshToken" -> "r-1"),
          login = Some(login),
          isSignOutPressed = true
        )
        assertEquals(after.storage, Map.empty[String, String])
        assertEquals(after.signedInAs, None)
        assert(after.isReloaded)
    }

    // A live region is heard only if it is there before its text, so a line that appears after the page has loaded
    // is unhidden first and written a moment later.
    test("a sign-in after the page has loaded shows the line before saying whose account it is") {
        val id = idToken("email" -> "alice@example.com", "exp" -> inAnHour)
        val after = load(
          "\"\"",
          login = Some(login),
          `then` = s"""
            document.readyState = "complete";
            storeTokens({ idToken: "$id" });
            if (elements["signed-in-as"].hidden) throw new Error("the line is still hidden");
            if (elements["signed-in-who"].textContent !== "") throw new Error("written as it was unhidden");
            await new Promise(resolve => setTimeout(resolve, 200));
          """
        )
        assertEquals(after.signedInAs, Some("Signed in as alice@example.com — not you?"))
    }

    // The reload is not instant, and Cognito may answer a refresh begun before Sign out was pressed in the meantime.
    test("a refresh that Cognito answers after signing out does not sign the tab back in") {
        val fresh = idToken("email" -> "alice@example.com", "exp" -> inAnHour)
        val after = load(
          "\"\"",
          stored = Map("stratego.refreshToken" -> "r-1"),
          login = Some(login),
          `then` = s"""
            let answer;
            globalThis.fetch = () => new Promise(resolve => { answer = resolve; });
            const refreshing = freshIdToken();
            listeners.click();
            answer({ ok: true, text: async () => JSON.stringify({ AuthenticationResult: { IdToken: "$fresh" } }) });
            await refreshing;
          """
        )
        assertEquals(after.storage, Map.empty[String, String])
        assertEquals(after.signedInAs, None)
        assert(after.isReloaded)
    }

    // The same window for a hand-off: Sign out pressed while the code is out, and the engine answering before the
    // reload replaces the page. Storing the handed tokens then would bring the tab back signed in as that account.
    test("a hand-off answered after signing out does not sign the tab in") {
        val mallory = idToken("email" -> "mallory@example.com", "exp" -> inAnHour)
        val after = load(
          "\"\"",
          login = Some(login),
          `then` = s"""
            let answer;
            globalThis.fetch = () => new Promise(resolve => { answer = resolve; });
            handingOff = redeemHandOff("c0de").finally(() => { handingOff = null; renderSignedInAs(); });
            listeners.click();
            answer(${tokens(mallory, Some("r-mallory"))});
            await handingOff;
          """
        )
        assertEquals(after.storage, Map.empty[String, String])
        assertEquals(after.signedInAs, None)
        assert(after.isReloaded)
    }

    test("a hand-off answered after signing in on the page does not replace that sign-in") {
        val mallory = idToken("email" -> "mallory@example.com", "exp" -> inAnHour)
        val after = load(
          "\"\"",
          login = Some(login),
          hasForm = true,
          `then` = s"""
            let answer;
            globalThis.fetch = () => new Promise(resolve => { answer = resolve; });
            handingOff = redeemHandOff("c0de").finally(() => { handingOff = null; renderSignedInAs(); });
            succeed({ idToken: "$bob", refreshToken: "r-bob" });
            answer(${tokens(mallory, Some("r-mallory"))});
            await handingOff;
          """
        )
        assertEquals(after.storage, Map("stratego.idToken" -> bob, "stratego.refreshToken" -> "r-bob"))
        assertEquals(after.asked, Seq.empty)
    }

    /** A refresh begun with the session held before an on-page sign-in, still out when the player signs in as Bob, and
      * answered with `answer` — a JavaScript `fetch` response — after that.
      */
    private def signedInDuringRefresh(answer: String): After =
        load(
          "\"\"",
          stored = Map("stratego.refreshToken" -> "r-old"),
          login = Some(login),
          hasForm = true,
          `then` = s"""
            let answer;
            globalThis.fetch = () => new Promise(resolve => { answer = resolve; });
            const refreshing = freshIdToken();
            succeed({ idToken: "$bob", refreshToken: "r-bob" });
            answer($answer);
            await refreshing;
          """
        )

    private val bob = idToken("email" -> "bob@example.com", "exp" -> inAnHour)

    // Cognito unreachable and then back: the form was shown while a refresh of the old session was still out.
    test("a refresh of the old session answered after signing in on the page does not overwrite the new one") {
        val old = idToken("email" -> "mallory@example.com", "exp" -> inAnHour)
        val after = signedInDuringRefresh(
          s"""{ ok: true, text: async () => JSON.stringify({ AuthenticationResult: { IdToken: "$old" } }) }"""
        )
        assertEquals(after.storage, Map("stratego.idToken" -> bob, "stratego.refreshToken" -> "r-bob"))
        assertEquals(after.signedInAs, Some("Signed in as bob@example.com — not you?"))
    }

    test("a refusal of the old session's refresh answered after signing in on the page does not sign the tab out") {
        val after = signedInDuringRefresh(
          """{ ok: false, status: 400, text: async () => JSON.stringify({ __type: "NotAuthorizedException" }) }"""
        )
        assertEquals(after.storage, Map("stratego.idToken" -> bob, "stratego.refreshToken" -> "r-bob"))
        assertEquals(after.signedInAs, Some("Signed in as bob@example.com — not you?"))
    }
}
