package com.vivi.stratego

import upickle.default.write
import com.vivi.engine.{LoginConfig, PlayLive, SignIn, TurnTimer}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page: the setup, the board, and the shell every engine's page has around them.
  *
  * The board is drawn from the state alone, and the state already leaves out every rank the viewer may not see — the
  * page hides nothing itself, since what the page receives anyone can read. It is drawn with the viewer's own army at
  * the bottom, which for Blue means turned round; the public board is drawn from Red's side.
  *
  * Ten squares across a phone is about 34 pixels a square, under the 44 a touch target should be. So the board is also
  * a set of labelled selects — the piece, and where it goes — that do everything a tap or a drag does at full size, and
  * that a screen reader or a keyboard can use as well as a finger. The board's squares are buttons too, each labelled
  * with its square and what is on it, and arrow keys move between them.
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
    val signIn: SignIn = SignIn("stratego")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("stratego")

    def board(
        matchId: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val heading = state match {
            case Some(s) if s.completed        => outcome(s)
            case Some(s) if s.phase == "setup" => "deploying"
            case Some(s)                       => s"${s.turn.getOrElse("")} to move"
            case None                          => "sign in to play"
        }

        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>capture the flag · ${escape(matchId)}</title>
<style>
  /* --error is 6.3:1 on the light page and 7.8:1 on the dark one; crimson, which it replaces, was 3.6:1
     in dark mode, under the 4.5:1 normal text needs. */
  /* The two armies carry white text: --red is 6.6:1 against it and --blue 6.7:1. */
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e;
          --square: #e9e4d4; --water: #8fbcd9; --red: #b3261e; --blue: #1d4ed8; --mark: #e6a700; --focus: #6d28d9; }
  @media (prefers-color-scheme: dark) {
    :root { --ink: #eee; --paper: #16181c; --error: #ff8a80; --square: #3a3a33; --water: #1f4d6b; --mark: #ffc940; --focus: #c4b5fd; }
  }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--paper); color: var(--ink);
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; }
  main { text-align: center; padding: 1.5rem 16px; width: 100%; max-width: 40rem; box-sizing: border-box; }
  h1 { font-size: 1rem; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; opacity: .6; margin: 0 0 .25rem; }
  #status { font-size: 1.5rem; font-weight: 700; margin: 0 0 1.25rem; min-height: 2rem; }
  #board { display: grid; grid-template-columns: repeat(10, 1fr); gap: 2px; width: 100%; max-width: 36rem;
           margin: 0 auto; padding: 2px; box-sizing: border-box; background: var(--line); border-radius: 6px; }
  #board button { aspect-ratio: 1; min-width: 0; padding: 0; border: 0; border-radius: 3px; position: relative;
                  background: var(--square); color: var(--ink); font: 700 clamp(.8rem, 3.6vw, 1.15rem)/1 ui-monospace, monospace; }
  #board button.Red { background: var(--red); color: #fff; }
  #board button.Blue { background: var(--blue); color: #fff; }
  #board button.lake { background: var(--water); }
  /* A piece's icon, and its number tucked into the corner — the number is what decides a fight, so it stays. */
  .icon { fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
  #board .icon { position: absolute; left: 24%; top: 26%; width: 56%; height: 56%; pointer-events: none; }
  #board .icon.alone { left: 18%; top: 18%; width: 64%; height: 64%; }
  #board .number { position: absolute; top: 2px; left: 3px; font-size: clamp(.6rem, 2.4vw, .8rem); }
  #board button[aria-disabled="false"] { cursor: pointer; }
  /* A piece that can be picked up. Only these take the touch for themselves, so a swipe that starts
     anywhere else on the board still scrolls the page. */
  #board { -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
  #board button.draggable { touch-action: none; cursor: grab; }
  /* The piece being dragged stays on its square, faded, while a copy follows the finger. */
  #board button.lifted { opacity: .35; }
  #board button.over { outline: 4px solid var(--mark); outline-offset: -4px; }
  #board button.ghost { position: fixed; z-index: 10; pointer-events: none; transform: scale(1.15);
                        box-shadow: 0 6px 16px rgba(0, 0, 0, .35); cursor: grabbing; }
  #board button.last { box-shadow: inset 0 0 0 2px var(--mark); }
  #board button.target { box-shadow: inset 0 0 0 4px var(--mark); }
  #board button.selected { outline: 4px solid var(--mark); outline-offset: -4px; }
  #board button:focus-visible { outline: 3px solid var(--focus); outline-offset: 1px; z-index: 1; }
  /* A piece that has moved, which tells the other side it is neither a bomb nor the flag. */
  #board button.moved::after { content: ""; position: absolute; top: 3px; right: 3px; width: 5px; height: 5px;
                               border-radius: 50%; background: currentColor; opacity: .8; }
  /* One of your own pieces whose rank the other side has seen. */
  #board button.known::before { content: ""; position: absolute; left: 25%; right: 25%; bottom: 2px; height: 2px;
                                background: currentColor; }
  #news { min-height: 1.5rem; margin: .75rem 0 0; }
  .controls { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: center; align-items: end; margin-top: 1rem; }
  /* Without this the display above would override `hidden`, and both sets of controls would always show. */
  .controls[hidden] { display: none; }
  .controls label { display: flex; flex-direction: column; align-items: start; font-size: .875rem; gap: .25rem; }
  .controls select, .controls button { font: inherit; font-size: 16px; min-height: 44px; }
  .controls select { min-width: 9rem; padding: 0 .5rem; }
  .controls button { padding: 0 1rem; border-radius: 6px; border: 1px solid var(--line); background: var(--paper);
                     color: var(--ink); cursor: pointer; }
  .controls button.primary { background: var(--ink); color: var(--paper); }
  .controls button:disabled { opacity: .5; cursor: default; }
  .controls :focus-visible { outline: 3px solid var(--focus); outline-offset: 2px; }
  #lost { margin-top: 1rem; font-size: .875rem; }
  #lost p { margin: .25rem 0; }
  details { margin-top: 1rem; font-size: .875rem; text-align: left; display: inline-block; }
  summary { min-height: 44px; display: flex; align-items: center; cursor: pointer; }
  details dl { display: grid; grid-template-columns: auto 1fr; gap: .125rem .75rem; margin: .25rem 0 0; }
  details dt { font: 700 1rem ui-monospace, monospace; display: flex; align-items: center; justify-content: end; gap: .25rem; }
  details dt .icon { width: 1.25rem; height: 1.25rem; }
  details dd { margin: 0; }
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
  #players h2 { font-size: 1rem; font-weight: 600; margin: 1.25rem 0 .25rem; }
  #seats { font-size: .875rem; opacity: .7; }
  #seats div { margin: .125rem 0; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
