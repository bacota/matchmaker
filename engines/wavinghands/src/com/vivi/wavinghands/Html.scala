package com.vivi.wavinghands

import upickle.default.write
import com.vivi.engine.{Finale, LoginConfig, PlayLive, SignIn, TurnTimer}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page: a self-contained document, as every engine's is, rendered from [[Protocol.StateResponse]].
  *
  * What a viewer may not know is kept out of the state by `Engine.stateOf`, not hidden here: the orders given for the
  * turn being played never reach the page, only that they were given.
  *
  * The orders form is built once per turn and left alone while the page keeps itself current, so a poll arriving while
  * a player is choosing their gestures does not throw their choices away.
  */
object Html {

    val signIn: SignIn = SignIn("wavinghands")

    val playLive: PlayLive = PlayLive("wavinghands")

    def board(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String =
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
  :root { color-scheme: light dark; --line: #8886; --ink: #222; --paper: #fafafa; --soft: #8881; --error: #b3261e;
          --accent: #5b3cc4; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; --accent: #b9a6ff; } }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--paper); color: var(--ink); font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { max-width: 48rem; margin: 0 auto; padding: 1.5rem 1rem 3rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  h2 { font-size: 1.125rem; margin: 1.75rem 0 .5rem; }
  #status { font-size: 1.375rem; font-weight: 700; margin: 0 0 1rem; min-height: 2rem; }
  .cards { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr)); }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .75rem; background: var(--soft); }
  .card h3 { margin: 0 0 .25rem; font-size: 1rem; }
  .card .meta { font-size: .875rem; opacity: .85; }
  .bar { height: .5rem; border-radius: 4px; background: var(--line); overflow: hidden; margin: .375rem 0; }
  .bar span { display: block; height: 100%; background: var(--error); }
  .tag { display: inline-block; font-size: .8125rem; border: 1px solid var(--line); border-radius: 999px; padding: 0 .5rem; margin: .125rem .25rem 0 0; }
  form { border: 1px solid var(--line); border-radius: 10px; padding: 1rem; }
  fieldset { border: 0; margin: 0 0 .75rem; padding: 0; }
  legend { font-weight: 600; margin-bottom: .25rem; }
  label { display: block; margin: .5rem 0 .25rem; font-size: .9375rem; }
  /* 16px and 44px: smaller fields make phones zoom on focus, and these are tapped. */
  select, button { font: inherit; font-size: 16px; min-height: 44px; width: 100%; max-width: 22rem; padding: .5rem;
                   border-radius: 8px; border: 1px solid var(--line); background: var(--paper); color: var(--ink); }
  button.primary { background: var(--accent); color: var(--paper); border-color: var(--accent); font-weight: 600; margin-top: .75rem; cursor: pointer; }
  .hands { display: grid; gap: .5rem 1rem; grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr)); }
  .note { font-size: .875rem; opacity: .85; margin: .25rem 0; }
  .warn { color: var(--error); font-weight: 600; }
  .scroll { overflow-x: auto; }
  table { border-collapse: collapse; font-size: .875rem; }
  th, td { padding: .25rem .5rem; border-bottom: 1px solid var(--line); text-align: left; white-space: nowrap; }
  td.g { font-family: ui-monospace, monospace; text-align: center; }
  ol.events { margin: .25rem 0 1rem; padding-left: 1.25rem; }
  details { margin: .5rem 0; }
  summary { cursor: pointer; min-height: 44px; display: flex; align-items: center; list-style: none; }
  summary::-webkit-details-marker { display: none; }
  summary::before { content: "▸"; display: inline-block; width: 1.25rem; }
  details[open] > summary::before { content: "▾"; }
  :focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
