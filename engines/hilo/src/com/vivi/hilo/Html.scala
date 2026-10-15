package com.vivi.hilo

import upickle.default.write
import com.vivi.engine.{Finale, LoginConfig, PlayLive, SignIn, TurnTimer}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page: a button for each number, who has picked, and every round played.
  *
  * Self-contained, like every engine's page. Nothing in it discloses a pick the server would not: what is hidden is
  * hidden in `Engine.stateOf`.
  */
object Html {

    val signIn: SignIn = SignIn("hilo")

    val playLive: PlayLive = PlayLive("hilo")

    def board(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val heading = state match {
            case Some(s) if s.isCompleted                         => "the match is over"
            case Some(s) if s.you.isDefined && s.yourPick.isEmpty => "pick a number"
            case Some(_)                                          => "waiting for the others"
            case None                                             => "sign in to play"
        }

        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e; --accent: #2e7d32; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; --accent: #81c784; } }
  body { margin: 0; min-height: 100vh; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { margin: 0 auto; padding: 2rem 16px; max-width: 32rem; text-align: center; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  h2 { font-size: 1rem; margin: 1.5rem 0 .5rem; }
  #status { font-size: 1.5rem; font-weight: 700; margin: 0 0 .25rem; min-height: 2rem; }
  #rule { margin: 0 0 1.25rem; opacity: .8; }
  #numbers { display: flex; gap: .5rem; justify-content: center; flex-wrap: wrap; margin: 0 0 1rem; padding: 0; border: 0; }
  #numbers[hidden], #history[hidden] { display: none; }
  #numbers legend { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
  /* 44px both ways: a pick made by mistake on a phone cannot be taken back. */
  button.number { font: 700 1.25rem/1 ui-sans-serif, system-ui, sans-serif; min-width: 52px; min-height: 52px;
                  border-radius: 8px; border: 1px solid var(--line); background: var(--paper); color: var(--ink);
                  cursor: pointer; }
  button.number[aria-disabled="true"] { cursor: default; opacity: .45; }
  button.number[aria-pressed="true"] { border-color: var(--accent); border-width: 3px; opacity: 1; }
  button.number:not([aria-disabled="true"]):hover { background: color-mix(in srgb, var(--paper) 85%, var(--ink)); }
  :focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
${Finale.css}
  ul { list-style: none; margin: 0; padding: 0; }
  #players li { margin: .125rem 0; }
  #rounds { text-align: left; }
  #rounds li { border-top: 1px solid var(--line); padding: .5rem 0; }
  #rounds .won { color: var(--accent); font-weight: 700; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>hilo</h1>
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  <p id="rule"></p>
  ${TurnTimer.markup}
  ${Finale.markup}
  <fieldset id="numbers"><legend>your number</legend></fieldset>
  <div id="signin" hidden></div>
  <h2 id="players-heading">Players</h2>
  <ul id="players" aria-labelledby="players-heading"></ul>
  <section id="history" hidden>
    <h2 id="rounds-heading">Rounds</h2>
    <ul id="rounds" aria-labelledby="rounds-heading"></ul>
  </section>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>
</main>
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${TurnTimer.script}
${Finale.script(Finale.trophy, Finale.brighterDays)}

  const publicView = $publicView;
  const here = location.pathname.replace(new RegExp("/(play|board)$$"), "");
  const query = location.search;
  const stateUrl = (publicView ? here + "/board/state" : here + "/state") + query;
  const movesUrl = here + "/moves" + query;

  let state = ${state.map(s => scriptSafe(write(s))).getOrElse("null")};
  let noSeat = false;

  const numbers = document.getElementById("numbers");
  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);
  const showFinale = finale();

  function nameOf(id) {
    const p = state.players.find(x => x.participantId === id);
    return p ? p.name : "someone";
  }
  function isMe(id) { return !!(state && state.you && String(id) === state.you); }
  function who(id) { return isMe(id) ? "you" : nameOf(id); }

  /* The buttons are rebuilt only when the count changes, so a refresh does not take the keyboard's
   * focus off the number it is on. */
  function renderNumbers(canPick) {
    const count = state ? state.highest : 0;
    if (numbers.querySelectorAll("button").length !== count) {
      numbers.querySelectorAll("button").forEach(b => b.remove());
      for (let n = 1; n <= count; n++) {
        const b = document.createElement("button");
        b.className = "number";
        b.type = "button";
        b.textContent = String(n);
        b.addEventListener("click", () => { if (b.getAttribute("aria-disabled") !== "true") pick(n); });
        numbers.appendChild(b);
      }
    }
    numbers.querySelectorAll("button").forEach((b, i) => {
      b.setAttribute("aria-disabled", String(!canPick));
      b.setAttribute("aria-pressed", String(!!state && state.yourPick === i + 1));
    });
  }

  function render() {
    const canPick = !!(state && state.you && state.yourPick == null && !state.isCompleted);
    renderNumbers(canPick);
    numbers.hidden = !state || !state.you || state.isCompleted;
    document.getElementById("status").textContent = describe();
    document.getElementById("rule").textContent = state
      ? "Pick a number from 1 to " + state.highest + ". The highest number nobody else picks wins."
      : "";

    const me = state && state.you ? Number(state.you) : null;
    showClock(state && state.clock, me);
    showFinale(result());
    signin.hidden = !login || noSeat || (state && state.you);

    document.getElementById("players").innerHTML = state
      ? state.players.map(p => {
          const you = isMe(p.participantId) ? " (you)" : "";
          const won = state.winners.includes(p.participantId) ? " · won" : "";
          const what = state.isCompleted ? won : (p.hasPicked ? " · picked" : " · picking");
          return "<li>" + escapeHtml(p.name) + you + what + "</li>";
        }).join("")
      : "";

    const rounds = state ? state.rounds : [];
    document.getElementById("history").hidden = rounds.length === 0;
    document.getElementById("rounds").innerHTML = rounds.map(r => {
      const picks = r.picks.map(x => {
        const text = escapeHtml(who(x.participantId)) + " " + x.number;
        return x.participantId === r.winner ? '<span class="won">' + text + "</span>" : text;
      }).join(", ");
      const outcome = r.winner == null
        ? "no number was picked by one player alone, so the round is played again"
        : escapeHtml(who(r.winner)) + (isMe(r.winner) ? " win" : " wins");
      return "<li><strong>Round " + r.round + ":</strong> " + picks + " — " + outcome + ".</li>";
    }).join("");
  }

  function ranOut() { return state && state.clock ? state.clock.timedOut : []; }

  function result() {
    if (!state || !state.isCompleted || !state.you || publicView) return null;
    return state.winners.includes(Number(state.you)) ? "win" : "lose";
  }

  function describe() {
    if (!state) return login ? "sign in to play" : "not your match";
    if (state.isCompleted) {
      const late = ranOut();
      if (late.length && state.you) return late.includes(Number(state.you)) ? "your time ran out — you lose" : "their time ran out — you win";
      if (!state.winners.length) return "nobody wins";
      if (state.you) return state.winners.includes(Number(state.you)) ? "you win" : "you lose";
      return state.winners.map(nameOf).join(" and ") + (state.winners.length > 1 ? " win" : " wins");
    }
    const again = state.round > 1 ? "nobody won round " + (state.round - 1) + " — " : "";
    if (state.you && state.yourPick == null) return again + "pick a number";
    const left = state.waitingFor.filter(id => !isMe(id)).map(nameOf);
    return again + (left.length ? "waiting for " + left.join(", ") : "waiting");
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  async function pick(number) {
    const ticket = ask();
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ number }) }, ticket);
    if (!response) return;
    const answer = await response.json();
    if (!response.ok) { if (!overtaken(ticket)) tell(ticket, answer.error || response.statusText); return; }
    if (latest(ticket)) state = answer;
    tell(ticket, "");
    render();
  }

  async function refresh() {
    const ticket = ask();
    const response = await send(stateUrl, {}, ticket);
    if (!response || !response.ok) return;
    const answer = await response.json();
    if (latest(ticket)) { state = answer; render(); }
  }

  /* A 401 is "who are you?": the session is dropped and a sign-in offered. A 403 is "not yours":
   * signed in, just not to a place in this match, so the page stops asking. */
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
        tell(ticket, "you have no place in this match");
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
  keepCurrent(refresh, () => mayFetch() && (!state || !state.isCompleted));
</script>
</body>
</html>
"""
    }
}