$icons
<main>
  <h1>capture the flag</h1>
  <!-- Announced: the other player's move, and the result, arrive while this page is idle. -->
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  ${TurnTimer.markup}
  <div id="board" role="group" aria-label="board"></div>
  <!-- Announced: what the last move did, which the other player's arrives while this page is idle. -->
  <p id="news" role="status" aria-live="polite"></p>
  <div id="setup-controls" class="controls" hidden>
    <label>Swap<select id="swap-a"></select></label>
    <label>with<select id="swap-b"></select></label>
    <button id="swap" type="button">Swap</button>
    <button id="shuffle" type="button">Shuffle</button>
    <button id="deploy" type="button" class="primary">Deploy army</button>
  </div>
  <div id="move-controls" class="controls" hidden>
    <label>Piece<select id="move-from"></select></label>
    <label>To<select id="move-to"></select></label>
    <button id="move" type="button" class="primary">Move</button>
  </div>
  <div id="lost"></div>
  <details>
    <summary>Key</summary>
    <dl>
      $key
      <dt>?</dt><dd>an enemy piece you have not seen; a dot means it has moved</dd>
    </dl>
  </details>
  <!-- The sign-in form, rendered by renderSignIn() and shown whenever there is a login to
       offer and no seat to show for it. -->
  <div id="signin" hidden></div>
  <section id="players" aria-labelledby="players-title" hidden>
    <h2 id="players-title">Players</h2>
    <div id="seats"></div>
  </section>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>
