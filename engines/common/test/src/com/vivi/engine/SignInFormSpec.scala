package com.vivi.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite

/** Drawing the sign-in form — `renderSignIn` in [[SignIn.signInScript]] — as a page does, with nothing of the page's
  * beside it but [[HtmlText.escapeScript]], which every page inlines first.
  *
  * The form writes text a player typed, and text Cognito sent back, into `innerHTML`. So the script is run here as it
  * is, in Node, with a stand-in for the page's `#signin` and text in every place the form writes some that would be
  * markup if it were not escaped. That each page does carry the helper is `RoutesContract`'s, for every engine.
  *
  * Node is required, as for [[SignInHandOffSpec]]: a machine without it fails here rather than skipping.
  */
class SignInFormSpec extends FunSuite {

    private val signIn = new SignIn("wavinghands")

    private val login = LoginConfig(
      hostedLoginUrl = "https://auth.example.com",
      clientId = "client",
      redirectUri = "https://engine.example.com/auth/callback",
      region = "us-west-2"
    )

    private val markup = """<img src=x onerror="alert(1)">&'"""
    private val escaped = "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;&#39;"

    /** The `#signin` box's contents after `setUp` — JavaScript run once the scripts are loaded, with the form's own
      * state in scope — and one draw of the form.
      */
    private def drawn(setUp: String): String = {
        val program =
            s"""const listening = () => ({ addEventListener: () => {}, focus: () => {}, value: "" });
const box = { innerHTML: "", dataset: {}, contains: () => false, querySelector: () => listening() };
globalThis.sessionStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
globalThis.location = { hash: "", pathname: "/matches/m-1/play", search: "", href: "" };
globalThis.history = { state: null, replaceState: () => {} };
globalThis.document = {
  activeElement: null,
  getElementById: id => id === "signin" ? box : id === "signed-in-as" ? { hidden: true } : listening()
};
${HtmlText.escapeScript}
${signIn.authScript(Some(login))}
${signIn.signInScript}
$setUp
renderSignIn();
console.log(JSON.stringify({ html: box.innerHTML }));
"""
        val file = Files.createTempFile("signin-form", ".js")
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
            ujson.read(output)("html").str
        } finally Files.deleteIfExists(file)
    }

    test("the form draws with only the shared helper beside it") {
        assert(drawn(setUp = "").contains("""id="si-email""""))
    }

    test("the address typed and the problem shown are escaped") {
        val html = drawn(setUp = s"signInEmail = ${ujson.write(markup)}; problem = ${ujson.write(markup)};")
        assert(!html.contains(markup), html)
        assert(html.contains(s"""value="$escaped""""), html)
        assert(html.contains(s"""role="alert">$escaped</p>"""), html)
    }

    test("the address a code was sent to is escaped") {
        val html = drawn(setUp = s"""stage = { kind: "code", deliveredTo: ${ujson.write(markup)} };""")
        assert(!html.contains(markup), html)
        assert(html.contains(s"We sent a sign-in code to $escaped."), html)
    }
}
