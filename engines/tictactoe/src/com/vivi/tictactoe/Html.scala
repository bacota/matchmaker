package com.vivi.tictactoe

import upickle.default.write
import Protocol.given

/** The board page, and the page the hosted login redirects back to.
  *
  * Self-contained documents with no assets, because the engine has no static hosting and a page that needs a second
  * request needs somewhere to serve it from. When the viewer already has a seat, the state is inlined into the first
  * render so the board is right before any script runs; otherwise the page is a shell that signs the player in and then
  * fetches it.
  *
  * The sign-in is the same one matchmaker's own UI uses — same user pool, same app client, and the same flow, copied
  * from `SignIn.scala`, `Auth.scala` and `CognitoIdp.scala` there rather than shared with them: email and password on
  * this page against `InitiateAuth`, with the emailed code offered second, and only sign-up and password reset
  * redirecting to Cognito's hosted pages. So a player signs in here exactly as they do on the main page, with the same
  * account, and the `sub` the engine sees is the `cognitoId` matchmaker sent it.
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
  #signin { margin: 1.25rem auto 0; text-align: left; max-width: 20rem; }
  #signin[hidden] { display: none; }
  #signin h2 { font-size: 1rem; font-weight: 600; margin: 0 0 .5rem; letter-spacing: normal; text-transform: none; opacity: 1; }
  #signin p { margin: 0 0 .75rem; font-size: .875rem; }
  #signin label { display: block; font-size: .875rem; margin-bottom: .25rem; }
  /* 16px on the fields, or iOS zooms the page the moment one takes focus. 44px on everything
     that can be tapped, including the links, which are buttons for exactly that reason. */
  #signin input { font: inherit; font-size: 16px; width: 100%; box-sizing: border-box; min-height: 44px;
                  padding: .5rem .625rem; margin-bottom: .75rem; border-radius: 6px;
                  border: 1px solid var(--line); background: var(--paper); color: var(--ink); }
  #signin button[type="submit"] { font: inherit; width: 100%; min-height: 44px; border-radius: 6px;
                  border: 1px solid var(--line); background: var(--paper); color: var(--ink); cursor: pointer; }
  #signin .alternatives { display: flex; flex-direction: column; align-items: flex-start; margin-top: .25rem; }
  #signin .alternatives button { font: inherit; min-height: 44px; padding: 0; border: 0; background: none;
                  color: inherit; text-decoration: underline; cursor: pointer; }
  #signin button:disabled { opacity: .45; cursor: default; }
  #signin .problem { color: crimson; font-size: .875rem; margin-bottom: .5rem; }
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
  <!-- The sign-in form, rendered by renderSignIn() and shown whenever there is a login to
       offer and no seat to show for it. -->
  <div id="signin" hidden></div>
  <div id="seats"></div>
  <div id="error"></div>
