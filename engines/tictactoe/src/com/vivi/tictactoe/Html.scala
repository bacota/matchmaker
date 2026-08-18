package com.vivi.tictactoe

import upickle.default.write
import Protocol.given

/** The board page, and the page the hosted login redirects back to.
  *
  * Self-contained documents with no assets, because the engine has no static hosting and a page
  * that needs a second request needs somewhere to serve it from. When the viewer already has a
  * seat, the state is inlined into the first render so the board is right before any script runs;
  * otherwise the page is a shell that signs the player in and then fetches it.
  *
  * The sign-in is the same one matchmaker's own UI uses — same user pool, same app client, same
  * authorization-code-with-PKCE flow — so a player who is signed in to matchmaker signs in here
  * with the same account, and the `sub` the engine sees is the `cognitoId` matchmaker sent it.
  */
object Html {

  def board(
      matchId: String,
      state: Option[Protocol.StateResponse],
      login: Option[LoginConfig],
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
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; } }
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
  #signin { font: inherit; padding: .5rem 1.25rem; border-radius: 6px; border: 1px solid var(--line);
            background: var(--paper); color: var(--ink); cursor: pointer; margin-top: 1rem; }
  #signin[hidden] { display: none; }
  #seats { margin-top: 1.25rem; font-size: .875rem; opacity: .7; }
  #seats div { margin: .125rem 0; }
  #error { color: crimson; min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>tic-tac-toe</h1>
  <p id="status">${escape(heading)}</p>
  <div id="grid"></div>
  <button id="signin" hidden>sign in</button>
  <div id="seats"></div>
  <div id="error"></div>
</main>
<script>
${authScript(login)}

  const publicView = $publicView;
  // Urls are derived from this page's own, not built from a base: behind API Gateway the path
  // carries a stage prefix, and a page that assumed "/matches/..." would 404 there.
  const here = location.pathname.replace(new RegExp("/(play|board)$$"), "");
  const stateUrl = publicView ? here + "/board/state" : here + "/state";
  const movesUrl = here + "/moves";

  // Present when the server already knew whose seat this is; null when the player has yet to
  // sign in, in which case the first fetch below fills it.
  let state = ${state.map(s => scriptSafe(write(s))).getOrElse("null")};

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
  if (login) signin.addEventListener("click", () => startSignIn(location.href));

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
    signin.hidden = !login || (state && state.you);

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
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ cell }) });
    if (!response) return;
    const answer = await response.json();
    if (response.ok) { state = answer; render(); } else show(answer.error || response.statusText);
  }

  async function refresh() {
    const response = await send(stateUrl, {});
    if (response && response.ok) { state = await response.json(); render(); }
  }

  /* Every call carries the ID token when there is one. A 401 means the session is over rather
   * than the move being wrong, so the token is dropped and the page falls back to offering a
   * sign-in — a stale token must not leave the board looking merely broken. */
  async function send(url, init) {
    const token = idToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      if (response.status === 401 || response.status === 403) {
        if (token) forgetToken();
        state = null;
        render();
        show(login ? "sign in to play this match" : "you have no seat in this match");
        return null;
      }
      return response;
    } catch (e) {
      show("could not reach the engine");
      return null;
    }
  }

  render();
  if (!state && idToken()) refresh();
  setInterval(() => { if (!state || !state.completed) refresh(); }, 2000);
