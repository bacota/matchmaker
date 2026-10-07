package com.vivi.engine

import upickle.default.write
import HtmlText.{escape, scriptSafe}
import ChoosingView.given

/** The play page of a match whose players are still choosing their roles ([[RoleChoosing]]), the same for every game.
  *
  * Served in place of the game's own page until the roles are settled; then it reloads, and the play url serves the
  * game's page from there on. Like every engine page it is self-contained, signs the player in with the game's own
  * [[SignIn]], keeps current with [[PlayLive]], and shows a live match's clock with [[TurnTimer]].
  *
  * What it shows: who is choosing now, in a live region, since a turn arrives while the page is idle; the free roles,
  * as buttons, to the player whose choice it is; who has chosen what; and the chooser's clock in a live match.
  */
object RoleChoicePage {

    def page(
        matchId: String,
        title: String,
        state: Option[ChoosingView],
        login: Option[LoginConfig],
        signIn: SignIn,
        liveUrl: Option[String],
        publicView: Boolean
    ): String = {
        val playLive = PlayLive(signIn.storagePrefix)
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e; --accent: #1d6b46; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; --accent: #7fd1a8; } }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { width: min(32rem, 100%); box-sizing: border-box; padding: 2rem 1rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  #status { font-size: 1.25rem; font-weight: 700; margin: 0 0 1rem; min-height: 2rem; }
  #roles { display: flex; flex-wrap: wrap; gap: .5rem; margin: 0 0 1.25rem; }
  #roles button, #concede { min-height: 44px; min-width: 44px; padding: .5rem 1rem; font: inherit; border-radius: 6px;
                            border: 2px solid var(--accent); background: var(--paper); color: var(--ink); cursor: pointer; }
  #roles button:hover { background: color-mix(in srgb, var(--paper) 85%, var(--accent)); }
  #roles button:focus-visible, #concede:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
  #concede { border-color: var(--line); }
  #seats { list-style: none; padding: 0; margin: 0 0 1rem; }
  #seats li { padding: .5rem 0; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between;
              gap: 1rem; }
  #seats .role { opacity: .8; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
</style>
</head>
<body>
<main>
  <h1>${escape(title)}</h1>
  <!-- Announced: whose choice it is changes while this page is idle. -->
  <p id="status" role="status" aria-live="polite"></p>
  ${TurnTimer.markup}
  <div id="roles" role="group" aria-labelledby="status" hidden></div>
  <ul id="seats" aria-label="Who plays which role"></ul>
  <button type="button" id="concede" hidden>Concede the match</button>
  <div id="signin" hidden></div>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>
</main>
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${TurnTimer.script}

  const publicView = $publicView;
  const here = location.pathname.replace(new RegExp("/(play|board)$$"), "");
  const query = location.search;
  const stateUrl = (publicView ? here + "/board/state" : here + "/state") + query;
  const roleUrl = here + "/role" + query;

  let state = ${state.map(s => scriptSafe(write(s))).getOrElse("null")};
  let noSeat = false;

  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);

  function nameOf(id) {
    const seat = state && state.seats.find(s => s.participantId === id);
    return seat ? seat.name : "a player";
  }

  function render() {
    const status = document.getElementById("status");
    const yours = !!(state && state.you !== undefined && state.you !== null && state.chooser === state.you);
    if (!state) status.textContent = login ? "Sign in to choose your role" : "Not your match";
    else if (state.ended) status.textContent = state.ended;
    else if (state.done) status.textContent = "Every role is chosen. The game is starting.";
    else if (yours) status.textContent = "Your turn: choose a role";
    else if (state.chooser !== undefined && state.chooser !== null) status.textContent = nameOf(state.chooser) + " is choosing a role";
    else status.textContent = "Waiting for the game to start";

    const roles = document.getElementById("roles");
    roles.hidden = !yours;
    roles.innerHTML = "";
    if (yours) state.free.forEach(offer => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = offer.displayName;
      b.addEventListener("click", () => choose({ role: offer.role }));
      roles.appendChild(b);
    });

    document.getElementById("seats").innerHTML = state
      ? state.seats.map(s => "<li><span>" + escapeHtml(s.name) + (s.participantId === state.you ? " (you)" : "") +
          "</span><span class=\\"role\\">" + escapeHtml(s.role || (s.waiting ? "choosing now" : "to choose")) +
          "</span></li>").join("")
      : "";

    const concede = document.getElementById("concede");
    concede.hidden = !(state && state.canConcede);

    showClock(state && state.clock, state && state.you !== undefined ? state.you : null);
    signin.hidden = !login || noSeat || (state && state.you !== undefined && state.you !== null);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  /* The game has begun once the roles are settled, and the play url now serves its own page. */
  function onward(answer) {
    if (!answer.choosing || answer.done) { location.reload(); return true; }
    return false;
  }

  async function choose(body) {
    show("");
    const ticket = ask();
    const response = await send(roleUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
    if (!response) return;
    const answer = await response.json();
    if (!response.ok) { if (!overtaken(ticket)) tell(ticket, answer.error || response.statusText); return; }
    if (onward(answer)) return;
    if (latest(ticket)) state = answer;
    tell(ticket, "");
    render();
  }

  document.getElementById("concede").addEventListener("click", () => {
    if (confirm("Concede the match? You will lose it.")) choose({ concede: true });
  });

  async function refresh() {
    const ticket = ask();
    const response = await send(stateUrl, {}, ticket);
    if (!response || !response.ok) return;
    const answer = await response.json();
    if (onward(answer)) return;
    if (latest(ticket)) { state = answer; render(); }
  }

  async function send(url, init, ticket) {
    const token = await freshIdToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      if (response.status === 401) {
        if (!latest(ticket)) return null;
        if (token) clearSession();
        state = null;
        render();
        tell(ticket, login ? "sign in to play this match" : "say who you are with ?as=<cognito sub>");
        return null;
      }
      if (response.status === 403) {
        if (!latest(ticket)) return null;
        noSeat = true;
        state = null;
        render();
        tell(ticket, "you have no seat in this match");
        return null;
      }
      return response;
    } catch (e) {
      if (!overtaken(ticket)) tell(ticket, "could not reach the engine");
      return null;
    }
  }

  function signedIn() { refresh(); }

  function mayFetch() { return !noSeat && (!login || publicView || isSignedIn()); }

  render();
  if (!state && mayFetch()) refresh();
  keepCurrent(refresh, () => mayFetch() && (!state || (!state.ended && !state.done)));
</script>
</body>
</html>
"""
    }
}