</main>
<script>
${authScript(login)}
${signInScript}

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
    const token = await freshIdToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      if (response.status === 401 || response.status === 403) {
        if (token) clearSession();
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

  /* Called once the sign-in has tokens in hand: there is a seat to fetch now, and the board is
   * still showing the shell it was served. */
  function signedIn() { refresh(); }

  /* Whether there is any point asking for the state. With a login configured and no session, the
   * answer is a 401 — and asking every two seconds scrolls the console with them and, worse, kept
   * rebuilding the sign-in form under the player's cursor. Public boards and the trusted local mode
   * have no session to wait for and are fetched as before. */
  function mayFetch() { return !login || publicView || isSignedIn(); }

  render();
  if (!state && mayFetch()) refresh();
  setInterval(() => { if (mayFetch() && (!state || !state.completed)) refresh(); }, 2000);
</script>
</body>
</html>
"""
    }

    /** The page Cognito redirects back to: it redeems the code and returns the player to the board they started from.
      *
      * A fixed path, because Cognito matches callback urls exactly and cannot be given a pattern — one per match is not
      * something that could be registered. Where to go afterwards is therefore this page's problem, and it is what the
      * flow stored before leaving.
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

    // Stored the same way a sign-in on the board stores them, refresh token included: a player who
    // arrives here from the hosted sign-up has a session that behaves like any other.
    storeTokens({ idToken: tokens.id_token, accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
    sessionStorage.removeItem(VerifierKey);
    sessionStorage.removeItem(StateKey);
    sessionStorage.removeItem(ReturnKey);
    location.replace(back);
  })();
</script>
</body>
</html>
"""

    /* The sign-in half of both pages, copied from matchmaker's own UI rather than shared with it:
     * this is a self-contained document served by a Lambda that has no static hosting, and the UI
     * is Scala.js. What is copied is `Auth.scala` and `CognitoIdp.scala` there, and the flow is
     * meant to stay identical to them — a player signs in here exactly as they do on the main
     * page, against the same pool and the same app client.
     *
     * So: signing in is done on this page, by `InitiateAuth` with `PREFERRED_CHALLENGE = PASSWORD`
     * (see `signInScript`). Signing up and resetting a password still redirect to the hosted pages
     * and come back through `/auth/callback` with an authorization code, which is what the PKCE
     * half below is for.
     *
     * Tokens live in `sessionStorage`: they die with the tab and are not shared between tabs. The
     * refresh token is kept alongside the ID token, as on the main page, so a board left open for
     * longer than the ID token's hour renews itself rather than asking the player to sign in in
     * the middle of a game. */
    private def authScript(login: Option[LoginConfig]): String =
        s"""  const login = ${login
                .map(l =>
                    s"""{ hostedLoginUrl: "${escapeJs(l.hostedLoginUrl)}", clientId: "${escapeJs(
                          l.clientId
                        )}", redirectUri: "${escapeJs(l.redirectUri)}", region: "${escapeJs(l.region)}" }"""
                )
                .getOrElse("null")};

  const TokenKey = "tictactoe.idToken";
  const AccessKey = "tictactoe.accessToken";
  const RefreshKey = "tictactoe.refreshToken";
  const VerifierKey = "tictactoe.pkceVerifier";
  const StateKey = "tictactoe.authState";
  const ReturnKey = "tictactoe.returnTo";

  /* True while the token's `exp` is still in the future, with a margin so one about to expire is
   * not sent on a request that would outlive it. The signature is deliberately not checked: the
   * engine is what decides whether a token is good, and this only decides whether to bother
   * asking. */
  function unexpired(token) {
    try {
      const claims = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
      return claims.exp - 30 > Date.now() / 1000;
    } catch (e) { return false; }
  }

  function idToken() {
    const token = sessionStorage.getItem(TokenKey);
    return token && unexpired(token) ? token : null;
  }

  function refreshToken() { return sessionStorage.getItem(RefreshKey) || null; }

  /* A session exists when there is a usable ID token or a refresh token that could obtain one.
   * Used for rendering; `freshIdToken` is what establishes whether the refresh actually works. */
  function isSignedIn() { return !!(idToken() || refreshToken()); }

  /* At most one refresh in flight: the board polls, so several requests can meet an expired token
   * at once and must share one redemption rather than race to spend the token each. */
  let refreshing = null;

  async function freshIdToken() {
    const token = idToken();
    if (token) return token;
    await refreshed();
    return idToken();
  }

  function refreshed() {
    const token = refreshToken();
    if (!token || !login) return Promise.resolve();
    if (!refreshing) refreshing = redeem(token).then(() => { refreshing = null; }, () => { refreshing = null; });
    return refreshing;
  }

  /* Through `REFRESH_TOKEN_AUTH` on the user pools API, not the hosted UI's token endpoint: a
   * refresh token minted by `InitiateAuth` was not issued against an OAuth grant and that endpoint
   * will not redeem it.
   *
   * A refusal from Cognito ends the session; an unreachable Cognito does not, or a dropped
   * connection would sign a player out mid-game over something that would have worked a second
   * later. */
  async function redeem(token) {
    try {
      const outcome = outcomeOf(await idp("InitiateAuth", {
        AuthFlow: "REFRESH_TOKEN_AUTH",
        ClientId: login.clientId,
        AuthParameters: { REFRESH_TOKEN: token }
      }));
      if (outcome.tokens) storeTokens(outcome.tokens);
      // A 200 with no tokens in it. Retrying would loop, so this ends the session too.
      else clearSession();
    } catch (error) {
      if (error && error.idp) clearSession();
    }
  }

  /* A refresh grant returns no refresh token of its own, so the one already held is left in
   * place rather than cleared. */
  function storeTokens(tokens) {
    sessionStorage.setItem(TokenKey, tokens.idToken);
    if (tokens.accessToken) sessionStorage.setItem(AccessKey, tokens.accessToken);
    if (tokens.refreshToken) sessionStorage.setItem(RefreshKey, tokens.refreshToken);
  }

  function clearSession() {
    [TokenKey, AccessKey, RefreshKey, VerifierKey, StateKey].forEach(key => sessionStorage.removeItem(key));
  }

  /* One call to the Cognito user pools API. Not an SDK and not SigV4 signed: `InitiateAuth` and
   * `RespondToAuthChallenge` are how a caller with no credentials obtains some, and the app client
   * is public, so there is no secret hash to compute either. The wire format is AWS JSON 1.1 — the
   * operation is named in a header, and a failure is a 400 whose body carries the exception type. */
  async function idp(operation, payload) {
    let response, body;
    try {
      response = await fetch("https://cognito-idp." + login.region + ".amazonaws.com/", {
        method: "POST",
        headers: {
          "content-type": "application/x-amz-json-1.1",
          "x-amz-target": "AWSCognitoIdentityProviderService." + operation
        },
        body: JSON.stringify(payload)
      });
      body = await response.text();
    } catch (e) { throw unreachable(String(e)); }
    if (response.ok) return body;
    throw idpError(response.status, body);
  }

  /* `idp: true` marks a rejection Cognito understood and answered — retrying will not change it,
   * and it is what ends a session. Anything else is a transport failure and is not. */
  function idpError(status, body) {
    try {
      const json = JSON.parse(body);
      // Sometimes a bare name, sometimes qualified with a namespace; only the last segment means
      // anything, so callers can match on it without knowing which form arrived.
      const named = json.__type || "UnknownError";
      return {
        idp: true,
        type: named.substring(named.lastIndexOf("#") + 1),
        // `message` in this protocol, but `Message` appears in the wild too.
        message: json.message || json.Message || body
      };
    } catch (e) { return unreachable("HTTP " + status + ": " + body); }
  }

  function unreachable(detail) { return { idp: false, type: "IdpUnavailable", message: detail }; }

  /* Either the run is finished and there are tokens, or Cognito wants another step. */
  function outcomeOf(body) {
    const json = JSON.parse(body);
    const result = json.AuthenticationResult;
    if (result) {
      return { tokens: { idToken: result.IdToken, accessToken: result.AccessToken, refreshToken: result.RefreshToken } };
    }
    return {
      challenge: {
        name: json.ChallengeName,
        // Every challenge carries one, and without it there is nothing to respond with.
        session: json.Session,
        parameters: json.ChallengeParameters || {}
      }
    };
  }

  /* `USER_AUTH` is the flow that lets the client decide what to ask for, which is the whole reason
   * this form exists rather than a redirect to managed login: managed login enables every factor
   * the pool allows and puts the emailed code first, with no setting that reorders it.
   *
   * Passing PASSWORD with the password itself resolves in one round trip when it is right. */
  async function initiateUserAuth(username, preferred, password) {
    const parameters = { USERNAME: username, PREFERRED_CHALLENGE: preferred };
    if (password) parameters.PASSWORD = password;
    return outcomeOf(await idp("InitiateAuth", {
      AuthFlow: "USER_AUTH",
      ClientId: login.clientId,
      AuthParameters: parameters
    }));
  }

  /* Answers an outstanding challenge. The session comes from the challenge being answered, and is
   * single-use — a new one comes back if this response is itself challenged. */
  async function respondToChallenge(name, session, responses) {
    return outcomeOf(await idp("RespondToAuthChallenge", {
      ChallengeName: name,
      ClientId: login.clientId,
      Session: session,
      ChallengeResponses: responses
    }));
  }

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

  /* Sign-up and password reset only. Both are several screens that Cognito already has, and both
   * come back to `/auth/callback` with an authorization code — where a player who has just
   * confirmed a sign-up or set a new password arrives already signed in. */
  async function hosted(page, returnTo) {
    if (!login) return;
    // crypto.subtle exists only in a secure context: https, or http on localhost. Saying so is
    // better than failing later with "undefined is not a function".
    if (!crypto.subtle) {
      document.getElementById("error").textContent = "sign-in needs https or localhost";
      return;
    }
    const verifier = randomValue();
    const state = randomValue();
    // Stored before navigating and read back when Cognito redirects here; sessionStorage survives
    // the redirect, and where to return to afterwards is this flow's own problem because Cognito
    // matches callback urls exactly and cannot be given one per match.
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
    location.assign(login.hostedLoginUrl + "/" + page + "?" + query.toString());
  }
