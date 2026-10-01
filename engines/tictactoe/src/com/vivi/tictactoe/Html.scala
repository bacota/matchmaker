package com.vivi.tictactoe

import upickle.default.write
import com.vivi.engine.{LoginConfig, PlayLive, SignIn}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The board page.
  *
  * A self-contained document with no assets, because the engine has no static hosting and a page that needs a second
  * request needs somewhere to serve it from. When the viewer already has a seat, the state is inlined into the first
  * render so the board is right before any script runs; otherwise the page is a shell that signs the player in and then
  * fetches it.
  *
  * The sign-in on it is [[com.vivi.engine.SignIn]], the same on every engine, which also serves the page the hosted
  * login redirects back to.
  */
object Html {

    /** The sign-in this page offers, keeping its tokens under the game's name as it always has. */
    val signIn: SignIn = SignIn("tictactoe")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("tictactoe")

    def board(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val heading = state match {
            case Some(s) if s.completed => outcome(s)
            case Some(s)                => s"${s.turn.getOrElse("")} to move"
            case None                   => "sign in to play"
        }

        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>tic-tac-toe · ${escape(matchId)}</title>
<style>
  /* --error is 6.3:1 on the light page and 7.8:1 on the dark one; crimson, which it replaces, was 3.6:1
     in dark mode, under the 4.5:1 normal text needs. */
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; } }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { text-align: center; padding: 2rem 1rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  #status { font-size: 1.5rem; font-weight: 700; margin: 0 0 1.25rem; min-height: 2rem; }
  #grid { display: grid; grid-template-columns: repeat(3, 5.5rem); grid-template-rows: repeat(3, 5.5rem); gap: 4px;
          background: var(--line); border: 4px solid var(--line); border-radius: 8px; margin: 0 auto; }
  button.cell { font: 700 2.75rem/1 ui-monospace, monospace; color: var(--ink); background: var(--paper);
                border: 0; cursor: pointer; padding: 0; }
  button.cell:disabled { cursor: default; }
  button.cell:not(:disabled):hover { background: color-mix(in srgb, var(--paper) 85%, var(--ink)); }
  button.cell.win { background: color-mix(in srgb, var(--paper) 70%, seagreen); }
${SignIn.css}
${PlayLive.css}
  #seats { margin-top: 1.25rem; font-size: .875rem; opacity: .7; }
  #seats div { margin: .125rem 0; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>tic-tac-toe</h1>
  <!-- Announced: the other player's move, and the result, arrive while this page is idle. -->
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  <div id="grid"></div>
  <!-- The sign-in form, rendered by renderSignIn() and shown whenever there is a login to
       offer and no seat to show for it. -->
  <div id="signin" hidden></div>
  <div id="seats"></div>
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

  const grid = document.getElementById("grid");
  const cells = [];
  for (let i = 0; i < 9; i++) {
    const b = document.createElement("button");
    b.className = "cell";
    b.addEventListener("click", () => play(i));
    grid.appendChild(b);
    cells.push(b);
  }

  const signin = document.getElementById("signin");
  renderSignIn();

  function render() {
    const board = state ? state.board : ".........";
    const line = (state && state.winningLine) || [];
    for (let i = 0; i < 9; i++) {
      const mark = board[i] === "." ? "" : board[i];
      cells[i].textContent = mark;
      // Playable only when this viewer holds the seat whose turn it is and the cell is free. The
      // server checks all of it again; this only keeps the page from asking for a refusal.
      cells[i].disabled = !state || !state.you || mark !== "" || state.completed || state.turn !== state.you;
      cells[i].classList.toggle("win", line.includes(i));
    }

    const status = document.getElementById("status");
    if (!state) status.textContent = login ? "sign in to play" : "not your match";
    else if (state.completed) status.textContent = state.draw ? "drawn" : state.winner + " wins";
    else if (state.you) status.textContent = state.turn === state.you ? "your move (" + state.you + ")" : state.turn + " to move";
    else status.textContent = state.turn + " to move";

    // Offered whenever there is a login to start and no seat to show for it — including after a
    // token expires mid-match, which is what turns a 401 back into a button.
    signin.hidden = !login || noSeat || (state && state.you);

    document.getElementById("seats").innerHTML = state
      ? state.players.map(p => "<div>" + p.mark + " · " + escapeHtml(p.cognitoId) + (p.mark === (state.you || "") ? " (you)" : "") + "</div>").join("")
      : "";
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  async function play(cell) {
    show("");
    const ticket = ask();
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ cell }) });
    if (!response) return;
    const answer = await response.json();
    // A refusal is dropped under a newer state, which says more; see PlayLive for the two orders.
    if (!response.ok) { if (!overtaken(ticket)) tell(ticket, answer.error || response.statusText); return; }
    if (latest(ticket)) state = answer;
    // Clears an earlier move's refusal, but not a later one's: that is still the news.
    tell(ticket, "");
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
   * sign-in — a stale token must not leave the board looking merely broken. */
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
  keepCurrent(refresh, () => mayFetch() && (!state || !state.completed));
</script>
</body>
</html>
"""
    }

    private def outcome(state: Protocol.StateResponse): String =
        if (state.draw) "drawn" else state.winner.map(w => s"$w wins").getOrElse("over")
}
