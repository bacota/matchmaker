package com.vivi.boxing

import upickle.default.write
import com.vivi.engine.{Finale, LoginConfig, PlayLive, SignIn, TurnTimer}
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

    /** The winner: a boxer with a glove raised. Drawn in the page's ink, so it reads in either theme, with red gloves
      * and trunks.
      */
    val victory: Finale.Art = Finale.Art(
      Finale.picture(
        "A boxer with one glove raised in victory",
        Finale.withConfetti(
          """<g fill="currentColor">
  <circle cx="100" cy="62" r="18"/>
  <path d="M76 86 Q100 78 124 86 L116 142 L84 142 Z"/>
  <rect x="84" y="164" width="13" height="44" rx="5"/><rect x="103" y="164" width="13" height="44" rx="5"/>
</g>
<g stroke="currentColor" stroke-width="12" stroke-linecap="round" fill="none">
  <path d="M80 90 L66 60 L62 38"/>
  <path d="M120 92 L134 116 L138 128"/>
</g>
<g fill="#c62828">
  <circle cx="61" cy="28" r="14"/>
  <circle cx="139" cy="136" r="12"/>
  <path d="M82 140 H118 L120 168 H103 L100 154 L97 168 H80 Z"/>
</g>"""
        )
      ),
      "Winner! Your hand is raised."
    )

    /** The loser: slumped on the stool in the corner, a towel over the head. */
    val defeat: Finale.Art = Finale.Art(
      Finale.picture(
        "A boxer sitting on the stool in his corner, a towel over his head",
        """<g stroke="currentColor" stroke-width="5" stroke-linecap="round">
  <line x1="64" y1="158" x2="58" y2="208"/><line x1="136" y1="158" x2="142" y2="208"/>
</g>
<rect x="56" y="150" width="88" height="10" rx="3" fill="currentColor"/>
<g fill="currentColor">
  <path d="M82 106 Q100 100 118 106 L114 152 L86 152 Z"/>
  <rect x="72" y="160" width="14" height="46" rx="5"/><rect x="114" y="160" width="14" height="46" rx="5"/>
</g>
<g stroke="currentColor" stroke-width="11" stroke-linecap="round" fill="none">
  <path d="M86 110 L78 150"/><path d="M114 110 L122 150"/>
</g>
<circle cx="100" cy="103" r="16" fill="currentColor"/>
<path d="M74 122 Q72 74 100 70 Q128 74 126 122 L118 126 Q116 92 100 88 Q84 92 82 126 Z"
      fill="#f4f4f4" stroke="#777" stroke-width="2" stroke-linejoin="round"/>
<g fill="#c62828"><circle cx="77" cy="156" r="11"/><circle cx="123" cy="156" r="11"/></g>"""
      ),
      "You went the distance. Rest up — there's always a rematch."
    )

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
${TurnTimer.css}
${Finale.css}
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
  ${TurnTimer.markup}
  ${Finale.markup}

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
${TurnTimer.script}
${Finale.script(victory, defeat)}

  const publicView = $publicView;
  // Urls are derived from this page's own, not built from a base: behind API Gateway the path
  // carries a stage prefix, and a page that assumed "/matches/..." would 404 there.
  const here = location.pathname.replace(new RegExp("/(play|board)$$"), "");
  // The page's own query goes along too: in the trusted local mode `?as=` is who the player is,
  // and a fetch without it would be nobody's. Deployed, there is no query and this adds nothing.
  const query = location.search;
  const stateUrl = (publicView ? here + "/board/state" : here + "/state") + query;
  const movesUrl = here + "/moves" + query;

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

  /* Built once and then only shown, hidden and relabelled: the page polls, and rebuilding the form
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

  const planInputs = fields(document.getElementById("plan-fields"), "p-", aims);
  const value = input => { const n = parseInt(input.value, 10); return isNaN(n) ? 0 : n; };

  // Every round starts from an even split, so the form is valid as it stands and a player who
  // only wants to lean one way changes one or two numbers rather than typing three.
  let planPrefilledFor = null;

  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);
  const showFinale = finale();

  function mine() {
    return state && state.you ? state.corners.find(c => c.side === state.you) : null;
  }

  function render() {
    const corner = mine();
    const live = !!(state && !state.completed);
    const canPlan = !!(corner && corner.fighter && live && !state.yourPlan);

    document.getElementById("status").textContent = describe();
    showClock(state && state.clock, corner ? corner.participantId : null);
    showFinale(result());
    document.getElementById("round").textContent = state
      ? (state.completed ? "Scheduled for " + state.scheduledRounds + " rounds"
                         : "Round " + state.round + " of " + state.scheduledRounds)
      : "";

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

  Object.values(planInputs).forEach(i => i.addEventListener("input", planLeft));

  document.getElementById("plan-form").addEventListener("submit", async e => {
    e.preventDefault();
    const body = { offense: value(planInputs.offense), defense: value(planInputs.defense), power: value(planInputs.power) };
    await submit(movesUrl, body, document.getElementById("plan-submit"));
  });

  /* "Looks powerful and sluggish." — the words as a sentence. */
  function looks(words) {
    if (!words.length) return "Nothing about this fighter stands out.";
    const list = words.length === 1 ? words[0] : words.slice(0, -1).join(", ") + " and " + words[words.length - 1];
    return "Looks " + list + ".";
  }

  function cornerName(side) {
    return side + " corner" + (state && state.you === side ? " (you)" : "");
  }

  function renderCorners() {
    const box = document.getElementById("corners");
    if (!state) { box.innerHTML = ""; return; }
    box.innerHTML = state.corners.map(c => {
      // Your own fighter's numbers; of anybody else's, only what can be seen from across the ring.
      const stats = c.fighter
        ? "<dl>" + traits.map(([key, label]) => "<dt>" + label + "</dt><dd>" + c.fighter[key] + "</dd>").join("") + "</dl>"
        : '<p class="looks">' + escapeHtml(looks(c.impression || [])) + "</p>";
      const doing = state.completed ? "" : c.planned ? "Round planned." : "Planning…";
      return '<div class="corner ' + escapeHtml(c.side) + '"><h3>' + escapeHtml(cornerName(c.side)) + "</h3>" +
        "<p>" + c.points + " points" + (doing ? " · " + doing : "") + "</p>" + stats +
        // By nickname, never the sign-in id, which means nothing to anybody; a bout created before
        // nicknames were kept has none to show.
        (c.nickname ? '<p class="who">' + escapeHtml(c.nickname) + "</p>" : "") + "</div>";
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

  /* "win" or "lose" for the corner viewing a finished bout, and nothing for a draw, a bout still
   * being fought, or the public board. Decided as `describe` decides it, so the picture and the
   * words never disagree: a forfeit by whoever ran out of time, otherwise by the winner. */
  function result() {
    if (!state || !state.completed || !state.you || publicView) return null;
    if (state.method === "forfeit") {
      const late = state.corners.filter(c => state.clock && state.clock.timedOut.includes(c.participantId)).map(c => c.side);
      if (late.length) return late.includes(state.you) ? "lose" : "win";
    }
    if (state.draw || !state.winner) return null;
    return state.winner === state.you ? "win" : "lose";
  }

  function describe() {
    if (!state) return login ? "sign in to fight" : "not your bout";
    if (state.completed) {
      if (state.method === "forfeit") {
        const late = state.corners.filter(c => state.clock && state.clock.timedOut.includes(c.participantId)).map(c => c.side);
        if (!state.you) return late.join(" and ") + " ran out of time";
        return late.includes(state.you) ? "your time ran out — you lose" : "their time ran out — you win";
      }
      if (state.draw) return "a draw on points";
      const how = state.method === "knockout" ? " by knockout" : " on points";
      if (!state.you) return state.winner + " wins" + how;
      return (state.winner === state.you ? "you win" : "you lose") + how;
    }
    const corner = mine();
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
    const response = await send(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
    button.disabled = false;
    if (!response) return;
    const answer = await response.json();
    // A refusal is dropped under a newer state, which says more; see PlayLive for the two orders.
    if (!response.ok) { if (!overtaken(ticket)) tell(ticket, answer.error || response.statusText); render(); return; }
    if (latest(ticket)) state = answer;
    // Clears an earlier submission's refusal, but not a later one's: that is still the news.
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

  /* Every call carries the ID token when there is one, and the two refusals mean different things.
   *
   * A 401 is "who are you?": the session is over or never began, so the token is dropped and the
   * page offers a sign-in again. A 403 is "not yours": the player is signed in, just not to a
   * corner of this bout. Their session is kept — it is good for every bout they are in — and the
   * page says so and stops asking, since signing in again would not give them a corner. */
  /* `ticket` is the caller's, from `ask()`: a refusal answered here is ordered like any other
   * answer, so a late 401 or 403 cannot blank a board a newer request has already shown, nor a
   * stale failure overwrite a newer message. If the session really is over, the next request is
   * refused too, with a newer ticket, and that one is acted on. */
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
        tell(ticket, login ? "sign in to fight this bout" : "say who you are with ?as=<cognito sub>");
        return null;
      }
      if (response.status === 403) {
        if (!latest(ticket)) return null;
        noCorner = true;
        state = null;
        render();
        tell(ticket, "you have no corner in this bout");
        return null;
      }
      return response;
    } catch (e) {
      if (!overtaken(ticket)) tell(ticket, "could not reach the engine");
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

    /** The fighters page: a player's fighters, each of which they may edit here, and the form a new one is built with.
      * Giving one away has its route but is not offered on the page yet. Every change to a fighter is made in this
      * engine; matchmaker is told of each, and is where the list comes from, since this engine keeps no fighters of its
      * own.
      *
      * Served to anyone, like the play page, and for the same reason: a browser navigation carries no token, so this is
      * the shell that signs the player in and then fetches and posts with one. Without a login configured — the local,
      * trusted mode — it goes straight on, and the player is whoever `?as=` says.
      */
    def fightersPage(login: Option[LoginConfig], rules: Protocol.BuildRules): String =
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>boxing — your fighters</title>
<style>
  :root { color-scheme: light dark; --line: #8886; --ink: #222; --paper: #fafafa; --error: #b3261e; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; } }
  body { margin: 0; min-height: 100vh; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { margin: 0 auto; padding: 1.5rem 1rem 3rem; max-width: 34rem; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; text-align: center; }
  h2 { font-size: 1.25rem; margin: 1.5rem 0 .5rem; }
  h3 { font-size: 1.0625rem; margin: 0 0 .25rem; overflow-wrap: anywhere; }
  section[hidden], div[hidden], p[hidden] { display: none; }
  form .field { display: grid; grid-template-columns: 1fr 6.5rem; align-items: center; gap: .5rem; margin-bottom: .5rem; }
  form .field.wide { grid-template-columns: 1fr; gap: .25rem; }
  form .field label { font-weight: 600; }
  form .field .hint { grid-column: 1 / -1; font-size: .875rem; opacity: .75; margin-top: -.375rem; }
  /* 16px on the fields, or iOS zooms the page the moment one takes focus; 44px on everything that
     can be tapped. */
  input[type="number"], input[type="text"] { font: inherit; font-size: 16px; min-height: 44px; width: 100%; box-sizing: border-box;
                         padding: .5rem .625rem; border-radius: 6px; border: 1px solid var(--line);
                         background: var(--paper); color: var(--ink); }
  button.primary { font: inherit; font-weight: 600; width: 100%; min-height: 44px; margin-top: .5rem; border-radius: 6px;
                   border: 1px solid var(--line); background: color-mix(in srgb, var(--paper) 80%, var(--ink));
                   color: var(--ink); cursor: pointer; }
  button.primary:disabled { opacity: .45; cursor: default; }
  .left { margin: .5rem 0 0; font-weight: 600; }
  .left.off { color: var(--error); }
  #fighters { list-style: none; margin: 0; padding: 0; display: grid; gap: .75rem; }
  #fighters li { border: 1px solid var(--line); border-radius: 8px; padding: .75rem; }
  #fighters .about { margin: 0 0 .25rem; opacity: .8; overflow-wrap: anywhere; }
  /* Each characteristic and its number kept together, as a pair, and the pairs wrapped as the width allows: laid out
     in columns instead, the names and numbers of five characteristics come apart on a phone. */
  #fighters dl { display: flex; flex-wrap: wrap; gap: .125rem 1rem; margin: .25rem 0 .5rem; font-size: .875rem; }
  #fighters dl > div { display: flex; gap: .375rem; white-space: nowrap; }
  #fighters dt { opacity: .75; }
  #fighters dd { margin: 0; font-weight: 600; font-variant-numeric: tabular-nums; }
  /* A characteristic's explanation sits behind a "?" beside its name. The tip opens below the row,
     no wider than it, so on a phone it stays on the screen: on hover where there is a mouse,
     on keyboard focus, and on a tap (`.open`), the only one of the three a touch screen has. */
  form .field.trait { position: relative; }
  .caption { display: flex; align-items: center; gap: .125rem; }
  .tip-toggle { min-width: 44px; min-height: 44px; padding: 0; border: none; background: none; color: inherit;
                font: inherit; font-weight: 700; cursor: help; }
  .tip { display: none; position: absolute; top: 100%; left: 0; right: 0; z-index: 10; max-width: 22rem; padding: .5rem .75rem; border: 1px solid var(--line);
         border-radius: 6px; background: var(--paper); color: var(--ink); font-size: .875rem; line-height: 1.4;
         box-shadow: 0 2px 8px #0004; }
  .tip.open, .tip:hover:not(.dismissed), .tip-toggle:hover + .tip:not(.dismissed), .tip-toggle:focus-visible + .tip:not(.dismissed) { display: block; }
  .detail { opacity: .8; }
  :focus-visible { outline: 3px solid seagreen; outline-offset: 2px; }
${SignIn.css}
  #status { min-height: 1.5rem; margin: .75rem 0 0; font-weight: 600; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
<main>
  <h1>boxing</h1>

  <!-- What the last change or build did. Focus is moved here when it is said, since the list it is
       about is redrawn; it is a live region as well, for readers that do not follow focus. -->
  <p id="status" role="status" aria-live="polite" tabindex="-1"></p>
  <div id="error" role="alert"></div>

  <div id="content" hidden>
    <section aria-labelledby="yours-heading">
      <h2 id="yours-heading">Your fighters</h2>
      <p id="yours-note" class="detail">Loading your fighters…</p>
      <ul id="fighters"></ul>
    </section>

    <section aria-labelledby="build-heading">
      <form id="build-form" novalidate>
        <h2 id="build-heading">Build a new fighter</h2>
        <p>Spread ${rules.budget} points across five characteristics, each from ${rules.min} to ${rules.max}.
           A fighter is built once: these are the numbers it fights every bout with. Its name and description you can change later.</p>
        <div class="field wide">
          <label for="f-name">Name</label>
          <input id="f-name" type="text" autocomplete="off" required maxlength="80">
        </div>
        <div class="field wide">
          <label for="f-description">Description <span class="hint">(optional)</span></label>
          <input id="f-description" type="text" autocomplete="off" maxlength="280">
        </div>
        <div id="build-fields"></div>
        <p id="build-left" class="left" aria-live="polite"></p>
        <button type="submit" class="primary" id="build-submit">Build this fighter</button>
      </form>
    </section>
  </div>

  <div id="signin" hidden></div>
</main>
<script>
${signIn.authScript(login)}
${signIn.signInScript}

  const rules = { budget: ${rules.budget}, min: ${rules.min}, max: ${rules.max} };
  // Derived from this page's own url, as the board's are: behind API Gateway the path carries a
  // stage prefix. The query goes along for the local `?as=`.
  const base = location.pathname.replace(new RegExp("/+$$"), "");
  const query = location.search;
  const mineUrl = base + "/mine" + query;
  const buildUrl = base + query;
  const fighterUrl = id => base + "/" + encodeURIComponent(id) + query;
  const ownerUrl = id => base + "/" + encodeURIComponent(id) + "/owner" + query;

  const traits = [
    ["strength", "Strength", "Added twice to power."],
    ["speed", "Speed", "Added twice to offense."],
    ["agility", "Agility", "Added twice to defense."],
    ["workrate", "Workrate", "Points to spend on every round."],
    ["chin", "Chin", "You are knocked out only by power above your defense plus three times this."]
  ];

  /* Each characteristic's explanation is a tip behind a "?" rather than a line under it. It still
   * describes the field (aria-describedby reads a hidden element too), so a screen reader hears it
   * on reaching the field without opening anything. */
  const inputs = {};
  const box = document.getElementById("build-fields");
  traits.forEach(([key, label, hint]) => {
    const row = document.createElement("div");
    row.className = "field trait";
    row.innerHTML =
      '<span class="caption"><label for="f-' + key + '">' + label + '</label>' +
      '<button type="button" class="tip-toggle" aria-label="About ' + label + '" aria-expanded="false"' +
      ' aria-controls="f-' + key + '-hint">?</button>' +
      '<span class="tip" role="tooltip" id="f-' + key + '-hint">' + hint + '</span></span>' +
      '<input id="f-' + key + '" type="number" inputmode="numeric" step="1" required min="' + rules.min +
      '" max="' + rules.max + '" aria-describedby="f-' + key + '-hint">';
    box.appendChild(row);
    inputs[key] = row.querySelector("input");
    tip(row.querySelector(".tip-toggle"), row.querySelector(".tip"));
  });

  /* A tap toggles the tip open and leaving the button closes it. Escape hides whatever tip is
   * showing, including one shown by hover or keyboard focus, which `.open` knows nothing about:
   * `.dismissed` holds it hidden, with focus left where it was, until the pointer or focus arrives
   * afresh. */
  function tip(toggle, text) {
    const setOpen = open => { text.classList.toggle("open", open); toggle.setAttribute("aria-expanded", String(open)); };
    const fresh = () => text.classList.remove("dismissed");
    toggle.addEventListener("click", () => { fresh(); setOpen(!text.classList.contains("open")); });
    toggle.addEventListener("focus", fresh);
    toggle.addEventListener("mouseenter", fresh);
    toggle.addEventListener("blur", () => setOpen(false));
    document.addEventListener("keydown", e => {
      if (e.key !== "Escape" || text.classList.contains("dismissed")) return;
      if (text.classList.contains("open") || toggle.matches(":focus-visible") || toggle.matches(":hover")) {
        setOpen(false);
        text.classList.add("dismissed");
      }
    });
  }
  const nameInput = document.getElementById("f-name");
  const descriptionInput = document.getElementById("f-description");

  function value(input) { const n = parseInt(input.value, 10); return isNaN(n) ? 0 : n; }

  // An even spread to start from, so the form is valid as it stands and a player who wants a
  // particular kind of fighter moves points rather than typing five numbers.
  function reset() {
    const each = Math.floor(rules.budget / traits.length);
    traits.forEach(([key], i) => { inputs[key].value = each + (i < rules.budget - each * traits.length ? 1 : 0); });
    nameInput.value = "";
    descriptionInput.value = "";
    left();
  }

  /* True while a build is on its way to the engine. Editing a field re-runs `left()`, which would
   * otherwise re-enable the button mid-request and let a second click register a second fighter;
   * the submit handler checks it too, since Enter in a field submits without the button. Not the
   * sign-in's `busy`, which is about the sign-in form. */
  let building = false;

  function left() {
    const values = traits.map(([key]) => value(inputs[key]));
    const out = traits.find(([key]) => value(inputs[key]) < rules.min || value(inputs[key]) > rules.max);
    const remaining = rules.budget - values.reduce((a, b) => a + b, 0);
    const line = document.getElementById("build-left");
    line.textContent = out
      ? out[1] + " must be from " + rules.min + " to " + rules.max + "."
      : remaining === 0 ? "All " + rules.budget + " points spent."
      : remaining > 0 ? remaining + " points left to spend." : -remaining + " points too many.";
    const bad = !!out || remaining !== 0;
    line.classList.toggle("off", bad);
    document.getElementById("build-submit").disabled = bad || nameInput.value.trim() === "" || building;
  }

  Object.values(inputs).forEach(i => i.addEventListener("input", left));
  nameInput.addEventListener("input", left);

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  /* Said, and focused, after something the player did: the list it is about is redrawn under them. */
  function announce(message) {
    const status = document.getElementById("status");
    status.textContent = message;
    status.focus();
  }

  function render() {
    const signedIn = !login || isSignedIn();
    document.getElementById("signin").hidden = signedIn;
    document.getElementById("content").hidden = !signedIn;
    if (!signedIn) renderSignIn();
  }

  /* Which session the page is in, and which load of the list is the latest. Both only count up.
   *
   * An answer can arrive after the player who asked has gone -- a 401 ended their session, and
   * somebody else has signed in on this page since. A list answer is drawn only if it is the latest
   * load's, and any form's answer only if its session is still this one, so one player's fighters
   * are never shown, or announced, to the next. */
  let session = 0;
  let loads = 0;

  /* The session is over: nothing it asked for may land, and its list is taken off the page now. */
  function forget() {
    session++;
    loads++;
    document.getElementById("fighters").replaceChildren();
    const note = document.getElementById("yours-note");
    note.textContent = "Loading your fighters…";
    note.hidden = false;
  }

  function signedIn() { forget(); show(""); render(); loadMine(); }

  /* A call with the player's token. A 401 is a session that is over -- if it is still this one: it
   * is dropped and the sign-in offered again, and the caller gets nothing. */
  async function send(url, init) {
    const asked = session;
    const token = await freshIdToken();
    const headers = Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {});
    const response = await fetch(url, Object.assign({}, init, { headers }));
    if (response.status === 401) {
      // Only a refusal of the session that is here ends it. One sent with an earlier player's token,
      // answering after somebody else has signed in on this page, is about nobody here: clearing
      // the session now would sign the new player out.
      if (asked !== session || (token && sessionStorage.getItem(TokenKey) !== token)) return null;
      if (token) clearSession();
      forget();
      render();
      show(login ? "Sign in to see your fighters." : "Say who you are with ?as=<cognito sub>.");
      return null;
    }
    return response;
  }

  /* The list is cleared as a load starts, so nothing from before it can be left showing, and only
   * the latest load may draw -- an earlier one answering late is dropped, success or failure. */
  async function loadMine() {
    const ticket = ++loads;
    const note = document.getElementById("yours-note");
    document.getElementById("fighters").replaceChildren();
    note.textContent = "Loading your fighters…";
    note.hidden = false;
    try {
      const response = await send(mineUrl, {});
      if (!response || ticket !== loads) return;
      const answer = await response.json().catch(() => ({}));
      if (ticket !== loads) return;
      if (!response.ok) { note.textContent = answer.error || response.statusText; return; }
      showMine(answer);
    } catch (err) {
      if (ticket === loads) note.textContent = "Could not reach the engine.";
    }
  }

  /* Built from elements rather than markup: a name is whatever a player typed. */
  function showMine(fighters) {
    const note = document.getElementById("yours-note");
    note.textContent = fighters.length ? "" : "You have no fighters yet. Build one below.";
    note.hidden = fighters.length > 0;
    const list = document.getElementById("fighters");
    list.replaceChildren(...fighters.map(fighterItem));
  }

  function fighterItem(f) {
    const item = document.createElement("li");
    const heading = document.createElement("h3");
    heading.textContent = f.name;
    item.appendChild(heading);
    if (f.description) {
      const about = document.createElement("p");
      about.className = "about";
      about.textContent = f.description;
      item.appendChild(about);
    }
    if (f.fighter) {
      const stats = document.createElement("dl");
      // Each name and its number in a group of their own, so the two cannot be laid out apart.
      traits.forEach(([key, label]) => {
        const pair = document.createElement("div");
        const term = document.createElement("dt");
        term.textContent = label;
        const figure = document.createElement("dd");
        figure.textContent = f.fighter[key];
        pair.append(term, figure);
        stats.appendChild(pair);
      });
      item.appendChild(stats);
    } else {
      const unbuilt = document.createElement("p");
      unbuilt.className = "detail";
      unbuilt.textContent = "Not a built fighter, so it cannot box.";
      item.appendChild(unbuilt);
    }

    // Not giveForm yet: giving a fighter away is to be offered later. The route and the form stay.
    item.appendChild(editForm(f));
    return item;
  }

  /* One labelled text field, as every form here lays them out. */
  function textField(id, caption, value, maxLength) {
    const field = document.createElement("div");
    field.className = "field wide";
    const label = document.createElement("label");
    label.htmlFor = id;
    label.textContent = caption;
    const input = document.createElement("input");
    input.id = id;
    input.type = "text";
    input.autocomplete = "off";
    input.maxLength = maxLength;
    input.value = value;
    field.append(label, input);
    return { field, input };
  }

  /* A form that sends one change about a fighter. `ready` says whether what is typed is worth
   * sending, `request` makes the call, and `done` says what happened. Each has its own in-flight
   * flag, for the reason `building` is the build form's: editing a field must not re-enable a
   * button whose request has not answered. */
  function changeForm(fields, buttonText, ready, request, done) {
    const form = document.createElement("form");
    form.noValidate = true;
    const button = document.createElement("button");
    button.type = "submit";
    button.className = "primary";
    button.textContent = buttonText;
    let sending = false;
    const refresh = () => { button.disabled = sending || !ready(); };
    fields.forEach(f => f.input.addEventListener("input", refresh));
    refresh();
    form.append(...fields.map(f => f.field), button);
    form.addEventListener("submit", async e => {
      e.preventDefault();
      if (sending || !ready()) return;
      sending = true;
      refresh();
      show("");
      const asked = session;
      try {
        const response = await request();
        if (!response || asked !== session) return;
        const answer = await response.json().catch(() => ({}));
        if (asked !== session) return;
        if (!response.ok) { show(answer.error || response.statusText); return; }
        await loadMine();
        if (asked === session) announce(done(answer));
      } catch (err) {
        if (asked === session) show("Could not reach the engine.");
      } finally {
        sending = false;
        refresh();
      }
    });
    return form;
  }

  function editForm(f) {
    const name = textField("name-" + f.characterId, "Name of " + f.name, f.name, 80);
    const about = textField("about-" + f.characterId, "Description of " + f.name, f.description || "", 280);
    return changeForm(
      [name, about],
      "Save changes",
      () => name.input.value.trim() !== "" &&
        (name.input.value.trim() !== f.name || about.input.value.trim() !== (f.description || "")),
      () => send(fighterUrl(f.characterId), {
        method: "PUT",
        body: JSON.stringify({ name: name.input.value.trim(), description: about.input.value.trim() })
      }),
      answer => "Saved " + answer.name + "."
    );
  }

  /* Giving a fighter away cannot be taken back from here, so the button asks first. Not offered on
   * the page yet (see fighterItem). */
  function giveForm(f) {
    const to = textField("give-" + f.characterId, "Give " + f.name + " to (matchmaker nickname)", "", 80);
    return changeForm(
      [to],
      "Give away",
      () => to.input.value.trim() !== "",
      () => window.confirm("Give " + f.name + " to " + to.input.value.trim() + "? It will be theirs, not yours.")
        ? send(ownerUrl(f.characterId), { method: "PUT", body: JSON.stringify({ toNickname: to.input.value.trim() }) })
        : Promise.resolve(null),
      answer => f.name + " now belongs to " + answer.toNickname + "."
    );
  }

  document.getElementById("build-form").addEventListener("submit", async e => {
    e.preventDefault();
    if (building) return;
    building = true;
    show("");
    const body = { name: nameInput.value.trim(), description: descriptionInput.value.trim() };
    traits.forEach(([key]) => { body[key] = value(inputs[key]); });
    document.getElementById("build-submit").disabled = true;
    const asked = session;
    try {
      const response = await send(buildUrl, { method: "POST", body: JSON.stringify(body) });
      if (!response || asked !== session) return;
      const answer = await response.json().catch(() => ({}));
      if (asked !== session) return;
      if (!response.ok) { show(answer.error || response.statusText); return; }
      reset();
      await loadMine();
      if (asked === session) announce(answer.name + " is built. Back in matchmaker, choose “I’ve made one — check again”" +
        " and offer or accept a bout with them.");
    } catch (err) {
      if (asked === session) show("Could not reach the engine.");
    } finally {
      building = false;
      left();
    }
  });

  reset();
  render();
  if (!login || isSignedIn()) loadMine();
</script>
</body>
</html>
"""

    /* The heading as first served, before any script runs; `describe()` in the page says the same thing. */
    private def heading(state: Option[Protocol.StateResponse]): String =
        state match {
            case None                                                   => "sign in to fight"
            case Some(s) if s.completed && s.method.contains("forfeit") => "time ran out"
            case Some(s) if s.completed =>
                val how = if (s.method.contains("knockout")) " by knockout" else " on points"
                if (s.draw) "a draw on points"
                else
                    s.winner.fold("over") { w =>
                        s.you.fold(s"$w wins$how")(you => (if (you == w) "you win" else "you lose") + how)
                    }
            case Some(s) =>
                val mine = s.you.flatMap(you => s.corners.find(_.side == you))
                if (mine.isDefined && s.yourPlan.isEmpty) s"plan round ${s.round}"
                else s"waiting for ${s.waitingFor.mkString(" and ")}"
        }
}