"""
    /* The sign-in form, and the challenge run behind it — `SignIn.scala` in matchmaker's UI,
     * copied. Cognito's `USER_AUTH` flow is a conversation rather than a single call: a first
     * request names the factor to try, and the answer is either tokens or another challenge
     * carrying a session to echo back. `stage` holds the position in that conversation.
     *
     * The page it renders into is `#signin`, which the board shows whenever there is a login to
     * offer and no seat to show for it. `signedIn()` is the board's own, called once tokens are
     * in hand. */
    private def signInScript: String =
        """  const codeResponseKeys = {
    // Only EMAIL_OTP can arrive with the pool as it is configured — no MFA and no phone number.
    // The rest are the same screen with a different key, and an unrecognised challenge is a dead
    // end for the player in front of it.
    EMAIL_OTP: "EMAIL_OTP_CODE",
    SMS_OTP: "SMS_OTP_CODE",
    SMS_MFA: "SMS_MFA_CODE",
    SOFTWARE_TOKEN_MFA: "SOFTWARE_TOKEN_MFA_CODE"
  };

  let stage = { kind: "credentials" };
  /* True while a request is in flight. Disables the buttons, so a slow answer does not become two
   * attempts — the second of which would arrive with a spent session. */
  let busy = false;
  /* Shown above the form. Not the page's `#error`: this belongs to the form and clears when the
   * player tries again. */
  let problem = null;
  let signInEmail = "";
  let signInPassword = "";

  function renderSignIn() {
    const box = document.getElementById("signin");
    if (!login) { box.innerHTML = ""; return; }

    const trouble = problem ? '<p class="problem" role="alert">' + escapeHtml(problem) + "</p>" : "";
    const off = busy ? " disabled" : "";
    const field = (id, type, complete, label, value, extra) =>
      '<label for="' + id + '">' + label + "</label>" +
      '<input id="' + id + '" type="' + type + '" autocomplete="' + complete + '" value="' +
      escapeHtml(value || "") + '"' + (extra || "") + ">";

    if (stage.kind === "credentials") {
      box.innerHTML = trouble +
        "<form><h2>Sign In</h2>" +
        field("si-email", "email", "username", "Email", signInEmail) +
        field("si-password", "password", "current-password", "Password", signInPassword) +
        '<button type="submit"' + off + ">Sign in</button>" +
        '<div class="alternatives">' +
        // The passwordless route, kept but not put first — which is the whole reason this form
        // exists instead of a redirect to managed login.
        '<button type="button" id="si-code"' + off + ">Email me a code instead</button>" +
        '<button type="button" id="si-forgot">Forgot your password?</button>' +
        '<button type="button" id="si-signup">Create an account</button>' +
        "</div></form>";

      const email = document.getElementById("si-email");
      const password = document.getElementById("si-password");
      // Remembered across the re-render a failed attempt causes, so the player is not retyping
      // their address to correct a typo in the password.
      email.addEventListener("input", () => { signInEmail = email.value; });
      password.addEventListener("input", () => { signInPassword = password.value; });

      // The browser's own submit is what makes Enter work in either field, and what gets password
      // managers to offer to fill and to save. preventDefault, or the page reloads.
      box.querySelector("form").addEventListener("submit", e => {
        e.preventDefault();
        const username = email.value.trim();
        step(username, () => initiateUserAuth(username, "PASSWORD", password.value));
      });
      document.getElementById("si-code").addEventListener("click", () => {
        const username = email.value.trim();
        signInPassword = "";
        step(username, () => initiateUserAuth(username, "EMAIL_OTP", null));
      });
      document.getElementById("si-forgot").addEventListener("click", () => hosted("forgotPassword", location.href));
      document.getElementById("si-signup").addEventListener("click", () => hosted("signup", location.href));

    } else if (stage.kind === "code") {
      box.innerHTML = trouble +
        "<form><h2>Enter Your Code</h2><p>" +
        (stage.deliveredTo ? "We sent a sign-in code to " + escapeHtml(stage.deliveredTo) + "." : "We sent you a sign-in code.") +
        "</p>" +
        // one-time-code lets phones offer the code straight from the notification.
        field("si-code-value", "text", "one-time-code", "Code", "", ' inputmode="numeric"') +
        '<button type="submit"' + off + ">Sign in</button>" +
        '<div class="alternatives"><button type="button" id="si-restart">Start again</button></div></form>';

      const code = document.getElementById("si-code-value");
      box.querySelector("form").addEventListener("submit", e => {
        e.preventDefault();
        const responses = { USERNAME: stage.username };
        responses[codeResponseKeys[stage.challenge]] = code.value.trim();
        step(stage.username, () => respondToChallenge(stage.challenge, stage.session, responses));
      });
      document.getElementById("si-restart").addEventListener("click", resetSignIn);

    } else {
      box.innerHTML = trouble +
        "<form><h2>Choose a Password</h2>" +
        "<p>This account is signed in with a temporary password. Pick a permanent one to continue.</p>" +
        field("si-new-password", "password", "new-password", "New password", "") +
        '<button type="submit"' + off + ">Save and sign in</button>" +
        '<div class="alternatives"><button type="button" id="si-restart">Start again</button></div></form>';

      const chosen = document.getElementById("si-new-password");
      box.querySelector("form").addEventListener("submit", e => {
        e.preventDefault();
        step(stage.username, () => respondToChallenge("NEW_PASSWORD_REQUIRED", stage.session, {
          USERNAME: stage.username,
          NEW_PASSWORD: chosen.value
        }));
      });
      document.getElementById("si-restart").addEventListener("click", resetSignIn);
    }
  }

  /* Runs one step and advances, or reports why it did not. `username` is threaded through because
   * Cognito does not echo it and every subsequent response has to carry it. */
  function step(username, run) {
    if (!username) { problem = "Enter your email address."; renderSignIn(); return; }
    if (busy) return;
    busy = true;
    problem = null;
    renderSignIn();
    run().then(
      outcome => { busy = false; advance(username, outcome); },
      error => { busy = false; problem = explain(error); renderSignIn(); }
    );
  }

  function advance(username, outcome) {
    if (outcome.tokens) return succeed(outcome.tokens);

    const challenge = outcome.challenge;
    const parameters = challenge.parameters || {};

    if (codeResponseKeys[challenge.name]) {
      stage = {
        kind: "code",
        challenge: challenge.name,
        username: username,
        session: challenge.session,
        // The masked address the code went to, worth showing so the player knows which mailbox
        // to open.
        deliveredTo: parameters.CODE_DELIVERY_DESTINATION
      };
      problem = null;
      renderSignIn();
      return;
    }

    // Reached by an account on a temporary password, and by anyone Cognito has had a reset
    // forced on.
    if (challenge.name === "NEW_PASSWORD_REQUIRED") {
      stage = { kind: "newPassword", username: username, session: challenge.session };
      problem = null;
      renderSignIn();
      return;
    }

    /* Cognito asking for the password separately rather than accepting the one already sent. Not
     * the usual answer — a correct password authenticates outright and a wrong one is a rejection
     * — so it is answered once from the field rather than becoming another screen. There is no
     * loop to fall into: the response either authenticates or fails. */
    if ((challenge.name === "PASSWORD" || challenge.name === "PASSWORD_SRP") && signInPassword) {
      step(username, () => respondToChallenge("PASSWORD", challenge.session, {
        USERNAME: username,
        PASSWORD: signInPassword
      }));
      return;
    }

    /* The pool offering a choice instead of honouring the preference. Answering with PASSWORD
     * keeps this page's ordering rather than dropping the player into a factor picker. */
    const available = (parameters.AVAILABLE_CHALLENGES || "").split(",");
    if (challenge.name === "SELECT_CHALLENGE" && available.indexOf("PASSWORD") >= 0) {
      step(username, () => respondToChallenge("SELECT_CHALLENGE", challenge.session, {
        USERNAME: username,
        ANSWER: "PASSWORD"
      }));
      return;
    }

    problem = "This account needs a sign-in step this page does not support (" + challenge.name +
      "). Try resetting your password.";
    renderSignIn();
  }

  function succeed(tokens) {
    storeTokens(tokens);
    resetSignIn();
    signedIn();
  }

  /* Back to an empty form: on success so the password does not sit in a variable for the rest of
   * the session, and on "start again" so a half-finished challenge is not resumed with a spent
   * session. */
  function resetSignIn() {
    stage = { kind: "credentials" };
    signInPassword = "";
    problem = null;
    renderSignIn();
  }

  /* Cognito's own wording is used for anything not named here: those strings are written to be
   * read by end users, and a second copy of its error catalogue is not worth maintaining. The
   * exceptions are the few where the right thing to say includes what to do next. */
  function explain(error) {
    if (!error || !error.idp) return "Could not reach the sign-in service. Check your connection and try again.";
    switch (error.type) {
      // Deliberately does not distinguish a bad address from a bad password: the pool client sets
      // prevent_user_existence_errors, and saying more here would undo it on the client side.
      case "NotAuthorizedException":
      case "UserNotFoundException":
        return "Incorrect email or password.";
      case "UserNotConfirmedException":
        return "This account has not been confirmed yet. Check your email for the confirmation link.";
      case "PasswordResetRequiredException":
        return "This account needs a new password. Use “Forgot your password?” below.";
      case "CodeMismatchException":
        return "That code is not right. Check it and try again.";
      case "ExpiredCodeException":
        return "That code has expired. Start again to have a new one sent.";
      case "InvalidPasswordException":
        return "That password does not meet the pool's requirements: " + error.message;
      default:
        return error.message;
    }
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