</script>
</body>
</html>
"""
  }

  /** The page Cognito redirects back to: it redeems the code and returns the player to the board
    * they started from.
    *
    * A fixed path, because Cognito matches callback urls exactly and cannot be given a pattern —
    * one per match is not something that could be registered. Where to go afterwards is therefore
    * this page's problem, and it is what the flow stored before leaving.
    */
  def authCallback(login: LoginConfig): String =
    s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>signing in</title>
<style>
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; color-scheme: light dark; }
  #error { color: crimson; max-width: 32rem; text-align: center; padding: 1rem; }
</style>
</head>
<body>
<p id="error">signing in…</p>
<script>
${authScript(Some(login))}

  (async () => {
    const params = new URLSearchParams(location.search);
    const failure = params.get("error_description") || params.get("error");
    const code = params.get("code");
    const returned = params.get("state");
    const expected = sessionStorage.getItem(StateKey);
    const back = sessionStorage.getItem(ReturnKey) || "/";

    function fail(message) { document.getElementById("error").textContent = message; }

    if (failure) return fail(failure);
    if (!code) return fail("no authorization code came back");
    // A callback this page did not start is not acted on (RFC 6749 §10.12).
    if (!returned || returned !== expected) return fail("this sign-in was not started here");

    const verifier = sessionStorage.getItem(VerifierKey);
    if (!verifier) return fail("this sign-in was started in another tab");

    const body = new URLSearchParams({
      grant_type: "authorization_code",
      client_id: login.clientId,
      code: code,
      code_verifier: verifier,
      redirect_uri: login.redirectUri
    });

    const response = await fetch(login.hostedLoginUrl + "/oauth2/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: body.toString()
    });

    if (!response.ok) return fail("the sign-in could not be completed: " + response.status);

    const tokens = await response.json();
    if (!tokens.id_token) return fail("no id token came back");

    sessionStorage.setItem(TokenKey, tokens.id_token);
    sessionStorage.removeItem(VerifierKey);
    sessionStorage.removeItem(StateKey);
    sessionStorage.removeItem(ReturnKey);
    location.replace(back);
  })();
</script>
</body>
</html>
"""

  /* The sign-in half of both pages: hosted login, authorization code with PKCE, ID token in
   * sessionStorage. The same flow and the same storage rules as matchmaker's UI (see Auth.scala
   * there) — tokens die with the tab and are not shared between tabs, and the password is only
   * ever typed into Cognito's own pages.
   *
   * The refresh token is deliberately not kept: a board is a page a player has open for the
   * length of a game, and an ID token lasts an hour. Dropping it means a long-abandoned tab asks
   * for a sign-in again instead of holding a credential that could renew itself. */
  private def authScript(login: Option[LoginConfig]): String =
    s"""  const login = ${login.map(l => s"""{ hostedLoginUrl: "${escapeJs(l.hostedLoginUrl)}", clientId: "${escapeJs(l.clientId)}", redirectUri: "${escapeJs(l.redirectUri)}" }""").getOrElse("null")};

  const TokenKey = "tictactoe.idToken";
  const VerifierKey = "tictactoe.pkceVerifier";
  const StateKey = "tictactoe.authState";
  const ReturnKey = "tictactoe.returnTo";

  function idToken() {
    const token = sessionStorage.getItem(TokenKey);
    if (!token) return null;
    // Expiry is checked here as well as by the engine, so an expired token becomes a sign-in
    // button rather than a request that is certain to come back 401.
    try {
      const claims = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
      if (claims.exp * 1000 <= Date.now() + 5000) { forgetToken(); return null; }
      return token;
    } catch (e) { forgetToken(); return null; }
  }

  function forgetToken() { sessionStorage.removeItem(TokenKey); }

  function randomValue() {
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    return base64Url(bytes);
  }

  function base64Url(bytes) {
    let s = "";
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=/g, "");
  }

  async function startSignIn(returnTo) {
    if (!login) return;
    // crypto.subtle exists only in a secure context: https, or http on localhost. Saying so is
    // better than failing later with "undefined is not a function".
    if (!crypto.subtle) {
      document.getElementById("error").textContent = "sign-in needs https or localhost";
      return;
    }
    const verifier = randomValue();
    const state = randomValue();
    sessionStorage.setItem(VerifierKey, verifier);
    sessionStorage.setItem(StateKey, state);
    sessionStorage.setItem(ReturnKey, returnTo);

    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
    const challenge = base64Url(new Uint8Array(digest));

    const query = new URLSearchParams({
      response_type: "code",
      client_id: login.clientId,
      redirect_uri: login.redirectUri,
      scope: "openid email profile",
      state: state,
      code_challenge_method: "S256",
      code_challenge: challenge
    });
    location.assign(login.hostedLoginUrl + "/oauth2/authorize?" + query.toString());
  }
"""

  private def outcome(state: Protocol.StateResponse): String =
    if (state.draw) "drawn" else state.winner.map(w => s"$w wins").getOrElse("over")

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /* Inlined into a <script> block, where the one sequence that must not appear verbatim is a
   * closing tag — a player's cognito id is not the engine's to vouch for. */
  private def scriptSafe(json: String): String = json.replace("</", "<\\/")

  private def escapeJs(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("</", "<\\/")
}