</main>
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${TurnTimer.script}

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

  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);

  function render() {
    const status = document.getElementById("status");
    if (!state) status.textContent = login ? "sign in to play" : "not your match";
    else if (state.completed && ranOut().length) {
      const late = ranOut();
      status.textContent = !state.you ? late.join(" and ") + " ran out of time"
        : late.includes(state.you) ? "your time ran out — you lose" : "their time ran out — you win";
    }
    else if (state.completed) status.textContent = endingText();
    else if (state.phase === "setup")
      status.textContent = deploying() ? "deploy your army: swap any two of your pieces, then deploy"
        : state.you ? "waiting for " + other(state.you) + " to deploy" : "the armies are deploying";
    else if (state.you) status.textContent = state.turn === state.you ? "your move (" + state.you + ")" : state.turn + " to move";
    else status.textContent = state.turn + " to move";

    const me = state && state.you ? state.players.find(p => p.side === state.you) : null;
    showClock(state && state.clock, me ? me.participantId : null);

    // Offered whenever there is a login to start and no seat to show for it — including after a
    // token expires mid-match, which is what turns a 401 back into a button.
    signin.hidden = !login || noSeat || (state && state.you);

    drawBoard();
    drawControls();
    drawNews();
    drawLost();

    // By nickname; a match created before matchmaker sent nicknames has only the sign-in id.
    document.getElementById("players").hidden = !state;
    document.getElementById("seats").innerHTML = state
      ? state.players.map(p => "<div>" + p.side + " · " + escapeHtml(p.nickname || p.cognitoId) + (p.side === (state.you || "") ? " (you)" : "") + "</div>").join("")
      : "";
  }

  // ---- the board --------------------------------------------------------------------------

  // The number a rank is shown with, the lower the stronger, as the key has it; the spy, the bomb
  // and the flag are drawn by their icon alone.
  const NUMBER = { Marshal: "1", General: "2", Colonel: "3", Major: "4", Captain: "5", Lieutenant: "6",
                   Sergeant: "7", Miner: "8", Scout: "9", Spy: "", Bomb: "", Flag: "" };
  const ARMY = [["Flag", 1], ["Spy", 1], ["Scout", 8], ["Miner", 5], ["Sergeant", 4], ["Lieutenant", 4],
                ["Captain", 4], ["Major", 3], ["Colonel", 2], ["General", 1], ["Marshal", 1], ["Bomb", 6]];
  const LAKES = [42, 43, 46, 47, 52, 53, 56, 57];

  // In setup, the first of two squares to swap; in play, the piece about to move.
  let selected = null;
  // The setup being arranged: 40 ranks, in the order of the player's home squares.
  let draft = null;
  // The square the board's keyboard focus is on, which is the one square in the tab order.
  let focusSquare = null;
  let lastNews = null;

  function other(side) { return side === "Red" ? "Blue" : "Red"; }
  function squareName(sq) { return "abcdefghij"[sq % 10] + (Math.floor(sq / 10) + 1); }
  function home(side) { const first = side === "Red" ? 0 : 60; return Array.from({ length: 40 }, (_, i) => first + i); }
  function deploying() { return !!(state && state.you && state.phase === "setup" && !state.deployed.includes(state.you)); }
  function myTurn() { return !!(state && state.you && state.phase === "play" && state.turn === state.you); }

  /* The square drawn at display position d, top left first: the viewer's own side at the bottom. */
  function squareAt(d) {
    const row = Math.floor(d / 10), col = d % 10;
    return state && state.you === "Blue" ? row * 10 + (9 - col) : (9 - row) * 10 + col;
  }

  function shuffled() {
    const army = [];
    ARMY.forEach(([rank, n]) => { for (let i = 0; i < n; i++) army.push(rank); });
    for (let i = army.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [army[i], army[j]] = [army[j], army[i]];
    }
    return army;
  }

  /* The setup is kept in this browser while it is being arranged, so a reload does not lose it. */
  const draftKey = "stratego-setup:" + here;
  function loadDraft() {
    try {
      const saved = JSON.parse(localStorage.getItem(draftKey));
      const counts = {};
      (saved || []).forEach(r => { counts[r] = (counts[r] || 0) + 1; });
      if (Array.isArray(saved) && saved.length === 40 && ARMY.every(([r, n]) => counts[r] === n)) return saved;
    } catch (e) {}
    return shuffled();
  }
  function saveDraft() { try { localStorage.setItem(draftKey, JSON.stringify(draft)); } catch (e) {} }

  /* Every piece the viewer may see, by square — the draft's, while it is being arranged. */
  function piecesBySquare() {
    const by = {};
    if (!state) return by;
    state.pieces.forEach(p => { by[p.square] = p; });
    if (deploying()) {
      if (!draft) draft = loadDraft();
      home(state.you).forEach((sq, i) => { by[sq] = { square: sq, side: state.you, rank: draft[i], revealed: false, moved: false }; });
    }
    return by;
  }

  function movesFrom(sq) { return state ? state.legalMoves.filter(m => m[0] === sq).map(m => m[1]) : []; }

  /* Whether tapping `sq` would do anything — which is also whether it is offered as a control. */
  function actionable(sq) {
    if (deploying()) return home(state.you).includes(sq);
    if (!myTurn()) return false;
    return movesFrom(sq).length > 0 || (selected !== null && movesFrom(selected).includes(sq));
  }

  const board = document.getElementById("board");
  const squares = [];
  for (let d = 0; d < 100; d++) {
    const b = document.createElement("button");
    b.type = "button";
    b.tabIndex = -1;
    b.addEventListener("click", () => {
      // The click that ends a drag is the drop, already acted on.
      if (justDragged) { justDragged = false; return; }
      tap(squareAt(d));
    });
    b.addEventListener("pointerdown", e => press(e, d));
    b.addEventListener("focus", () => { focusSquare = squareAt(d); });
    board.appendChild(b);
    squares.push(b);
  }
  board.addEventListener("keydown", e => {
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -10, ArrowDown: 10 }[e.key];
    const d = squares.indexOf(document.activeElement);
    if (step === undefined || d < 0) return;
    const next = d + step;
    if (next < 0 || next > 99 || (Math.abs(step) === 1 && Math.floor(next / 10) !== Math.floor(d / 10))) return;
    e.preventDefault();
    squares[d].tabIndex = -1;
    squares[next].tabIndex = 0;
    squares[next].focus();
  });

  function describe(sq, p) {
    const at = squareName(sq);
    if (LAKES.includes(sq)) return at + ", lake";
    if (!p) return at + ", empty";
    const mine = state && p.side === state.you;
    let text = at + ", " + (mine ? "your " : p.side + " ") + (p.rank || "unknown piece");
    if (p.moved && !p.rank) text += ", has moved";
    if (mine && p.revealed) text += ", seen by " + other(p.side);
    return text;
  }

  function drawBoard() {
    const by = piecesBySquare();
    const last = state && state.lastMove ? [state.lastMove.from, state.lastMove.to] : [];
    const targets = selected !== null && myTurn() ? movesFrom(selected) : [];
    let focusable = false;
    for (let d = 0; d < 100; d++) {
      const sq = squareAt(d), p = by[sq], b = squares[d];
      if (LAKES.includes(sq) || !p) b.textContent = "";
      else if (!p.rank) b.textContent = "?";
      else b.innerHTML = '<svg class="icon' + (NUMBER[p.rank] ? "" : " alone") + '" aria-hidden="true" focusable="false">' +
        '<use href="#rank-' + p.rank + '"/></svg>' + (NUMBER[p.rank] ? '<span class="number">' + NUMBER[p.rank] + "</span>" : "");
      b.className = LAKES.includes(sq) ? "lake" : p ? p.side : "";
      b.classList.toggle("moved", !!(p && p.moved && !p.rank));
      b.classList.toggle("known", !!(p && p.rank && p.revealed && state && p.side === state.you));
      b.classList.toggle("last", last.includes(sq));
      b.classList.toggle("target", targets.includes(sq));
      b.classList.toggle("selected", sq === selected);
      b.classList.toggle("draggable", draggable(sq));
      b.classList.toggle("lifted", !!(drag && drag.ghost && drag.from === sq));
      b.setAttribute("aria-disabled", actionable(sq) ? "false" : "true");
      let label = describe(sq, p);
      if (sq === selected) label += ", selected";
      if (targets.includes(sq)) label += p ? ", attack" : ", move here";
      b.setAttribute("aria-label", label);
      b.tabIndex = sq === focusSquare ? 0 : -1;
      if (sq === focusSquare) focusable = true;
    }
    // Something on the board must be reachable with the tab key.
    if (!focusable) squares[90].tabIndex = 0;
    board.setAttribute("aria-label", "board, " + (state && state.you === "Blue" ? "Blue" : "Red") + " at the bottom");
  }

  function tap(sq) {
    if (!actionable(sq)) return;
    if (deploying()) {
      if (selected === null) selected = sq;
      else if (selected === sq) selected = null;
      else { swap(selected, sq); selected = null; }
    } else if (selected !== null && movesFrom(selected).includes(sq)) {
      const from = selected;
      selected = null;
      submit({ from, to: sq });
    } else selected = sq === selected ? null : sq;
    render();
  }

  // ---- dragging ------------------------------------------------------------------------------
  //
  // Pointer events rather than HTML drag and drop, which a phone's touch does not drive. A press
  // becomes a drag only once it has moved, so a press that stays put is still a tap. Picking a
  // piece up selects it, which is what shows where it may go; letting it go does what tapping the
  // square under it would, and anywhere else puts it back.

  let drag = null, justDragged = false;

  function draggable(sq) {
    if (deploying()) return home(state.you).includes(sq);
    return myTurn() && movesFrom(sq).length > 0;
  }

  /* The square at a point on the screen, if it is one of the board's. */
  function squareUnder(x, y) {
    const d = squares.indexOf(document.elementFromPoint(x, y)?.closest("#board button"));
    return d < 0 ? null : squareAt(d);
  }

  /* Whether letting the dragged piece go on `sq` would do anything. */
  function droppable(sq) {
    if (sq === null || sq === drag.from) return false;
    return deploying() ? home(state.you).includes(sq) : movesFrom(drag.from).includes(sq);
  }

  function press(e, d) {
    const sq = squareAt(d);
    if (drag || !e.isPrimary || e.button !== 0 || !draggable(sq)) return;
    drag = { from: sq, button: squares[d], pointer: e.pointerId, touch: e.pointerType !== "mouse",
             x: e.clientX, y: e.clientY, ghost: null, over: null };
    squares[d].setPointerCapture(e.pointerId);
  }

  board.addEventListener("pointermove", e => {
    if (!drag || e.pointerId !== drag.pointer) return;
    if (!drag.ghost) {
      // Under this, the press may still be a tap.
      if (Math.hypot(e.clientX - drag.x, e.clientY - drag.y) < 8) return;
      selected = drag.from;
      render();
      const r = drag.button.getBoundingClientRect();
      const g = drag.button.cloneNode(true);
      g.classList.remove("selected", "target", "last", "draggable");
      g.classList.add("ghost");
      g.tabIndex = -1;
      g.inert = true;
      g.setAttribute("aria-hidden", "true");
      g.style.width = r.width + "px";
      g.style.height = r.height + "px";
      board.appendChild(g);
      drag.ghost = g;
      drag.button.classList.add("lifted");
    }
    drag.ghost.style.left = (e.clientX - drag.ghost.offsetWidth / 2) + "px";
    // Held above the point rather than under it, so that the square it would land on, and its
    // highlight, stay in sight — clear of a fingertip, which covers more than a cursor does.
    drag.ghost.style.top = (e.clientY - drag.ghost.offsetHeight * (drag.touch ? 1.5 : 1)) + "px";
    const sq = squareUnder(e.clientX, e.clientY);
    const over = droppable(sq) ? sq : null;
    if (over !== drag.over) {
      squares.forEach((b, i) => b.classList.toggle("over", squareAt(i) === over));
      drag.over = over;
    }
  });

  function release(e, drop) {
    if (!drag || e.pointerId !== drag.pointer) return;
    const d = drag;
    drag = null;
    if (!d.ghost) return; // never moved: a tap, which the click that follows handles
    d.ghost.remove();
    d.button.classList.remove("lifted");
    squares.forEach(b => b.classList.remove("over"));
    // The click that follows a drag, if the browser sends one, lands on the square it started from.
    justDragged = true;
    setTimeout(() => { justDragged = false; }, 0);
    selected = null;
    if (drop && d.over !== null) {
      if (deploying()) swap(d.from, d.over);
      else submit({ from: d.from, to: d.over });
    }
    render();
  }
  board.addEventListener("pointerup", e => release(e, true));
  board.addEventListener("pointercancel", e => release(e, false));

  function swap(a, b) {
    const own = home(state.you), i = own.indexOf(a), j = own.indexOf(b);
    if (i < 0 || j < 0 || i === j) return;
    [draft[i], draft[j]] = [draft[j], draft[i]];
    saveDraft();
  }

  // ---- the controls --------------------------------------------------------------------------

  /* Replaces a select's options, keeping what was chosen if it is still there. */
  function fill(select, options) {
    const kept = select.value;
    select.innerHTML = "";
    options.forEach(([value, text]) => {
      const o = document.createElement("option");
      o.value = String(value);
      o.textContent = text;
      select.appendChild(o);
    });
    if (options.some(([value]) => String(value) === kept)) select.value = kept;
  }

  const swapA = document.getElementById("swap-a"), swapB = document.getElementById("swap-b");
  const moveFrom = document.getElementById("move-from"), moveTo = document.getElementById("move-to");

  function drawControls() {
    const setup = deploying(), play = myTurn();
    document.getElementById("setup-controls").hidden = !setup;
    document.getElementById("move-controls").hidden = !play;
    const by = piecesBySquare();
    if (setup) {
      const options = home(state.you).map(sq => [sq, squareName(sq) + " " + by[sq].rank]);
      fill(swapA, options);
      fill(swapB, options);
      // Two different squares to start with, so that Swap does something before either is changed.
      if (swapA.value === swapB.value) swapB.selectedIndex = (swapA.selectedIndex + 1) % options.length;
    }
    if (play) {
      const origins = [...new Set(state.legalMoves.map(m => m[0]))];
      fill(moveFrom, origins.map(sq => [sq, squareName(sq) + " " + by[sq].rank]));
      if (selected !== null && origins.includes(selected)) moveFrom.value = String(selected);
      drawTargets();
    }
  }

  function drawTargets() {
    const by = piecesBySquare();
    fill(moveTo, movesFrom(Number(moveFrom.value)).map(sq => [sq, squareName(sq) + (by[sq] ? " — attack" : "")]));
    document.getElementById("move").disabled = moveTo.options.length === 0;
  }

  moveFrom.addEventListener("change", () => { selected = Number(moveFrom.value); drawBoard(); drawTargets(); });
  document.getElementById("move").addEventListener("click", () => {
    if (!moveTo.value) return;
    selected = null;
    submit({ from: Number(moveFrom.value), to: Number(moveTo.value) });
  });
  document.getElementById("swap").addEventListener("click", () => {
    swap(Number(swapA.value), Number(swapB.value));
    selected = null;
    render();
  });
  document.getElementById("shuffle").addEventListener("click", () => {
    draft = shuffled();
    saveDraft();
    selected = null;
    render();
  });
  document.getElementById("deploy").addEventListener("click", () => {
    selected = null;
    submit({ setup: draft });
  });

  // ---- what happened --------------------------------------------------------------------------

  function drawNews() {
    const news = document.getElementById("news");
    const lm = state && state.lastMove;
    const key = lm ? state.moveCount + ":" + JSON.stringify(lm) : null;
    // Written only when it changes, so the live region announces each move once.
    if (key === lastNews) return;
    lastNews = key;
    news.textContent = lm ? moveText(lm) : "";
  }

  function moveText(lm) {
    const at = squareName(lm.to), them = other(lm.side);
    if (!lm.battle) return lm.side + " moved " + squareName(lm.from) + " to " + at + ".";
    const a = lm.side + "'s " + lm.battle.attacker, d = them + "'s " + lm.battle.defender;
    if (lm.battle.result === "AttackerWins") return a + " took " + d + " on " + at + ".";
    if (lm.battle.result === "DefenderWins") return a + " attacked " + d + " on " + at + " and lost.";
    return a + " and " + d + " both fell on " + at + ".";
  }

  function endingText() {
    if (state.ending === "flag") return state.winner + " captured the flag";
    if (state.ending === "no-moves")
      return state.draw ? "neither side can move — drawn" : other(state.winner) + " cannot move — " + state.winner + " wins";
    if (state.ending === "cap") return "move limit reached — drawn";
    return state.draw ? "drawn" : state.winner + " wins";
  }

  function drawLost() {
    const lost = document.getElementById("lost");
    if (!state || state.phase === "setup") { lost.innerHTML = ""; return; }
    lost.innerHTML = state.lost.map(l => {
      const counts = {};
      l.ranks.forEach(r => { counts[r] = (counts[r] || 0) + 1; });
      const text = Object.keys(counts).map(r => r + (counts[r] > 1 ? " ×" + counts[r] : "")).join(", ") || "nothing";
      return "<p>" + l.side + " has lost: " + escapeHtml(text) + "</p>";
    }).join("") + "<p>" + state.moveCount + " of " + state.maxMoves + " moves</p>";
  }

  /* The sides whose clock ran out in a live match, which is how it ended if there are any. */
  function ranOut() {
    if (!state || !state.clock) return [];
    return state.players.filter(p => state.clock.timedOut.includes(p.participantId)).map(p => p.side);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  /* A setup or a move: `body` is either {setup: [...]} or {from, to}. */
  async function submit(body) {
    show("");
    const ticket = ask();
    const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
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
    const response = await send(stateUrl, {}, ticket);
    if (!response || !response.ok) return;
    const answer = await response.json();
    if (latest(ticket)) { state = answer; render(); }
  }

  /* Every call carries the ID token when there is one. A 401 means the session is over rather
   * than the move being wrong, so the token is dropped and the page falls back to offering a
   * sign-in — a stale token must not leave the board looking merely broken. */
  /* `ticket` is the caller's, from `ask()`: a refusal answered here is ordered like any other
   * answer, so a late 401 or 403 cannot blank a board a newer request has already shown, nor a
   * stale failure overwrite a newer message. If the session really is over, the next request is
   * refused too, with a newer ticket, and that one is acted on. */
  async function send(url, init, ticket) {
    const token = await freshIdToken();
    const headers = Object.assign({}, init.headers || {}, token ? { authorization: "Bearer " + token } : {});
    try {
      const response = await fetch(url, Object.assign({}, init, { headers }));
      // A 401 is "who are you?": the session is over or never began, so it is dropped and a sign-in
      // offered. A 403 is "not yours": signed in, just not to a seat here. The session is kept — it
      // is good for every match the player is in — and the page stops asking, since another sign-in
      // would be the same player refused the same way.
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

    /** One icon per rank, drawn for this page: plain symbols, not any published edition's artwork, which is
      * copyrighted. Each is a 24-unit square in `currentColor`, so it takes the white of a coloured piece, and is
      * placed on a square with `<use href="#rank-Marshal">`. They are decoration — a square's label names its rank in
      * words.
      */
    /** The key's lines, strongest first. Numbered the classic European way — 1 is the Marshal, and the lower number
      * wins — which is only how ranks are shown: `Rank.strength` is what decides a fight. The page's `NUMBER` must
      * agree.
      */
    private val key: String =
        List(
          ("Marshal", "1", "Marshal"),
          ("General", "2", "General"),
          ("Colonel", "3", "Colonel"),
          ("Major", "4", "Major"),
          ("Captain", "5", "Captain"),
          ("Lieutenant", "6", "Lieutenant"),
          ("Sergeant", "7", "Sergeant"),
          ("Miner", "8", "Miner — the only piece that survives attacking a bomb"),
          ("Scout", "9", "Scout — runs any distance in a straight line"),
          ("Spy", "", "Spy — takes the Marshal, if it attacks first"),
          ("Bomb", "", "Bomb — never moves"),
          ("Flag", "", "Flag — take it to win")
        ).map((rank, number, meaning) => keyEntry(rank, number, meaning)).mkString("\n      ")

    /** A line of the key: the icon and number a rank is drawn with, and what it is. */
    private def keyEntry(rank: String, number: String, meaning: String): String =
        s"""<dt>${iconOf(rank)}$number</dt><dd>$meaning</dd>"""

    private def iconOf(rank: String): String =
        s"""<svg class="icon" aria-hidden="true" focusable="false"><use href="#rank-$rank"/></svg>"""

    private val icons: String =
        """<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false"><defs>
  <symbol id="rank-Marshal" viewBox="0 0 24 24"><path fill="currentColor" d="M3 17 2 6.5l5.5 4L12 3.5l4.5 7 5.5-4L21 17z"/><path d="M3 20.5h18"/></symbol>
  <symbol id="rank-General" viewBox="0 0 24 24"><path fill="currentColor" d="m12 2.5 2.9 6 6.6.9-4.8 4.6 1.2 6.5-5.9-3.1-5.9 3.1 1.2-6.5-4.8-4.6 6.6-.9z"/></symbol>
  <symbol id="rank-Colonel" viewBox="0 0 24 24"><path fill="currentColor" d="m12 2.5 8 3v6.5c0 5-3.5 8.2-8 9.5-4.5-1.3-8-4.5-8-9.5V5.5z"/></symbol>
  <symbol id="rank-Major" viewBox="0 0 24 24"><path d="M4 4l13 13M20 4 7 17M14 19.5l5.5-5.5M10 19.5 4.5 14M17 17l3 3M7 17l-3 3"/></symbol>
  <symbol id="rank-Captain" viewBox="0 0 24 24"><rect x="6" y="4" width="4" height="16" rx="1" fill="currentColor" stroke="none"/><rect x="14" y="4" width="4" height="16" rx="1" fill="currentColor" stroke="none"/></symbol>
  <symbol id="rank-Lieutenant" viewBox="0 0 24 24"><rect x="10" y="4" width="4" height="16" rx="1" fill="currentColor" stroke="none"/></symbol>
  <symbol id="rank-Sergeant" viewBox="0 0 24 24"><path d="m5 8.5 7-4.5 7 4.5M5 13.5 12 9l7 4.5M5 18.5l7-4.5 7 4.5"/></symbol>
  <symbol id="rank-Miner" viewBox="0 0 24 24"><g transform="rotate(-35 12 12)"><path d="M3 9.5c5-5 13-5 18 0"/><path d="M12 6v15.5"/></g></symbol>
  <symbol id="rank-Scout" viewBox="0 0 24 24"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.75" fill="currentColor" stroke="none"/></symbol>
  <symbol id="rank-Spy" viewBox="0 0 24 24"><path fill="currentColor" fill-rule="evenodd" stroke="none" d="M2 9c0-1.5 1-2 2.5-2C8 7 10 9 12 9s4-2 7.5-2C21 7 22 7.5 22 9c0 4-2 7.5-5 7.5-2.5 0-3.5-2.5-5-2.5s-2.5 2.5-5 2.5c-3 0-5-3.5-5-7.5zM4.5 10.5a2.75 1.75 0 1 0 5.5 0 2.75 1.75 0 1 0-5.5 0zM14 10.5a2.75 1.75 0 1 0 5.5 0 2.75 1.75 0 1 0-5.5 0z"/></symbol>
  <symbol id="rank-Bomb" viewBox="0 0 24 24"><circle cx="10" cy="14" r="7" fill="currentColor" stroke="none"/><path d="m14.5 9.5 3-3M20 2v2M23 5h-2M22 3l-1.5 1.5"/></symbol>
  <symbol id="rank-Flag" viewBox="0 0 24 24"><path d="M6 21.5V3"/><path fill="currentColor" d="M6 4h12l-3 4 3 4H6z"/></symbol>
</defs></svg>"""

    private def outcome(state: Protocol.StateResponse): String =
        if (state.clock.exists(_.timedOut.nonEmpty)) "time ran out"
        else if (state.draw) "drawn"
        else if (state.ending.contains("flag")) state.winner.map(w => s"$w captured the flag").getOrElse("over")
        else state.winner.map(w => s"$w wins").getOrElse("over")
}
