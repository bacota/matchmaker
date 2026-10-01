package com.vivi.boxing

import upickle.default.write
import com.vivi.engine.{LoginConfig, PlayLive, SignIn}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page.
  *
  * A self-contained document with no assets, because the engine has no static hosting. When the viewer already has a
  * corner, the state is inlined into the first render; otherwise the page is a shell that signs the player in and then
  * fetches it.
  *
  * Nothing the page is given discloses a plan the server would not disclose: what is hidden is hidden in
  * `Engine.stateOf`, not here.
  *
  * The sign-in on it is [[com.vivi.engine.SignIn]], the same on every engine, which also serves the page the hosted
  * login redirects back to.
  */
object Html {

    /** The sign-in this page offers, keeping its tokens under the game's name as it always has. */
    val signIn: SignIn = SignIn("boxing")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("boxing")

    def board(
        matchId: String,
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
<title>boxing — ${escape(matchId)}</title>
<style>
  /* --error is 6.3:1 on the light page and 7.8:1 on the dark one; crimson, which it replaces, was 3.6:1
     in dark mode, under the 4.5:1 normal text needs. */
  :root { color-scheme: light dark; --line: #8886; --ink: #222; --paper: #fafafa; --red: #b3261e; --blue: #1f5fae; --error: #b3261e; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --red: #ff8a80; --blue: #8ab4f8; --error: #ff8a80; } }
  body { margin: 0; min-height: 100vh; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { margin: 0 auto; padding: 1.5rem 1rem 3rem; max-width: 34rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; text-align: center; }
  h2 { font-size: 1.125rem; margin: 0 0 .5rem; }
  #status { font-size: 1.5rem; font-weight: 700; margin: 0; min-height: 2rem; text-align: center; }
  #round { margin: 0 0 1.25rem; text-align: center; opacity: .8; min-height: 1.5rem; }
  section { margin: 0 0 1.5rem; }
  section[hidden] { display: none; }
  form .field { display: grid; grid-template-columns: 1fr 6.5rem; align-items: center; gap: .5rem; margin-bottom: .5rem; }
  form .field label { font-weight: 600; }
  form .field .hint { grid-column: 1 / -1; font-size: .875rem; opacity: .75; margin-top: -.375rem; }
  /* 16px on the fields, or iOS zooms the page the moment one takes focus; 44px on everything that
     can be tapped. */
  input[type="number"] { font: inherit; font-size: 16px; min-height: 44px; width: 100%; box-sizing: border-box;
                         padding: .5rem .625rem; border-radius: 6px; border: 1px solid var(--line);
                         background: var(--paper); color: var(--ink); }
  button.primary { font: inherit; font-weight: 600; width: 100%; min-height: 44px; margin-top: .5rem; border-radius: 6px;
                   border: 1px solid var(--line); background: color-mix(in srgb, var(--paper) 80%, var(--ink));
                   color: var(--ink); cursor: pointer; }
  button.primary:disabled { opacity: .45; cursor: default; }
  .left { margin: .5rem 0 0; font-weight: 600; }
  .left.off { color: var(--error); }
  .preview { font-size: .875rem; opacity: .85; margin: .25rem 0 0; }
  :focus-visible { outline: 3px solid seagreen; outline-offset: 2px; }
  #corners { display: grid; grid-template-columns: 1fr 1fr; gap: .75rem; }
  .corner { border: 1px solid var(--line); border-radius: 8px; padding: .75rem; border-top-width: 4px; }
  .corner.Red { border-top-color: var(--red); }
  .corner.Blue { border-top-color: var(--blue); }
  .corner h3 { font-size: 1rem; margin: 0 0 .25rem; }
  .corner dl { display: grid; grid-template-columns: auto auto; gap: 0 .5rem; margin: .25rem 0; font-size: .875rem; }
  .corner dt { opacity: .75; }
  .corner dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
  .corner .who { font-size: .75rem; opacity: .7; overflow-wrap: anywhere; }
  table { width: 100%; border-collapse: collapse; font-size: .875rem; font-variant-numeric: tabular-nums; }
  caption { text-align: left; font-weight: 600; font-size: 1.125rem; margin-bottom: .5rem; }
  th, td { padding: .375rem .25rem; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }
  td.num, th.num { text-align: right; }
  td .plans { display: block; opacity: .7; font-size: .75rem; }
  tfoot td, tfoot th { font-weight: 700; border-bottom: 0; }
${SignIn.css}
${PlayLive.css}
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>boxing</h1>
  <!-- A round resolves while the page is idle rather than in answer to anything this player just
       did, so what happened is announced rather than left to be found. -->
  <p id="status" role="status" aria-live="polite">${escape(heading(state))}</p>
  <p id="round"></p>

  <section id="build" hidden aria-labelledby="build-heading">
    <form id="build-form" novalidate>
      <h2 id="build-heading">Build your fighter</h2>
      <p id="build-intro"></p>
      <div id="build-fields"></div>
      <p id="build-left" class="left" aria-live="polite"></p>
      <button type="submit" class="primary" id="build-submit">Build fighter</button>
    </form>
  </section>

  <section id="plan" hidden aria-labelledby="plan-heading">
    <form id="plan-form" novalidate>
      <h2 id="plan-heading">Plan the round</h2>
      <p id="plan-intro"></p>
      <div id="plan-fields"></div>
      <p id="plan-left" class="left" aria-live="polite"></p>
      <p id="plan-preview" class="preview"></p>
      <button type="submit" class="primary" id="plan-submit">Throw these punches</button>
    </form>
  </section>

  <div id="signin" hidden></div>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>

  <section aria-label="corners"><div id="corners"></div></section>
  <section id="card-section" hidden><table id="card"></table></section>
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
  const fighterUrl = here + "/fighter" + query;

  let state = ${state.map(s => scriptSafe(write(s))).getOrElse("null")};
  // Set by a 403: signed in, but not to a corner of this bout. See `send`.
  let noCorner = false;

  const traits = [
    ["strength", "Strength", "Added twice to power."],
    ["speed", "Speed", "Added twice to offense."],
    ["agility", "Agility", "Added twice to defense."],
    ["workrate", "Workrate", "Points to spend on every round."],
    ["chin", "Chin", "You are knocked out only by power above your defense plus three times this."]
  ];
  const aims = [
    ["offense", "Offense", "Breaks ties, and wins a round where nothing lands."],
    ["defense", "Defense", "What a punch has to beat to land at all."],
    ["power", "Power", "Beats defense for a telling blow, defense plus chin for a knockdown."]
  ];

  /* Built once and then only shown, hidden and relabelled: the page polls, and rebuilding a form
   * on every poll would throw away what the player was typing and move their focus. */
  function fields(box, prefix, rows) {
    const inputs = {};
    rows.forEach(([key, label, hint]) => {
      const row = document.createElement("div");
      row.className = "field";
      row.innerHTML =
        '<label for="' + prefix + key + '">' + label + '</label>' +
        '<input id="' + prefix + key + '" type="number" inputmode="numeric" step="1" required' +
        ' aria-describedby="' + prefix + key + '-hint">' +
        '<span class="hint" id="' + prefix + key + '-hint">' + hint + '</span>';
      box.appendChild(row);
      inputs[key] = row.querySelector("input");
    });
    return inputs;
  }

  const buildInputs = fields(document.getElementById("build-fields"), "b-", traits);
  const planInputs = fields(document.getElementById("plan-fields"), "p-", aims);
  const value = input => { const n = parseInt(input.value, 10); return isNaN(n) ? 0 : n; };

  // Every round starts from an even split, so the form is valid as it stands and a player who
  // only wants to lean one way changes one or two numbers rather than typing three.
  let planPrefilledFor = null;
  let buildPrefilled = false;

  const signin = document.getElementById("signin");
  renderSignIn();

  function mine() {
    return state && state.you ? state.corners.find(c => c.side === state.you) : null;
  }

  function render() {
    const corner = mine();
    const live = !!(state && !state.completed);
    const needsBuild = !!(corner && !corner.fighter && live);
    const canPlan = !!(corner && corner.fighter && live && !state.yourPlan);

    document.getElementById("status").textContent = describe();
    document.getElementById("round").textContent = state
      ? (state.completed ? "Scheduled for " + state.scheduledRounds + " rounds"
                         : "Round " + state.round + " of " + state.scheduledRounds)
      : "";

    const build = document.getElementById("build");
    build.hidden = !needsBuild;
    if (needsBuild) {
      const rules = state.buildRules;
      document.getElementById("build-intro").textContent =
        "This is your fighter's first bout. Spread " + rules.budget + " points over five characteristics, each from " +
        rules.min + " to " + rules.max + ". A fighter is built once and keeps these for every bout.";
      Object.values(buildInputs).forEach(i => { i.min = rules.min; i.max = rules.max; });
      if (!buildPrefilled) {
        const even = Math.floor(rules.budget / traits.length);
        traits.forEach(([key], i) => {
          buildInputs[key].value = even + (i < rules.budget - even * traits.length ? 1 : 0);
        });
        buildPrefilled = true;
      }
      buildLeft();
    }

    const plan = document.getElementById("plan");
    plan.hidden = !canPlan;
    if (canPlan) {
      const workrate = corner.fighter.workrate;
      document.getElementById("plan-heading").textContent = "Plan round " + state.round;
      document.getElementById("plan-intro").textContent =
        "Spend your workrate of " + workrate + " across offense, defense and power. Your opponent is planning too, " +
        "and neither of you sees the other's plan until the round is over.";
      Object.values(planInputs).forEach(i => { i.min = 0; i.max = workrate; });
      if (planPrefilledFor !== state.round) {
        const third = Math.floor(workrate / 3);
        planInputs.offense.value = workrate - 2 * third;
        planInputs.defense.value = third;
        planInputs.power.value = third;
        planPrefilledFor = state.round;
      }
      planLeft();
    }

    // Offered whenever there is a login to start and no corner to show for it — including after a
    // token expires mid-bout, which is what turns a 401 back into a form.
    // Not offered to a player already signed in with no corner here: another sign-in would be the
    // same player, refused the same way.
    signin.hidden = !login || noCorner || !!(state && state.you);

    renderCorners();
    renderCard();
  }

  function buildLeft() {
    if (!state) return;
    const rules = state.buildRules;
    const spent = traits.reduce((sum, [key]) => sum + value(buildInputs[key]), 0);
    const outOfRange = traits.some(([key]) => value(buildInputs[key]) < rules.min || value(buildInputs[key]) > rules.max);
    const left = rules.budget - spent;
    const line = document.getElementById("build-left");
    line.textContent = outOfRange
      ? "Each characteristic must be from " + rules.min + " to " + rules.max + "."
      : left === 0 ? "All " + rules.budget + " points spent." : left > 0 ? left + " points left to spend." : -left + " points too many.";
    line.classList.toggle("off", outOfRange || left !== 0);
    document.getElementById("build-submit").disabled = outOfRange || left !== 0;
  }

  function planLeft() {
    const corner = mine();
    if (!corner || !corner.fighter) return;
    const f = corner.fighter;
    const o = value(planInputs.offense), d = value(planInputs.defense), p = value(planInputs.power);
    const negative = o < 0 || d < 0 || p < 0;
    const left = f.workrate - o - d - p;
    const line = document.getElementById("plan-left");
    line.textContent = negative
      ? "Nothing can be negative."
      : left === 0 ? "All " + f.workrate + " points spent." : left > 0 ? left + " points left to spend." : -left + " points too many.";
    line.classList.toggle("off", negative || left !== 0);
    document.getElementById("plan-submit").disabled = negative || left !== 0;
    // The numbers the round will actually be fought with, so the characteristics' part in them is
    // visible rather than something to work out.
    const defense = d + 2 * f.agility;
    document.getElementById("plan-preview").textContent =
      "In the ring: offense " + (o + 2 * f.speed) + ", defense " + defense + ", power " + (p + 2 * f.strength) +
      ", knocked down by more than " + (defense + f.chin) + ", knocked out by more than " + (defense + 3 * f.chin) + ".";
  }

  Object.values(buildInputs).forEach(i => i.addEventListener("input", buildLeft));
  Object.values(planInputs).forEach(i => i.addEventListener("input", planLeft));

  document.getElementById("build-form").addEventListener("submit", async e => {
    e.preventDefault();
    const body = {};
    traits.forEach(([key]) => { body[key] = value(buildInputs[key]); });
    await submit(fighterUrl, body, document.getElementById("build-submit"));
  });

  document.getElementById("plan-form").addEventListener("submit", async e => {
    e.preventDefault();
    const body = { offense: value(planInputs.offense), defense: value(planInputs.defense), power: value(planInputs.power) };
    await submit(movesUrl, body, document.getElementById("plan-submit"));
  });

  function cornerName(side) {
    return side + " corner" + (state && state.you === side ? " (you)" : "");
  }

  function renderCorners() {
    const box = document.getElementById("corners");
    if (!state) { box.innerHTML = ""; return; }
    box.innerHTML = state.corners.map(c => {
      const stats = c.fighter
        ? "<dl>" + traits.map(([key, label]) => "<dt>" + label + "</dt><dd>" + c.fighter[key] + "</dd>").join("") + "</dl>"
        : "<p>Fighter not built yet.</p>";
      const doing = state.completed ? "" : c.planned ? "Round planned." : c.fighter ? "Planning…" : "Building…";
      return '<div class="corner ' + escapeHtml(c.side) + '"><h3>' + escapeHtml(cornerName(c.side)) + "</h3>" +
        "<p>" + c.points + " points" + (doing ? " · " + doing : "") + "</p>" + stats +
        '<p class="who">' + escapeHtml(c.cognitoId) + "</p></div>";
    }).join("");
  }

  function renderCard() {
    const section = document.getElementById("card-section");
    section.hidden = !state || state.rounds.length === 0;
    if (section.hidden) return;
    const plans = p => p.offense + "/" + p.defense + "/" + p.power;
    const rows = state.rounds.map(r => {
      const how = r.winner ? r.winner + " — " + r.decision : r.decision;
      const score = v => v === null || v === undefined ? "—" : v;
      return '<tr><th scope="row">' + r.number + "</th>" +
        '<td class="num">' + score(r.redPoints) + '</td><td class="num">' + score(r.bluePoints) + "</td>" +
        "<td>" + escapeHtml(how) +
        '<span class="plans">Red ' + plans(r.red) + " · Blue " + plans(r.blue) + " (offense/defense/power)</span></td></tr>";
    }).join("");
    const total = side => state.corners.find(c => c.side === side).points;
    document.getElementById("card").innerHTML =
      "<caption>Scorecard</caption>" +
      '<thead><tr><th scope="col">Round</th><th scope="col" class="num">Red</th><th scope="col" class="num">Blue</th><th scope="col">Result</th></tr></thead>' +
      "<tbody>" + rows + "</tbody>" +
      '<tfoot><tr><th scope="row">Total</th><td class="num">' + total("Red") + '</td><td class="num">' + total("Blue") + "</td><td></td></tr></tfoot>";
  }

  function describe() {
    if (!state) return login ? "sign in to fight" : "not your bout";
    if (state.completed) {
      if (state.draw) return "a draw on points";
      const how = state.method === "knockout" ? " by knockout" : " on points";
      if (!state.you) return state.winner + " wins" + how;
      return (state.winner === state.you ? "you win" : "you lose") + how;
    }
    const corner = mine();
    if (corner && !corner.fighter) return "build your fighter";
    if (corner && !state.yourPlan) return "plan round " + state.round;
    return "waiting for " + state.waitingFor.join(" and ");
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  async function submit(url, body, button) {
    show("");
    button.disabled = true;
    const ticket = ask();
    const response = await send(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    button.disabled = false;
    if (!response) return;
    const answer = await response.json();
    // Kept only if nothing asked for since has been shown: that would already include this.
    if (response.ok) { if (latest(ticket)) state = answer; render(); } else { show(answer.error || response.statusText); render(); }
  }

  async function refresh() {
    const ticket = ask();
    const response = await send(stateUrl, {});
    if (!response || !response.ok) return;
    const answer = await response.json();
    if (latest(ticket)) { state = answer; render(); }
  }

  /* Every call carries the ID token when there is one, and the two refusals mean different things.
   *
   * A 401 is "who are you?": the session is over or never began, so the token is dropped and the
   * page offers a sign-in again. A 403 is "not yours": the player is signed in, just not to a
   * corner of this bout. Their session is kept — it is good for every bout they are in — and the
   * page says so and stops asking, since signing in again would not give them a corner. */
  async function send(url, init) {
    const token = await freshIdToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      if (response.status === 401) {
        if (token) clearSession();
        state = null;
        render();
        show(login ? "sign in to fight this bout" : "say who you are with ?as=<cognito sub>");
        return null;
      }
      if (response.status === 403) {
        noCorner = true;
        state = null;
        render();
        show("you have no corner in this bout");
        return null;
      }
      return response;
    } catch (e) {
      show("could not reach the engine");
      return null;
    }
  }

  function signedIn() { refresh(); }

  /* With a login configured and no session the answer is a 401, so there is no point asking; and a
   * signed-in player with no corner here will be refused every time. */
  function mayFetch() { return !noCorner && (!login || publicView || isSignedIn()); }

  render();
  if (!state && mayFetch()) refresh();
  // Kept current to the end — polled, or with Play Live told: the other corner's plan, and so the
  // round's result, arrive while this page is doing nothing.
  keepCurrent(refresh, () => mayFetch() && (!state || !state.completed));
</script>
</body>
</html>
"""

    /* The heading as first served, before any script runs; `describe()` in the page says the same thing. */
    private def heading(state: Option[Protocol.StateResponse]): String =
        state match {
            case None => "sign in to fight"
            case Some(s) if s.completed =>
                val how = if (s.method.contains("knockout")) " by knockout" else " on points"
                if (s.draw) "a draw on points"
                else
                    s.winner.fold("over") { w =>
                        s.you.fold(s"$w wins$how")(you => (if (you == w) "you win" else "you lose") + how)
                    }
            case Some(s) =>
                val mine = s.you.flatMap(you => s.corners.find(_.side == you))
                if (mine.exists(_.fighter.isEmpty)) "build your fighter"
                else if (mine.isDefined && s.yourPlan.isEmpty) s"plan round ${s.round}"
                else s"waiting for ${s.waitingFor.mkString(" and ")}"
        }
}
