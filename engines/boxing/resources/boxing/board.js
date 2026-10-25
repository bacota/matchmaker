  const pageData = JSON.parse(document.getElementById("page-data").textContent);
  const publicView = pageData.publicView;
  // Urls are derived from this page's own, not built from a base: behind API Gateway the path
  // carries a stage prefix, and a page that assumed "/matches/..." would 404 there.
  const here = location.pathname.replace(new RegExp("/(play|board)$"), "");
  // The page's own query goes along too: in the trusted local mode `?as=` is who the player is,
  // and a fetch without it would be nobody's. Deployed, there is no query and this adds nothing.
  const query = location.search;
  const stateUrl = (publicView ? here + "/board/state" : here + "/state") + query;
  const movesUrl = here + "/moves" + query;

  let state = pageData.state;
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

  // Only when it changes: the status line is a live region, and every poll rewriting the same
  // text can make a screen reader repeat it.
  function sayStatus(text) {
    const status = document.getElementById("status");
    if (status.textContent !== text) status.textContent = text;
  }

  function render() {
    const corner = mine();
    const live = !!(state && !state.isCompleted);
    const canPlan = !!(corner && corner.fighter && live && !state.yourPlan);

    sayStatus(describe());
    showClock(state && state.clock, corner ? corner.participantId : null);
    showFinale(result());
    document.getElementById("round").textContent = state
      ? (state.isCompleted ? "Scheduled for " + state.scheduledRounds + " rounds" + (state.hasExtraRound ? ", and an extra round" : "")
                         : state.hasExtraRound ? "Extra round: the bout was level after " + state.scheduledRounds + " rounds"
                         : "Round " + state.round + " of " + state.scheduledRounds)
      : "";

    const plan = document.getElementById("plan");
    // The plan is hidden once it is in, and the keyboard with it would fall to the top of the page;
    // it goes to the status line instead, as Finale's Close sends it.
    if (!canPlan && !plan.hidden && plan.contains(document.activeElement)) {
      const status = document.getElementById("status");
      if (!status.hasAttribute("tabindex")) status.setAttribute("tabindex", "-1");
      status.focus();
    }
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
      const doing = state.isCompleted ? "" : c.isPlanned ? "Round planned." : "Planning…";
      return '<div class="corner ' + escapeHtml(c.side) + '"><h3>' + escapeHtml(cornerName(c.side)) + "</h3>" +
        // The fighter by name, first under the corner: who is in the ring is what the page is about.
        (c.fighterName ? '<p class="fighter-name">' + escapeHtml(c.fighterName) + "</p>" : "") +
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
    // Spelled out rather than "14/12/16", which a screen reader reads as dates or fractions.
    // Each word kept with its number, so a narrow screen never leaves a number alone on a line.
    const spent = n => [["offense", n.offense], ["defense", n.defense], ["power", n.power]]
      .map(([k, v]) => '<span class="pair">' + k + " " + v + "</span>").join(", ");
    const rows = state.rounds.map(r => {
      const how = r.winner ? r.winner + " — " + r.decision : r.decision;
      const score = v => v === null || v === undefined ? "—" : v;
      // Everyone's totals; the viewer's own plan only, which the engine sends nobody else.
      const mine = state.you === "Red" ? r.red : state.you === "Blue" ? r.blue : null;
      return '<tr><th scope="row">' + r.number + "</th>" +
        '<td class="num">' + score(r.redPoints) + '</td><td class="num">' + score(r.bluePoints) + "</td>" +
        "<td>" + escapeHtml(how) +
        '<span class="plans">Red: ' + spent(r.redNumbers) + "</span>" +
        '<span class="plans">Blue: ' + spent(r.blueNumbers) + "</span>" +
        (mine ? '<span class="plans tactics">Your tactics: ' + spent(mine) + "</span>" : "") +
        "</td></tr>";
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
    if (!state || !state.isCompleted || !state.you || publicView) return null;
    if (state.method === "forfeit") {
      const late = state.corners.filter(c => state.clock && state.clock.timedOut.includes(c.participantId)).map(c => c.side);
      if (late.length) return late.includes(state.you) ? "lose" : "win";
    }
    if (state.isDrawn || !state.winner) return null;
    return state.winner === state.you ? "win" : "lose";
  }

  function describe() {
    if (!state) return login ? "sign in to fight" : "not your bout";
    if (state.isCompleted) {
      if (state.method === "forfeit") {
        const late = state.corners.filter(c => state.clock && state.clock.timedOut.includes(c.participantId)).map(c => c.side);
        if (!state.you) return late.join(" and ") + " ran out of time";
        return late.includes(state.you) ? "your time ran out — you lose" : "their time ran out — you win";
      }
      if (state.isDrawn) return "a draw on points";
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
    // `aria-disabled` while it is out rather than `disabled`, which would drop the keyboard's focus; a
    // second press meanwhile is refused here.
    if (button.getAttribute("aria-disabled") === "true") return;
    show("");
    button.setAttribute("aria-disabled", "true");
    const ticket = ask();
    const response = await send(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
    button.removeAttribute("aria-disabled");
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
        tell(ticket, errors.noCorner);
        return null;
      }
      return response;
    } catch (e) {
      if (!overtaken(ticket)) tell(ticket, errors.unreachable);
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
  keepCurrent(refresh, () => mayFetch() && (!state || !state.isCompleted));
