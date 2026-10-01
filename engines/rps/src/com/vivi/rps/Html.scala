package com.vivi.rps

import upickle.default.write
import com.vivi.engine.{LoginConfig, PlayLive, SignIn}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page.
  *
  * A self-contained document with no assets, because the engine has no static hosting and a page that needs a second
  * request needs somewhere to serve it from. When the viewer already has a seat, the state is inlined into the first
  * render so the board is right before any script runs; otherwise the page is a shell that signs the player in and then
  * fetches it.
  *
  * Nothing the page is given discloses a throw the server would not disclose: what is hidden is hidden in
  * `Engine.stateOf`, not here. A page that filtered the answer it rendered would be hiding it from the one person who
  * can open the network tab.
  *
  * The sign-in on it is [[com.vivi.engine.SignIn]], the same on every engine, which also serves the page the hosted
  * login redirects back to.
  */
object Html {

    /** The sign-in this page offers, keeping its tokens under the game's name as it always has. */
    val signIn: SignIn = SignIn("rps")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("rps")

    def board(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val heading = state match {
            case Some(s) if s.completed => outcome(s)
            case Some(s)                => waiting(s)
            case None                   => "sign in to play"
        }

        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>rock · paper · scissors — ${escape(matchId)}</title>
<style>
  /* --error is 6.3:1 on the light page and 7.8:1 on the dark one; crimson, which it replaces, was 3.6:1
     in dark mode, under the 4.5:1 normal text needs. */
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; } }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { text-align: center; padding: 2rem 1rem; max-width: 30rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  #status { font-size: 1.5rem; font-weight: 700; margin: 0 0 1.25rem; min-height: 2rem; }
  #throws { display: flex; gap: .75rem; justify-content: center; flex-wrap: wrap; margin: 0 0 1rem; padding: 0; border: 0; }
  /* 44px minimum in both directions: these are the only controls on the page, and a throw made
     by mistake on a phone cannot be taken back. */
  button.throw { font: 1rem/1 ui-sans-serif, system-ui, sans-serif; min-width: 7rem; min-height: 44px;
                 padding: .75rem 1rem; border-radius: 8px; border: 1px solid var(--line);
                 background: var(--paper); color: var(--ink); cursor: pointer; }
  button.throw .glyph { display: block; font-size: 2rem; line-height: 1.2; }
  button.throw:disabled { cursor: default; opacity: .45; }
  button.throw[aria-pressed="true"] { border-color: seagreen; background: color-mix(in srgb, var(--paper) 70%, seagreen); opacity: 1; }
  button.throw:not(:disabled):hover { background: color-mix(in srgb, var(--paper) 85%, var(--ink)); }
  :focus-visible { outline: 3px solid seagreen; outline-offset: 2px; }
${SignIn.css}
${PlayLive.css}
  #seats { margin-top: 1.25rem; font-size: .875rem; opacity: .8; }
  #seats div { margin: .125rem 0; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>rock · paper · scissors</h1>
  <!-- The result arrives while the page is idle rather than in answer to anything the player
       just did, so it is announced: a screen-reader user must not have to go looking for it. -->
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  <fieldset id="throws" aria-label="your throw"></fieldset>
  <!-- The sign-in form, rendered by renderSignIn() and shown whenever there is a login to
       offer and no seat to show for it. -->
  <div id="signin" hidden></div>
  <div id="seats" aria-label="seats"></div>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>
</main>
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}

  const publicView = $publicView;
  // Urls are derived from this page's own, not built from a base: behind API Gateway the path
  // carries a stage prefix, and a page that assumed "/matches/..." would 404 there.
  const here = location.pathname.replace(new RegExp("/(play|board)$$"), "");
  // The page's own query goes along too: in the trusted local mode `?as=` is who the player is,
  // and a fetch without it would be nobody's. Deployed, there is no query and this adds nothing.
  const query = location.search;
  const stateUrl = (publicView ? here + "/board/state" : here + "/state") + query;
  const movesUrl = here + "/moves" + query;

  // Present when the server already knew whose seat this is; null when the player has yet to
  // sign in, in which case the first fetch below fills it.
  let state = ${state.map(s => scriptSafe(write(s))).getOrElse("null")};
  // Set by a 403: signed in, but not to a seat in this match. See `send`.
  let noSeat = false;

  const shapes = [["Rock", "✊"], ["Paper", "✋"], ["Scissors", "✌️"]];
  const throws = document.getElementById("throws");
  const buttons = shapes.map(([name, glyph]) => {
    const b = document.createElement("button");
    b.className = "throw";
    b.type = "button";
    b.innerHTML = '<span class="glyph" aria-hidden="true">' + glyph + '</span>' + name;
    b.addEventListener("click", () => play(name));
    throws.appendChild(b);
    return b;
  });

  const signin = document.getElementById("signin");
  renderSignIn();

  function render() {
    // Thrown already, or the match is over, or this viewer has no seat to throw with: the server
    // checks all of it again, and this only keeps the page from asking for a refusal.
    const canThrow = !!(state && state.you && !state.yourThrow && !state.completed);
    shapes.forEach(([name], i) => {
      buttons[i].disabled = !canThrow;
      buttons[i].setAttribute("aria-pressed", String(mine() === name));
    });
    throws.hidden = !!(state && !state.you);

    document.getElementById("status").textContent = describe();

    // Offered whenever there is a login to start and no seat to show for it — including after a
    // token expires mid-match, which is what turns a 401 back into a button.
    signin.hidden = !login || noSeat || (state && state.you);

    document.getElementById("seats").innerHTML = state
      ? state.players.map(p => {
          const you = p.side === (state.you || "") ? " (you)" : "";
          // A throw is shown only when the server sent one, which it does only once the match has
          // resolved. Until then all anyone learns is that a player has moved.
          const what = p.shape ? " · threw " + p.shape : (p.thrown ? " · thrown" : " · waiting");
          return "<div>" + escapeHtml(p.side) + " · " + escapeHtml(p.cognitoId) + you + what + "</div>";
        }).join("")
      : "";
  }

  /* What this viewer threw, if anything. `yourThrow` is the seat's own throw and is the one thing
   * a player may see before the match resolves. */
  function mine() { return state ? state.yourThrow : null; }

  function describe() {
    if (!state) return login ? "sign in to play" : "not your match";
    if (state.completed) {
      if (state.draw) return "drawn";
      if (!state.you) return state.winner + " wins";
      return state.winner === state.you ? "you win" : "you lose";
    }
    if (state.you && !state.yourThrow) return "throw";
    // Both sides are pending from the start, so "waiting" here names whoever is left — which is
    // the other player when this viewer has thrown, and both of them on the public board.
    return "waiting for " + state.waitingFor.join(" and ");
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  async function play(shape) {
    show("");
    const ticket = ask();
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ shape }) });
    if (!response) return;
    const answer = await response.json();
    // Dropped if something asked for since has been shown, refusal or not: that already says more.
    if (overtaken(ticket)) return;
    if (!response.ok) { show(answer.error || response.statusText); return; }
    latest(ticket);
    state = answer;
    // An earlier move's refusal is no longer the news.
    show("");
    render();
  }

  async function refresh() {
    const ticket = ask();
    const response = await send(stateUrl, {});
    if (!response || !response.ok) return;
    const answer = await response.json();
    if (latest(ticket)) { state = answer; render(); }
  }

  /* Every call carries the ID token when there is one. A 401 means the session is over rather
   * than the move being wrong, so the token is dropped and the page falls back to offering a
   * sign-in — a stale token must not leave the page looking merely broken. */
  async function send(url, init) {
    const token = await freshIdToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      // A 401 is "who are you?": the session is over or never began, so it is dropped and a sign-in
      // offered. A 403 is "not yours": signed in, just not to a seat here. The session is kept — it
      // is good for every match the player is in — and the page stops asking, since another sign-in
      // would be the same player refused the same way.
      if (response.status === 401) {
        if (token) clearSession();
        state = null;
        render();
        show(login ? "sign in to play this match" : "say who you are with ?as=<cognito sub>");
        return null;
      }
      if (response.status === 403) {
        noSeat = true;
        state = null;
        render();
        show("you have no seat in this match");
        return null;
      }
      return response;
    } catch (e) {
      show("could not reach the engine");
      return null;
    }
  }

  /* Called once the sign-in has tokens in hand: there is a seat to fetch now, and the board is
   * still showing the shell it was served. */
  function signedIn() { refresh(); }

  /* Whether there is any point asking for the state. With a login configured and no session, the
   * answer is a 401 — and asking every two seconds scrolls the console with them and, worse, kept
   * rebuilding the sign-in form under the player's cursor. Public boards and the trusted local mode
   * have no session to wait for and are fetched as before. */
  function mayFetch() { return !noSeat && (!login || publicView || isSignedIn()); }

  render();
  if (!state && mayFetch()) refresh();
  // Kept current to the end — polled, or with Play Live told — and for the same reason as
  // tic-tac-toe's board: the other player's throw arrives while this page is doing nothing.
  keepCurrent(refresh, () => mayFetch() && (!state || !state.completed));
</script>
</body>
</html>
"""
    }

    private def outcome(state: Protocol.StateResponse): String =
        if (state.draw) "drawn" else state.winner.map(w => s"$w wins").getOrElse("over")

    /* The heading of an unresolved match, server-rendered: "throw" while this viewer still has one
     * to make, and otherwise who is being waited for. */
    private def waiting(state: Protocol.StateResponse): String =
        if (state.you.isDefined && state.yourThrow.isEmpty) "throw"
        else s"waiting for ${state.waitingFor.mkString(" and ")}"
}
