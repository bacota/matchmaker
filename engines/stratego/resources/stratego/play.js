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

  // Present when the server already knew whose seat this is; null when the player has yet to
  // sign in, in which case the first fetch below fills it.
  let state = pageData.state;
  // Set by a 403: signed in, but not to a seat in this match. See `send`.
  let noSeat = false;

  const signin = document.getElementById("signin");
  renderSignIn();
  const showClock = turnClock(refresh);
  const showFinale = finale();

  // Only when it changes: the status line is a live region, and every poll rewriting the same
  // text can make a screen reader repeat it.
  function sayStatus(text) {
    const status = document.getElementById("status");
    if (status.textContent !== text) status.textContent = text;
  }

  function statusText() {
    if (!state) return login ? "sign in to play" : "not your match";
    if (state.isCompleted && ranOut().length) {
      const late = ranOut();
      return !state.you ? late.join(" and ") + " ran out of time"
        : late.includes(state.you) ? "your time ran out — you lose" : "their time ran out — you win";
    }
    if (state.isCompleted) return endingText();
    if (state.phase === "setup")
      return deploying() ? "deploy your army: swap any two of your pieces, then deploy"
        : state.you ? "waiting for " + other(state.you) + " to deploy" : "the armies are deploying";
    if (state.you) return state.turn === state.you ? "your move (" + state.you + ")" : state.turn + " to move";
    return state.turn + " to move";
  }

  function render() {
    settlePending();
    sayStatus(statusText());

    const me = state && state.you ? state.players.find(p => p.side === state.you) : null;
    showClock(state && state.clock, me ? me.participantId : null);
    showFinale(result());

    // Offered whenever there is a login to start and no seat to show for it — including after a
    // token expires mid-match, which is what turns a 401 back into a button.
    signin.hidden = !login || noSeat || (state && state.you);

    // A state with nothing to replay — signed out, or a new match — has no past to be looking at.
    if (!state || !state.replay) view = null;
    drawBoard();
    drawReplay();
    drawControls();
    drawNews();
    drawLost();
    drawRemaining();

    // By nickname, never the sign-in id; a match created before matchmaker sent nicknames shows the side alone.
    document.getElementById("players").hidden = !state;
    document.getElementById("seats").innerHTML = state
      ? state.players.map(p => "<div>" + p.side + (p.nickname ? " · " + escapeHtml(p.nickname) : "") + (p.side === (state.you || "") ? " (you)" : "") + "</div>").join("")
      : "";
  }

  // ---- the board --------------------------------------------------------------------------

  // The number a rank is shown with, the lower the stronger, as the key has it; the assassin, the
  // mine and the flag are drawn by their icon alone.
  const NUMBER = { Marshal: "1", General: "2", Colonel: "3", Major: "4", Captain: "5", Lieutenant: "6",
                   Sergeant: "7", Miner: "8", Scout: "9", Spy: "", Bomb: "", Flag: "" };
  // What a rank is called on this page, where it differs from the engine's own name for it: the two
  // generals are named for the insignia they wear, and the miner, spy and bomb are the engineer,
  // assassin and mine. The engine's names are what the API and stored matches use, so they stay; only
  // what a player reads changes.
  const NAME = { Marshal: "General", General: "Brigadier", Miner: "Engineer", Spy: "Assassin", Bomb: "Mine" };
  function rankName(rank) { return NAME[rank] || rank; }
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
  // The move the board is showing the position after, while stepping back through the match; null
  // for the position being played. See `replayFrame`.
  let view = null;

  function other(side) { return side === "Red" ? "Blue" : "Red"; }
  function squareName(sq) { return "abcdefghij"[sq % 10] + (Math.floor(sq / 10) + 1); }
  function home(side) { const first = side === "Red" ? 0 : 60; return Array.from({ length: 40 }, (_, i) => first + i); }
  function deploying() { return !!(state && state.you && state.phase === "setup" && !state.deployed.includes(state.you)); }
  function myTurn() { return !!(state && state.you && state.phase === "play" && state.turn === state.you); }
  /* Whether the board is showing a past position rather than the one being played. */
  function replaying() { return view !== null && !!(state && state.replay); }
  /* What each side had lost by the position on the board. */
  function lostShown() { return replaying() ? replayFrame(state.replay, view).lost : state.lost; }

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
    if (replaying()) return replayFrame(state.replay, view).by;
    const by = {};
    if (!state) return by;
    state.pieces.forEach(p => { by[p.square] = p; });
    if (deploying()) {
      if (!draft) draft = loadDraft();
      home(state.you).forEach((sq, i) => { by[sq] = { square: sq, side: state.you, rank: draft[i], isRevealed: false, isMoved: false }; });
    }
    // A move that has been made but not yet answered is shown made; the answer replaces it either
    // way, and a refused one goes back. Only over the state it was made from: one that has moved on
    // already holds this move, and perhaps the reply, whose piece may now stand on `from`.
    if (pending && state.moveCount === pending.count && by[pending.from]) {
      by[pending.to] = Object.assign({}, by[pending.from], { square: pending.to, isMoved: true });
      delete by[pending.from];
    }
    return by;
  }

  function movesFrom(sq) { return state ? state.legalMoves.filter(m => m[0] === sq).map(m => m[1]) : []; }

  /* Whether tapping `sq` would do anything — which is also whether it is offered as a control. */
  function actionable(sq) {
    // Nothing while a move is out, as `tap` and dragging have it: the turn's move is already made.
    if (pending || replaying()) return false;
    if (deploying()) return home(state.you).includes(sq);
    if (!myTurn()) return false;
    return movesFrom(sq).length > 0 || (selected !== null && movesFrom(selected).includes(sq));
  }

  const board = document.getElementById("board");
  const squares = [];
  // Whether a square has the focus because it was clicked or touched, rather than reached with the
  // keyboard. The arrow keys move between squares for a keyboard user and step the replay for
  // everyone else; see the replay's keys below.
  let pointerDown = false, squareFocusByPointer = false;
  // A key pressed means the next focus is the keyboard's, even after a press that focused nothing.
  document.addEventListener("keydown", () => { pointerDown = false; }, true);
  for (let d = 0; d < 100; d++) {
    const b = document.createElement("button");
    b.type = "button";
    b.tabIndex = -1;
    b.addEventListener("click", () => {
      // The click that ends a drag is the drop, already acted on.
      if (justDragged) { justDragged = false; return; }
      tap(squareAt(d));
    });
    b.addEventListener("pointerdown", e => { pointerDown = true; press(e, d); });
    b.addEventListener("focus", () => {
      focusSquare = squareAt(d);
      squareFocusByPointer = pointerDown;
      pointerDown = false;
      if (b.matches(":focus-visible")) tipLater(d);
    });
    b.addEventListener("blur", hideTip);
    b.addEventListener("pointerenter", e => { if (e.pointerType === "mouse") tipLater(d); });
    b.addEventListener("pointerleave", () => leaveSoon());
    board.appendChild(b);
    squares.push(b);
  }
  board.addEventListener("keydown", e => {
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -10, ArrowDown: 10 }[e.key];
    const d = squares.indexOf(document.activeElement);
    if (step === undefined || d < 0 || squareFocusByPointer) return;
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
    let text = at + ", " + (mine ? "your " : p.side + " ") + (p.rank ? rankName(p.rank) : "unknown piece");
    if (p.isMoved && !p.isRevealed) text += ", has moved";
    if (mine && p.isRevealed) text += ", seen by " + other(p.side);
    else if (replaying() && p.rank && !mine) text += p.isRevealed ? ", revealed" : ", not yet revealed";
    return text;
  }

  // ---- naming a piece on hover ----------------------------------------------------------------
  //
  // A pointer resting on a piece for a second, or the keyboard's focus, names it beside the square.
  // Hover only: a phone has none, and a long press there is the start of a drag. It stays while the
  // pointer moves onto it and goes with Escape, as content shown on hover must.

  const tip = document.getElementById("tip");
  let tipSquare = null, tipTimer = null, leaveTimer = null;

  /* What the tip says for the piece on display position `d`, or null for no tip. */
  function tipText(d) {
    const sq = squareAt(d), p = piecesBySquare()[sq];
    if (!p || LAKES.includes(sq)) return null;
    if (!p.rank) return "Unknown " + p.side + " piece" + (p.isMoved ? " — has moved" : "");
    return rankName(p.rank) + (NUMBER[p.rank] ? " (" + NUMBER[p.rank] + ")" : "");
  }

  function tipLater(d) {
    clearTimeout(tipTimer);
    clearTimeout(leaveTimer);
    if (tipSquare !== null && tipSquare !== d) hideTip();
    tipTimer = setTimeout(() => showTip(d), 1000);
  }

  function showTip(d) {
    const text = drag ? null : tipText(d);
    if (text === null) { hideTip(); return; }
    tipSquare = d;
    tip.textContent = text;
    tip.hidden = false;
    // Above the square, or below it when there is no room above; kept inside the window.
    const r = squares[d].getBoundingClientRect(), w = tip.offsetWidth, h = tip.offsetHeight;
    const left = Math.min(Math.max(4, r.left + r.width / 2 - w / 2), window.innerWidth - w - 4);
    const top = r.top - h - 6 >= 4 ? r.top - h - 6 : r.bottom + 6;
    tip.style.left = left + "px";
    tip.style.top = top + "px";
  }

  function hideTip() {
    clearTimeout(tipTimer);
    clearTimeout(leaveTimer);
    tipSquare = null;
    tip.hidden = true;
  }

  /* Leaving the square hides the tip, unless the pointer is on its way onto the tip itself. */
  function leaveSoon() {
    clearTimeout(tipTimer);
    if (tipSquare === null) return;
    clearTimeout(leaveTimer);
    leaveTimer = setTimeout(hideTip, 200);
  }
  tip.addEventListener("pointerenter", () => clearTimeout(leaveTimer));
  tip.addEventListener("pointerleave", hideTip);
  document.addEventListener("keydown", e => { if (e.key === "Escape") hideTip(); });
  // The square scrolls away from under a tip placed beside it.
  window.addEventListener("scroll", () => { if (tipSquare !== null) hideTip(); }, { passive: true });

  function drawBoard() {
    const by = piecesBySquare();
    const shownMove = replaying() ? (view > 0 ? state.replay.moves[view - 1] : null) : state && state.lastMove;
    const last = shownMove ? [shownMove.from, shownMove.to] : [];
    const targets = selected !== null && myTurn() ? movesFrom(selected) : [];
    let focusable = false;
    for (let d = 0; d < 100; d++) {
      const sq = squareAt(d), p = by[sq], b = squares[d];
      if (LAKES.includes(sq) || !p) b.textContent = "";
      else if (!p.rank) b.textContent = "?";
      else b.innerHTML = '<svg class="icon' + (p.rank === "Marshal" ? " wide" : NUMBER[p.rank] ? "" : " alone") + '" aria-hidden="true" focusable="false">' +
        '<use href="#rank-' + p.rank + '"/></svg>' + (NUMBER[p.rank] ? '<span class="number">' + NUMBER[p.rank] + "</span>" : "");
      b.className = LAKES.includes(sq) ? "lake" : p ? p.side : "";
      // Like `known`, either side's, so that a replay shows what each side knew of the other at every step.
      b.classList.toggle("moved", !!(p && p.isMoved && !p.isRevealed));
      // Either side's: a replay shows ranks that were not seen until later, and the mark is what tells them apart, so
      // it means the same at every step — the latest included.
      b.classList.toggle("known", !!(p && p.rank && p.isRevealed));
      b.classList.toggle("last", last.includes(sq));
      b.classList.toggle("target", targets.includes(sq));
      b.classList.toggle("selected", sq === selected);
      b.classList.toggle("draggable", draggable(sq));
      // A redraw mid-drag — a refresh arriving — must not drop what the drag is showing.
      b.classList.toggle("lifted", !!(drag && drag.ghost && drag.from === sq));
      b.classList.toggle("over", !!(drag && drag.ghost && drag.over === sq));
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
    // A refresh can change what is on the square a tip names.
    if (tipSquare !== null) showTip(tipSquare);
    board.classList.toggle("replaying", replaying());
    board.setAttribute("aria-label", "board, " + (state && state.you === "Blue" ? "Blue" : "Red") + " at the bottom" +
      (replaying() ? ", replaying " + replayPlace() : ""));
  }

  function tap(sq) {
    if (pending || !actionable(sq)) return;
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
  // piece up selects it, which is what shows where it may go.
  //
  // What is seen is what happens: the piece carried is centred on the pointer and lands on the
  // square under it. Over a square it may land on it snaps into that square, ringed; anywhere else
  // it floats, and letting it go there puts it back. Under a finger it is drawn larger, so that it
  // shows round the fingertip. A move then shows where it was dropped while the
  // engine is asked (`pending`), rather than jumping home until the answer comes.

  let drag = null, justDragged = false, pending = null;

  function draggable(sq) {
    if (pending || replaying()) return false;
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

  function buttonOf(sq) { return squares[squares.findIndex((_, i) => squareAt(i) === sq)]; }

  /* The square the piece would land on if let go with the pointer at (x, y), or null for nowhere —
   * worked out afresh from where the pointer is, never from where it last moved. */
  function landing(x, y) {
    const sq = squareUnder(x, y);
    return droppable(sq) ? sq : null;
  }

  /* Places the carried piece for a pointer at (x, y): snapped into the square it would land on, or
   * floating where it is carried. Called on every move, and on a scroll, which moves the board
   * under a pointer that has not moved. */
  function place(x, y) {
    drag.x = x;
    drag.y = y;
    const over = landing(x, y), g = drag.ghost;
    if (over !== null) {
      const r = buttonOf(over).getBoundingClientRect();
      g.style.left = r.left + "px";
      g.style.top = r.top + "px";
    } else {
      g.style.left = (x - g.offsetWidth / 2) + "px";
      g.style.top = (y - g.offsetHeight / 2) + "px";
    }
    g.classList.toggle("snapped", over !== null);
    if (over !== drag.over) {
      drag.over = over;
      squares.forEach((b, i) => b.classList.toggle("over", squareAt(i) === over));
    }
  }

  function press(e, d) {
    hideTip();
    const sq = squareAt(d);
    if (drag || !e.isPrimary || e.button !== 0 || !draggable(sq)) return;
    drag = { from: sq, button: squares[d], pointer: e.pointerId, touch: e.pointerType !== "mouse",
             startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY, ghost: null, over: null };
    squares[d].setPointerCapture(e.pointerId);
  }

  board.addEventListener("pointermove", e => {
    if (!drag || e.pointerId !== drag.pointer) return;
    if (!drag.ghost) {
      // Under this, the press may still be a tap.
      if (Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY) < 8) return;
      selected = drag.from;
      const r = drag.button.getBoundingClientRect();
      const g = drag.button.cloneNode(true);
      g.classList.remove("selected", "target", "last", "draggable", "over");
      g.classList.add("ghost");
      g.classList.toggle("touch", drag.touch);
      g.tabIndex = -1;
      g.inert = true;
      g.setAttribute("aria-hidden", "true");
      g.style.width = r.width + "px";
      g.style.height = r.height + "px";
      board.appendChild(g);
      drag.ghost = g;
      render();
    }
    place(e.clientX, e.clientY);
  });

  // The board can scroll under a pointer that holds still — a wheel, a phone's page settling.
  window.addEventListener("scroll", () => { if (drag && drag.ghost) place(drag.x, drag.y); }, { passive: true });

  function release(e, drop) {
    if (!drag || e.pointerId !== drag.pointer) return;
    const d = drag;
    // Where it is let go, not where it last moved: the board may have scrolled since.
    const to = drop && d.ghost ? landing(e.clientX, e.clientY) : null;
    drag = null;
    if (!d.ghost) return; // never moved: a tap, which the click that follows handles
    d.ghost.remove();
    // The click that follows a drag, if the browser sends one, lands on the square it started from.
    justDragged = true;
    setTimeout(() => { justDragged = false; }, 0);
    selected = null;
    if (to !== null) {
      if (deploying()) swap(d.from, to);
      else submit({ from: d.from, to });
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
    // A seated player may give up at any point until the match is over, setup included.
    document.getElementById("concede-bar").hidden = !(state && state.you && !state.isCompleted);
    // Not while a move is out: it is shown made, so its piece is no longer where the controls
    // would offer it from, and it is the only move this turn has.
    const setup = deploying(), play = myTurn() && !pending && !replaying();
    document.getElementById("setup-controls").hidden = !setup;
    document.getElementById("move-controls").hidden = !play;
    const by = piecesBySquare();
    if (setup) {
      const options = home(state.you).map(sq => [sq, squareName(sq) + " " + rankName(by[sq].rank)]);
      fill(swapA, options);
      fill(swapB, options);
      // Two different squares to start with, so that Swap does something before either is changed.
      if (swapA.value === swapB.value) swapB.selectedIndex = (swapA.selectedIndex + 1) % options.length;
    }
    if (play) {
      const origins = [...new Set(state.legalMoves.map(m => m[0]))];
      fill(moveFrom, origins.map(sq => [sq, squareName(sq) + " " + rankName(by[sq].rank)]));
      if (selected !== null && origins.includes(selected)) moveFrom.value = String(selected);
      drawTargets();
    }
  }

  function drawTargets() {
    const by = piecesBySquare();
    fill(moveTo, movesFrom(Number(moveFrom.value)).map(sq => [sq, squareName(sq) + (by[sq] ? " — attack" : "")]));
    // And not while a move is out: one move a turn, and the page shows this one made already.
    document.getElementById("move").disabled = moveTo.options.length === 0 || !!pending;
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
  /* Conceding asks first. In a dialog where the browser has one, or its plain confirm otherwise —
   * old Safari — so that the question is always asked. */
  const concedeDialog = document.getElementById("concede-dialog");
  function concede() { submit({ isConcession: true }); }
  document.getElementById("concede").addEventListener("click", () => {
    if (typeof concedeDialog.showModal === "function") concedeDialog.showModal();
    else if (window.confirm("Concede this match? Your opponent wins. This cannot be undone.")) concede();
  });
  document.getElementById("concede-cancel").addEventListener("click", () => concedeDialog.close());
  document.getElementById("concede-confirm").addEventListener("click", () => { concedeDialog.close(); concede(); });

  document.getElementById("deploy").addEventListener("click", () => {
    selected = null;
    submit({ setup: draft });
  });

  // ---- replaying ------------------------------------------------------------------------------
  //
  // Back and forward a move at a time, from the opening position to the one being played, with the
  // buttons under the board or the left and right arrow keys, and straight to either end with the
  // double arrows or Home and End. Looking back changes nothing: the
  // board cannot be played on until it is back at the latest position, and a move the other player
  // makes meanwhile is added to the end of what can be stepped through.

  const replayBack = document.getElementById("replay-back"), replayForward = document.getElementById("replay-forward");
  const replayFirst = document.getElementById("replay-first"), replayLatest = document.getElementById("replay-latest");

  function replayLength() { return state && state.replay ? state.replay.moves.length : 0; }

  /* Where the board is in the match, as the replay's controls say it. */
  function replayPlace() {
    if (!replaying()) return "latest position, move " + replayLength();
    return view === 0 ? "opening position" : "move " + view + " of " + replayLength();
  }

  function drawReplay() {
    const n = replayLength();
    document.getElementById("replay").hidden = n === 0;
    document.getElementById("replay-at").textContent = replaying()
      ? (view === 0 ? "Opening position" : "Move " + view + " of " + n) : "Move " + n + " of " + n;
    // aria-disabled rather than disabled, so a button keeps the focus when it has stepped as far as
    // it goes, and the arrow keys still work from it.
    replayFirst.setAttribute("aria-disabled", String(n === 0 || view === 0));
    replayBack.setAttribute("aria-disabled", String(n === 0 || view === 0));
    replayForward.setAttribute("aria-disabled", String(!replaying()));
    replayLatest.setAttribute("aria-disabled", String(!replaying()));
  }

  /* Shows the position `delta` moves on from the one shown, as far as the opening or the latest. */
  function stepReplay(delta) {
    const n = replayLength();
    if (n === 0 || drag) return;
    const at = Math.min(n, Math.max(0, (replaying() ? view : n) + delta));
    view = at === n ? null : at;
    selected = null;
    hideTip();
    render();
  }

  replayFirst.addEventListener("click", () => stepReplay(-Infinity));
  replayBack.addEventListener("click", () => stepReplay(-1));
  replayForward.addEventListener("click", () => stepReplay(1));
  replayLatest.addEventListener("click", () => stepReplay(Infinity));
  document.addEventListener("keydown", e => {
    const delta = { ArrowLeft: -1, ArrowRight: 1, Home: -Infinity, End: Infinity }[e.key];
    if (delta === undefined || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    // Not where the arrows already mean something: a select, a field, or a dialog over the board.
    if (e.target.closest && e.target.closest("select, input, textarea, dialog, [popover]")) return;
    // Home and End scroll the page anywhere else, so they go to the ends only from the board or its controls.
    if (Math.abs(delta) === Infinity && !(e.target.closest && e.target.closest("#board, #replay"))) return;
    if (replayLength() === 0) return;
    e.preventDefault();
    stepReplay(delta);
  });

  // ---- what happened --------------------------------------------------------------------------

  function drawNews() {
    const news = document.getElementById("news");
    if (replaying()) {
      const key = "replay:" + view;
      if (key === lastNews) return;
      lastNews = key;
      news.textContent = view === 0 ? "The opening position." :
        "Move " + view + ": " + moveText(state.replay.moves[view - 1]);
      return;
    }
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
    const a = lm.side + "'s " + rankName(lm.battle.attacker), d = them + "'s " + rankName(lm.battle.defender);
    if (lm.battle.result === "AttackerWins") return a + " took " + d + " on " + at + ".";
    if (lm.battle.result === "DefenderWins") return a + " attacked " + d + " on " + at + " and lost.";
    return a + " and " + d + " both fell on " + at + ".";
  }

  function endingText() {
    if (state.ending === "conceded") return other(state.winner) + " conceded — " + state.winner + " wins";
    if (state.ending === "flag") return state.winner + " captured the flag";
    if (state.ending === "no-moves")
      return state.isDrawn ? "neither side can move — drawn" : other(state.winner) + " cannot move — " + state.winner + " wins";
    if (state.ending === "cap") return "move limit reached — drawn";
    return state.isDrawn ? "drawn" : state.winner + " wins";
  }

  // Strongest first, as the key lists them.
  const STRONGEST_FIRST = ["Marshal", "General", "Colonel", "Major", "Captain", "Lieutenant", "Sergeant", "Miner",
                           "Scout", "Spy", "Bomb", "Flag"];

  /* The other side's army as it stands, rank by rank — a player's opponent's, or on the public board
   * both — once the setups are in and pieces can have been lost. */
  function drawRemaining() {
    const box = document.getElementById("remaining");
    const sides = !state || state.phase === "setup" ? [] : state.you ? [other(state.you)] : ["Red", "Blue"];
    box.hidden = sides.length === 0;
    box.innerHTML = sides.map(side => {
      const lost = (lostShown().find(l => l.side === side) || { ranks: [] }).ranks;
      const items = STRONGEST_FIRST.map(rank => {
        const of = ARMY.find(a => a[0] === rank)[1];
        const left = of - lost.filter(r => r === rank).length;
        const said = rankName(rank) + ": " + left + " of " + of;
        return '<li class="' + (left ? "" : "gone") + '" title="' + said + '">' +
          '<span class="chip ' + side + '" aria-hidden="true"><svg class="icon' + (rank === "Marshal" ? " wide" : "") +
          '" focusable="false"><use href="#rank-' + rank + '"/></svg></span>' +
          '<span aria-hidden="true">' + left + '</span><span class="sr-only">' + said + '</span></li>';
      }).join("");
      const id = "remaining-" + side;
      return '<section aria-labelledby="' + id + '"><h2 id="' + id + '">' + side + " has " + (40 - lost.length) +
        " of 40 pieces left</h2><ul>" + items + "</ul></section>";
    }).join("");
  }

  function drawLost() {
    const lost = document.getElementById("lost");
    if (!state || state.phase === "setup") { lost.innerHTML = ""; return; }
    lost.innerHTML = lostShown().map(l => {
      const counts = {};
      l.ranks.forEach(r => { counts[rankName(r)] = (counts[rankName(r)] || 0) + 1; });
      const text = Object.keys(counts).map(r => r + (counts[r] > 1 ? " ×" + counts[r] : "")).join(", ") || "nothing";
      return "<p>" + l.side + " has lost: " + escapeHtml(text) + "</p>";
    }).join("") + "<p>" + (replaying() ? view : state.moveCount) + " of " + state.maxMoves + " moves</p>";
  }

  /* The sides whose clock ran out in a live match, which is how it ended if there are any. */
  /* "win" or "lose" for a player viewing their finished match, and nothing for a draw, a match
   * still being played, or the public board. Decided as the status line decides it: by whoever ran
   * out of time where somebody did, otherwise by the winner. */
  function result() {
    if (!state || !state.isCompleted || !state.you || publicView) return null;
    const late = ranOut();
    if (late.length) return late.includes(state.you) ? "lose" : "win";
    if (state.isDrawn || !state.winner) return null;
    return state.winner === state.you ? "win" : "lose";
  }

  function ranOut() {
    if (!state || !state.clock) return [];
    return state.players.filter(p => state.clock.timedOut.includes(p.participantId)).map(p => p.side);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function show(message) { document.getElementById("error").textContent = message || ""; }

  /* `pending` lets go once the state has moved past it, however the news arrived: the move's own
   * answer, a poll, a push. So a stalled request cannot hold the board once the page knows better. */
  function settlePending() {
    if (pending && (!state || state.isCompleted || state.moveCount !== pending.count)) {
      clearTimeout(pending.timer);
      pending = null;
    }
  }

  /* A setup or a concession out, which `pending` does not cover: a second press of Deploy or
   * Concede while the first is out is refused by the engine ("already deployed"), and that refusal,
   * newer than the first press's success, would be shown as if the first had failed. */
  let sending = false;

  /* A setup, a concession or a move: `body` is {setup: [...]}, {isConcession: true} or {from, to}. */
  async function submit(body) {
    // A move is shown made from now until it is answered, whatever the answer; see `pending`. It
    // remembers the move count it was made at, which is how a newer state is told from an older.
    const move = body.from !== undefined ? { from: body.from, to: body.to, count: state.moveCount, timer: null } : null;
    if (!move) {
      if (sending) return;
      sending = true;
    }
    show("");
    const ticket = ask();
    if (move) {
      pending = move;
      // Nothing heard at all — neither an answer nor a state that has moved on — is let go of after
      // a while, and the board shown as the engine last described it. Not a retry: the request may
      // yet land, and if it does a second move is refused by the engine as out of turn, and the
      // board catches up from the next poll or push.
      move.timer = setTimeout(() => {
        if (pending !== move) return;
        pending = null;
        tell(ticket, errors.notAnswered);
        render();
        refresh();
      }, 15000);
      render();
    }
    try {
      const response = await send(movesUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }, ticket);
      if (!response) return;
      const answer = await response.json();
      // A refusal is dropped under a newer state, which says more; see PlayLive for the two orders.
      if (!response.ok) { if (!overtaken(ticket)) tell(ticket, answer.error || response.statusText); return; }
      // Clears an earlier move's refusal, but not a later one's: that is still the news.
      tell(ticket, "");
      if (latest(ticket)) state = answer;
      // Overtaken by a refresh asked while the move was out, whose answer may predate it — a poll
      // can be answered before the move it was asked after is made. Showing that would put the
      // piece back until the next poll, so ask once more, now that the move is made, before
      // letting go of it.
      else if (move) await refresh();
    } finally {
      if (!move) sending = false;
      if (move) clearTimeout(move.timer);
      if (move && pending === move) pending = null;
      render();
    }
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
        tell(ticket, errors.noSeat);
        return null;
      }
      return response;
    } catch (e) {
      if (!overtaken(ticket)) tell(ticket, errors.unreachable);
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

  /* The rules, as a modal. Escape closes it, as does a click on the backdrop around it. Without
   * dialogs — Safari before 15.4 — it opens in place in the page instead. */
  const rules = document.getElementById("rules"), rulesButton = document.getElementById("rules-button");
  rulesButton.addEventListener("click", () => {
    document.getElementById("rules-cap").textContent = state ? String(state.maxMoves) : "a set number of";
    if (typeof rules.showModal === "function") rules.showModal();
    else { rules.setAttribute("open", ""); rules.querySelector(".close").focus(); }
  });
  rules.querySelector(".close").addEventListener("click", () => {
    if (typeof rules.close === "function") rules.close(); else rules.removeAttribute("open");
    rulesButton.focus();
  });
  rules.addEventListener("click", e => {
    if (e.target !== rules || typeof rules.close !== "function") return;
    const box = rules.getBoundingClientRect();
    if (e.clientX < box.left || e.clientX > box.right || e.clientY < box.top || e.clientY > box.bottom) rules.close();
  });

  /* Without popovers — Safari before 17 — the key would sit open in the page for good. There the
   * buttons show and hide it instead, and it is a section of the page rather than a layer over it. */
  if (!HTMLElement.prototype.hasOwnProperty("popover")) {
    const key = document.getElementById("key"), opener = document.getElementById("key-button");
    key.hidden = true;
    opener.setAttribute("aria-expanded", "false");
    const show = open => {
      key.hidden = !open;
      opener.setAttribute("aria-expanded", String(open));
      (open ? key.querySelector(".close") : opener).focus();
    };
    opener.addEventListener("click", () => show(key.hidden));
    key.querySelector(".close").addEventListener("click", () => show(false));
  }

  render();
  if (!state && mayFetch()) refresh();
  keepCurrent(refresh, () => mayFetch() && (!state || !state.isCompleted));