${Finale.css}
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .9375rem; }
</style>
</head>
<body>
<main>
  <h1>waving hands</h1>
  <p id="status" role="status" aria-live="polite">${escape(heading(state))}</p>
  ${TurnTimer.markup}
  ${Finale.markup}
  <div id="signin" hidden></div>
  <section aria-labelledby="wizards-h"><h2 id="wizards-h">Wizards</h2><div id="wizards" class="cards"></div></section>
  <section id="monsters-s" aria-labelledby="monsters-h" hidden><h2 id="monsters-h">Monsters</h2><div id="monsters" class="cards"></div></section>
  <section id="orders-s" aria-labelledby="orders-h" hidden><h2 id="orders-h">Your orders</h2><div id="orders"></div></section>
  <div id="error" role="alert"></div>
  <section aria-labelledby="history-h"><h2 id="history-h">Gestures</h2><div id="history" class="scroll"></div></section>
  <section aria-labelledby="reports-h"><h2 id="reports-h">What happened</h2><div id="reports"></div></section>
  <details><summary>Spells</summary><div class="scroll" id="spells"></div></details>
  ${PlayLive.markup}
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
  // Which turn the orders form was built for, so a poll does not rebuild it under the player.
  let formFor = null;

  const gestureNames = { "F": "F — fingers", "P": "P — palm", "S": "S — snap", "W": "W — wave", "D": "D — digit",
                         "C": "C — clap (both hands)", "stab": "stab", "-": "nothing" };

  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);
  const showFinale = finale();

  function esc(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }
  /* The viewer's participant id as a number, which is how every other id here is written. */
  function youId() { return state && state.you != null ? Number(state.you) : null; }
  function nameOf(id) { const w = state.wizards.find(x => x.participantId === id); return w ? w.name : "someone"; }
  function show(message) { document.getElementById("error").textContent = message || ""; }

  function describe() {
    if (!state) return login ? "sign in to play" : "not your duel";
    if (state.isCompleted) {
      if (state.isDrawn) return "a draw between " + state.winners.map(nameOf).join(" and ");
      if (!state.winners.length) return "nobody wins";
      const w = state.winners[0];
      if (youId() === w) return "you win";
      return nameOf(w) + " wins";
    }
    const me = state.wizards.find(x => x.participantId === youId());
    if (me && me.isDead) return "you have died — turn " + state.turn;
    if (me && me.isSurrendered) return "you have surrendered — turn " + state.turn;
    if (state.yourTurn && !state.yourTurn.isOrdered) return "turn " + state.turn + ": give your orders";
    return "turn " + state.turn + ": waiting for " + state.waitingFor.map(nameOf).join(", ");
  }

  function result() {
    if (!state || !state.isCompleted || state.you == null || publicView || state.isDrawn) return null;
    return state.winners.includes(youId()) ? "win" : "lose";
  }

  function render() {
    document.getElementById("status").textContent = describe();
    showClock(state && state.clock, youId());
    showFinale(result());
    signin.hidden = !login || noSeat || (state && state.you != null) || publicView;
    if (!state) return;
    renderWizards();
    renderMonsters();
    renderOrders();
    renderHistory();
    renderReports();
    renderSpells();
  }

  function renderWizards() {
    document.getElementById("wizards").innerHTML = state.wizards.map(w => {
      const you = w.participantId === youId() ? " (you)" : "";
      const tags = [];
      if (w.isDead) tags.push("dead");
      else if (w.isSurrendered) tags.push("surrendered");
      else if (!state.isCompleted) tags.push(w.hasOrdered ? "orders given" : "thinking");
      w.effects.forEach(e => tags.push(e));
      const pct = Math.min(100, Math.round(w.damage * 100 / 15));
      return '<div class="card"><h3>' + esc(w.name) + you + '</h3>' +
        '<div class="meta">damage ' + w.damage + ' of 15</div>' +
        '<div class="bar" role="img" aria-label="' + w.damage + ' of 15 damage"><span style="width:' + pct + '%"></span></div>' +
        tags.map(t => '<span class="tag">' + esc(t) + '</span>').join("") + '</div>';
    }).join("");
  }

  function renderMonsters() {
    const alive = state.monsters.filter(m => !m.isDead);
    document.getElementById("monsters-s").hidden = !alive.length;
    document.getElementById("monsters").innerHTML = alive.map(m =>
      '<div class="card"><h3>' + esc(nameOf(m.controller)) + "'s " + esc(m.kind) + ' (#' + m.id + ')</h3>' +
      '<div class="meta">hits for ' + m.strength + ' · damage ' + m.damage + ' of ' + m.strength + '</div></div>'
    ).join("");
  }

  function renderHistory() {
    const turns = Math.max(0, ...state.wizards.map(w => w.left.length));
    if (!turns) { document.getElementById("history").innerHTML = '<p class="note">No gestures yet.</p>'; return; }
    let html = '<table><caption class="note" style="text-align:left">Left and right hand, turn by turn</caption><thead><tr><th scope="col">Turn</th>' +
      state.wizards.map(w => '<th scope="col" colspan="2">' + esc(w.name) + '</th>').join("") + '</tr></thead><tbody>';
    for (let t = turns - 1; t >= 0; t--) {
      html += '<tr><th scope="row">' + (t + 1) + '</th>' + state.wizards.map(w =>
        '<td class="g">' + esc(w.left[t] || "") + '</td><td class="g">' + esc(w.right[t] || "") + '</td>').join("") + '</tr>';
    }
    document.getElementById("history").innerHTML = html + '</tbody></table>';
  }

  function renderReports() {
    if (!state.reports.length) { document.getElementById("reports").innerHTML = '<p class="note">Nothing yet.</p>'; return; }
    document.getElementById("reports").innerHTML = state.reports.map((r, i) => {
      const list = '<ol class="events">' + (r.events.length ? r.events.map(e => '<li>' + esc(e) + '</li>').join("") : '<li>Nothing happened.</li>') + '</ol>';
      return i === 0 ? '<h3 style="font-size:1rem;margin:.5rem 0 0">Turn ' + r.turn + '</h3>' + list
                     : '<details><summary>Turn ' + r.turn + '</summary>' + list + '</details>';
    }).join("");
  }

  function renderSpells() {
    const el = document.getElementById("spells");
    if (el.dataset.done) return;
    el.dataset.done = "1";
    el.innerHTML = '<table><thead><tr><th scope="col">Spell</th><th scope="col">Gestures</th><th scope="col">Effect</th></tr></thead><tbody>' +
      state.spells.map(s => '<tr><td>' + esc(s.name) + '</td><td class="g">' + s.series.map(esc).join(" or ") +
        '</td><td style="white-space:normal">' + esc(s.about) + '</td></tr>').join("") +
      '</tbody></table><p class="note">Upper case is one hand; "(w" is a wave with both hands; C is always both hands. ' +
      'Two palms at once surrenders.</p>';
  }

  // ---- orders ------------------------------------------------------------------------

  function select(id, label, options, value) {
    return '<label for="' + id + '">' + esc(label) + '</label><select id="' + id + '">' +
      options.map(([v, text]) => '<option value="' + esc(v) + '"' + (v === value ? " selected" : "") + '>' + esc(text) + '</option>').join("") +
      '</select>';
  }

  function renderOrders() {
    const section = document.getElementById("orders-s");
    const yt = state.yourTurn;
    if (!yt || state.isCompleted) { section.hidden = true; formFor = null; return; }
    section.hidden = false;
    const box = document.getElementById("orders");
    if (yt.isOrdered) {
      formFor = null;
      box.innerHTML = '<p>Your orders for turn ' + state.turn + ' are in. Waiting for ' +
        esc(state.waitingFor.map(nameOf).join(", ") || "the turn to resolve") + '.</p>';
      return;
    }
    if (formFor === state.turn) return;
    formFor = state.turn;
    const notes = yt.notes.map(n => '<p class="note">' + esc(n) + '</p>').join("");
    box.innerHTML = '<form id="orders-form" novalidate>' + notes +
      '<fieldset><legend>Gestures</legend><div class="hands"><div>' +
      select("left", "Left hand", yt.left.map(g => [g, gestureNames[g] || g]), startingGesture(yt.left)) + '</div><div>' +
      select("right", "Right hand", yt.right.map(g => [g, gestureNames[g] || g]), startingGesture(yt.right)) +
      '</div></div><p id="surrender" class="warn" role="status" aria-live="polite"></p></fieldset>' +
      '<div id="spells-cast" aria-live="polite"></div><div id="stab-box"></div><div id="monster-orders"></div>' +
      '<button class="primary" type="submit">Give orders</button></form>';
    const form = document.getElementById("orders-form");
    form.addEventListener("change", e => { if (e.target.id === "left" || e.target.id === "right" || e.target.id === "casting") renderCasting(); });
    form.addEventListener("submit", e => { e.preventDefault(); give(); });
    renderCasting();
    renderMonsterOrders();
  }

  /* What a hand's select starts on: nothing, which completes no spell by accident — or the one gesture a forced hand
   * has. */
  function startingGesture(allowed) { return allowed.includes("-") ? "-" : allowed[0]; }

  function targetOptions(excludeSelf) {
    return state.yourTurn.targets.filter(t => !(excludeSelf && t.code === "wizard:" + state.you)).map(t => [t.code, t.label]);
  }

  function chosenCasting() {
    const yt = state.yourTurn;
    const l = document.getElementById("left").value, r = document.getElementById("right").value;
    const options = yt.castings[l + "," + r] || [];
    const picker = document.getElementById("casting");
    const key = picker ? picker.value : null;
    return { l, r, options, casting: options.find(c => c.key === key) || biggest(options) };
  }

  /* The casting made when the wizard picks none: the server lists the castings with it first. */
  function biggest(options) { return options.length ? options[0] : null; }

  function renderCasting() {
    const yt = state.yourTurn;
    const l = document.getElementById("left").value, r = document.getElementById("right").value;
    document.getElementById("surrender").textContent =
      (l === "P" && r === "P") ? "Two palms at once: you will surrender." : "";
    const options = yt.castings[l + "," + r] || [];
    const box = document.getElementById("spells-cast");
    const previous = document.getElementById("casting");
    const keep = previous ? previous.value : null;
    let html = "";
    if (options.length > 1) {
      html += select("casting", "These gestures complete more than one spell. Cast:", options.map(c =>
        [c.key, c.spells.map(s => s.name + " (" + s.slot + ")").join(" + ")]), options.some(c => c.key === keep) ? keep : biggest(options).key);
    }
    box.innerHTML = html;
    const casting = chosenCasting().casting;
    let more = "";
    if (!casting || !casting.spells.length) more = '<p class="note">These gestures complete no spell.</p>';
    else casting.spells.forEach(s => {
      more += '<fieldset><legend>' + esc(s.name) + ' — ' + esc(s.slot === "both" ? "both hands" : s.slot + " hand") + '</legend>' +
        select("target-" + s.slot, "Cast at", targetOptions(false), s.defaultTarget);
      if (s.isParalysis) more += select("hand-" + s.slot, "Paralyse which hand", [["left", "left hand"], ["right", "right hand"]], "left");
      if (s.isSummons) more += select("summons-" + s.slot, "The new monster attacks", targetOptions(true), yt.defaultEnemy || "nobody");
      more += '</fieldset>';
    });
    box.insertAdjacentHTML("beforeend", more);
    document.getElementById("stab-box").innerHTML = (l === "stab" || r === "stab")
      ? '<fieldset><legend>Stab</legend>' + select("stab", "Stab at", targetOptions(true), yt.defaultEnemy || "nobody") + '</fieldset>'
      : "";
  }

  function renderMonsterOrders() {
    const yt = state.yourTurn;
    document.getElementById("monster-orders").innerHTML = yt.monsters.length
      ? '<fieldset><legend>Your monsters</legend>' + yt.monsters.map(id => {
          const m = state.monsters.find(x => x.id === id);
          return select("monster-" + id, (m ? m.kind : "monster") + " #" + id + " attacks", targetOptions(true), yt.defaultEnemy || "nobody");
        }).join("") + '</fieldset>'
      : "";
  }

  function value(id) { const el = document.getElementById(id); return el ? el.value : null; }

  async function give() {
    show("");
    const { l, r, casting } = chosenCasting();
    const body = { left: l, right: r, targets: {}, hands: {}, summons: {}, monsters: {} };
    if (casting) {
      body.casting = casting.key;
      casting.spells.forEach(s => {
        body.targets[s.slot] = value("target-" + s.slot);
        if (s.isParalysis) body.hands[s.slot] = value("hand-" + s.slot);
        if (s.isSummons) body.summons[s.slot] = value("summons-" + s.slot);
      });
    }
    if (value("stab")) body.stab = value("stab");
    state.yourTurn.monsters.forEach(id => { body.monsters[String(id)] = value("monster-" + id); });
    const ticket = ask();
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
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
        tell(ticket, login ? "sign in to play this duel" : "say who you are with ?as=<cognito sub>");
        return null;
      }
      if (response.status === 403) {
        if (!latest(ticket)) return null;
        noSeat = true;
        state = null;
        render();
        tell(ticket, "you have no place in this duel");
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

    /** The heading the page is served with, before any script runs. */
    private def heading(state: Option[Protocol.StateResponse]): String =
        state match {
            case None                                       => "sign in to play"
            case Some(s) if s.isCompleted                   => "the duel is over"
            case Some(s) if s.yourTurn.exists(!_.isOrdered) => s"turn ${s.turn}: give your orders"
            case Some(s)                                    => s"turn ${s.turn}"
        }
}
