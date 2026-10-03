package com.vivi.stratego

import upickle.default.write
import com.vivi.engine.{LoginConfig, MessageBoards, PlayLive, SignIn, TurnTimer}
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
  /* The two armies carry white text: --red is 6.6:1 against it and --blue 6.7:1. The insignia's
     metals are drawn only on a piece, in either theme: --silver is 5.1:1 against --red and 5.2:1
     against --blue, --gold 4.0:1 and 4.1:1 — over the 3:1 a graphic needs. --black (2.8:1 on --blue),
     --brown (1.1:1 on --red), --green (1.2:1 on --red) and a flag in the other army's colour (1.0:1) are not, so each is drawn
     inside a --halo outline, 5.8:1 against --red and 5.9:1 against --blue. */
  :root { color-scheme: light dark; --line: #8884; --ink: #222; --paper: #fafafa; --error: #b3261e;
          --square: #e9e4d4; --water: #8fbcd9; --red: #b3261e; --blue: #1d4ed8; --mark: #e6a700; --focus: #6d28d9;
          --silver: #dfe3e8; --gold: #f3c34a; --black: #111; --brown: #7b4a1f; --green: #3d7a2a;
          --halo: #f4f1ea; }
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
  /* --flag: each army's flag is drawn in the other's colour. */
  #board button.Red { background: var(--red); color: #fff; --flag: var(--blue); }
  #board button.Blue { background: var(--blue); color: #fff; --flag: var(--red); }
  #board button.lake { background: var(--water); }
  /* A piece's icon, and its number tucked into the corner — the number is what decides a fight, so it stays. */
  .icon { fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
  #board .icon { position: absolute; left: 24%; top: 26%; width: 56%; height: 56%; pointer-events: none; }
  #board .icon.alone { left: 18%; top: 18%; width: 64%; height: 64%; }
  /* Four stars in a row, as a general wears them, given the whole width or they shrink to dots. */
  #board .icon.wide { left: 5%; top: 42%; width: 90%; height: 32%; }
  #board .number { position: absolute; top: 2px; left: 3px; font-size: clamp(.6rem, 2.4vw, .8rem); }
  #board button[aria-disabled="false"] { cursor: pointer; }
  /* A piece that can be picked up. Only these take the touch for themselves, so a swipe that starts
     anywhere else on the board still scrolls the page. */
  #board { -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
  #board button.draggable { touch-action: none; cursor: grab; }
  /* The piece being dragged stays on its square, faded, while a copy follows the finger. */
  #board button.lifted { opacity: .35; }
  /* The square the carried piece will land on: the piece sits in it, ringed. */
  #board button.over { outline: 4px solid var(--mark); outline-offset: 0; z-index: 1; }
  #board button.ghost { position: fixed; z-index: 10; pointer-events: none; transform: scale(1.15);
                        box-shadow: 0 6px 16px rgba(0, 0, 0, .35); cursor: grabbing; }
  /* Settled into the square it will land on, at that square's size, so there is no doubt which. */
  #board button.ghost.snapped { transform: none; box-shadow: 0 0 0 4px var(--mark), 0 4px 10px rgba(0, 0, 0, .3); }
  /* Under a finger, larger, so that it shows round the fingertip. */
  #board button.ghost.touch { transform: scale(1.6); }
  #board button.ghost.touch.snapped { transform: scale(1.5); }
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
  /* Conceding: set apart from the turn's own controls, and asked twice, since it cannot be undone. */
  #concede-bar { margin-top: 1.25rem; }
  #concede-bar[hidden] { display: none; }
  #concede, #concede-dialog button { font: inherit; font-size: 1rem; min-height: 44px; padding: 0 1rem; border-radius: 6px;
                                     border: 1px solid var(--line); background: var(--paper); color: var(--ink);
                                     cursor: pointer; }
  #concede { color: var(--error); border-color: currentColor; }
  #concede-dialog { max-width: min(24rem, calc(100vw - 32px)); box-sizing: border-box; padding: 1.25rem;
                    border: 1px solid var(--line); border-radius: 8px; background: var(--paper); color: var(--ink); }
  #concede-dialog::backdrop { background: rgba(0, 0, 0, .35); }
  #concede-dialog h2 { font-size: 1.125rem; margin: 0 0 .5rem; }
  #concede-dialog p { margin: 0 0 1rem; }
  #concede-dialog .actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: flex-end; }
  #concede-dialog .give-up { background: var(--error); border-color: var(--error); color: #fff; }
  #concede:focus-visible, #concede-dialog :focus-visible { outline: 3px solid var(--focus); outline-offset: 2px; }
  #lost { margin-top: 1rem; font-size: .875rem; }
  #lost p { margin: .25rem 0; }
  /* The key: a button beside the board that opens it over the page, so it is in reach mid-game. */
  .board-bar { display: flex; justify-content: flex-end; max-width: 36rem; margin: 0 auto .5rem; }
  .board-bar button, #key .close { font: inherit; font-size: 1rem; min-height: 44px; min-width: 44px; padding: 0 1rem;
                                   border-radius: 6px; border: 1px solid var(--line); background: var(--paper);
                                   color: var(--ink); cursor: pointer; }
  .board-bar button:focus-visible, #key :focus-visible { outline: 3px solid var(--focus); outline-offset: 2px; }
  #key { width: min(28rem, calc(100vw - 32px)); max-height: 85vh; overflow: auto; box-sizing: border-box; padding: 1rem;
         border: 1px solid var(--line); border-radius: 8px; background: var(--paper); color: var(--ink);
         text-align: left; box-shadow: 0 12px 32px rgba(0, 0, 0, .3); }
  #key::backdrop { background: rgba(0, 0, 0, .35); }
  #key .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: .5rem; }
  #key h2 { font-size: 1.125rem; margin: 0; }
  #key .close { padding: 0; }
  #key dl { display: grid; grid-template-columns: auto 1fr; gap: .5rem .75rem; margin: 0; }
  #key dt { font: 700 1rem ui-monospace, monospace; display: flex; align-items: center; gap: .375rem; }
  #key dd { margin: 0; align-self: center; }
  #key dd strong { display: block; }
  #key dd span { display: block; font-size: .875rem; opacity: .85; }
  #key .rule { margin: 1rem 0 .75rem; font-weight: 600; }
  /* Each icon on a piece of its own, since the metals are drawn for a piece and silver would vanish on the page. */
  #key .chip { display: inline-grid; place-items: center; position: relative; width: 2rem; height: 2rem;
               border-radius: 3px; background: var(--red); color: #fff; --flag: var(--blue); }
  #key .chip.Blue { background: var(--blue); --flag: var(--red); }
  #key .icon { width: 1.4rem; height: 1.4rem; }
  #key .icon.wide { width: 1.8rem; height: .65rem; }
  /* The marks a square can carry, drawn as the board draws them. */
  #key .dot { position: absolute; top: 3px; right: 3px; width: 5px; height: 5px; border-radius: 50%; background: #fff; }
  /* The name of the piece under a resting pointer, after a second. */
  #tip { position: fixed; z-index: 20; padding: .375rem .625rem; border-radius: 6px; background: var(--ink);
         color: var(--paper); font-size: .875rem; line-height: 1.3; white-space: nowrap;
         box-shadow: 0 4px 12px rgba(0, 0, 0, .25); }
  #tip[hidden] { display: none; }
  #key .bar { position: absolute; left: 25%; right: 25%; bottom: 2px; height: 2px; background: #fff; }
${SignIn.css}
${PlayLive.css}
${MessageBoards.css}
${TurnTimer.css}
  #players h2 { font-size: 1rem; font-weight: 600; margin: 1.25rem 0 .25rem; }
  #seats { font-size: .875rem; opacity: .7; }
  #seats div { margin: .125rem 0; }
  #error { color: var(--error); min-height: 1.5rem; margin-top: .75rem; font-size: .875rem; }
</style>
</head>
<body>
$icons
${MessageBoards.layoutStart}
<main>
  <h1>capture the flag</h1>
  <!-- Announced: the other player's move, and the result, arrive while this page is idle. -->
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  ${TurnTimer.markup}
  <div class="board-bar">
    <button type="button" id="key-button" popovertarget="key" aria-haspopup="dialog">Key</button>
  </div>
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
  <div id="concede-bar" hidden>
    <button type="button" id="concede" aria-haspopup="dialog">Concede</button>
  </div>
  <dialog id="concede-dialog" aria-labelledby="concede-title" aria-describedby="concede-text">
    <h2 id="concede-title">Concede this match?</h2>
    <p id="concede-text">Your opponent wins. This cannot be undone.</p>
    <div class="actions">
      <!-- First, so that it has the focus when the dialog opens: the safe answer is the default. -->
      <button type="button" id="concede-cancel" autofocus>Keep playing</button>
      <button type="button" id="concede-confirm" class="give-up">Concede</button>
    </div>
  </dialog>
  <div id="lost"></div>
  <!-- What a square's label already says to a screen reader, shown to a mouse or keyboard after a
       second's rest; so it is hidden from the accessibility tree rather than said twice. -->
  <div id="tip" aria-hidden="true" hidden></div>
  <div id="key" popover role="dialog" aria-labelledby="key-title">
    <div class="head">
      <h2 id="key-title">Key</h2>
      <button type="button" class="close" popovertarget="key" popovertargetaction="hide" aria-label="Close the key">✕</button>
    </div>
    <dl>
      $key
    </dl>
    <p class="rule">The lower number wins a battle. Equal ranks both fall.</p>
    <dl>
      <dt><span class="chip Blue">?</span></dt><dd>An enemy piece you have not seen</dd>
      <dt><span class="chip Blue">?<span class="dot"></span></span></dt><dd>An enemy piece that has moved — so it is not a bomb or the flag</dd>
      <dt><span class="chip">${iconOf(
              "Sergeant"
            )}<span class="bar"></span></span></dt><dd>One of your pieces the enemy has seen</dd>
    </dl>
  </div>
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
${MessageBoards.layoutEnd}
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${MessageBoards.script}
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
    signin.hidden = !login || noSeat || (state && state.you) || (publicView && !mbWantsSignIn());

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
  // What a rank is called on this page, where it differs from the engine's own name for it: the two
  // generals are named for the insignia they wear. The engine's names are what the API and stored
  // matches use, so they stay; only what a player reads changes.
  const NAME = { Marshal: "General", General: "Brigadier General" };
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
    // A move that has been made but not yet answered is shown made; the answer replaces it either
    // way, and a refused one goes back.
    if (pending && by[pending.from]) {
      by[pending.to] = Object.assign({}, by[pending.from], { square: pending.to, moved: true });
      delete by[pending.from];
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
    b.addEventListener("focus", () => {
      focusSquare = squareAt(d);
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
    let text = at + ", " + (mine ? "your " : p.side + " ") + (p.rank ? rankName(p.rank) : "unknown piece");
    if (p.moved && !p.rank) text += ", has moved";
    if (mine && p.revealed) text += ", seen by " + other(p.side);
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
    if (!p.rank) return "Unknown " + p.side + " piece" + (p.moved ? " — has moved" : "");
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
    const last = state && state.lastMove ? [state.lastMove.from, state.lastMove.to] : [];
    const targets = selected !== null && myTurn() ? movesFrom(selected) : [];
    let focusable = false;
    for (let d = 0; d < 100; d++) {
      const sq = squareAt(d), p = by[sq], b = squares[d];
      if (LAKES.includes(sq) || !p) b.textContent = "";
      else if (!p.rank) b.textContent = "?";
      else b.innerHTML = '<svg class="icon' + (p.rank === "Marshal" ? " wide" : NUMBER[p.rank] ? "" : " alone") + '" aria-hidden="true" focusable="false">' +
        '<use href="#rank-' + p.rank + '"/></svg>' + (NUMBER[p.rank] ? '<span class="number">' + NUMBER[p.rank] + "</span>" : "");
      b.className = LAKES.includes(sq) ? "lake" : p ? p.side : "";
      b.classList.toggle("moved", !!(p && p.moved && !p.rank));
      b.classList.toggle("known", !!(p && p.rank && p.revealed && state && p.side === state.you));
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
    board.setAttribute("aria-label", "board, " + (state && state.you === "Blue" ? "Blue" : "Red") + " at the bottom");
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
    if (pending) return false;
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
    document.getElementById("concede-bar").hidden = !(state && state.you && !state.completed);
    // Not while a move is out: it is shown made, so its piece is no longer where the controls
    // would offer it from, and it is the only move this turn has.
    const setup = deploying(), play = myTurn() && !pending;
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
  function concede() { submit({ concede: true }); }
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
    const a = lm.side + "'s " + rankName(lm.battle.attacker), d = them + "'s " + rankName(lm.battle.defender);
    if (lm.battle.result === "AttackerWins") return a + " took " + d + " on " + at + ".";
    if (lm.battle.result === "DefenderWins") return a + " attacked " + d + " on " + at + " and lost.";
    return a + " and " + d + " both fell on " + at + ".";
  }

  function endingText() {
    if (state.ending === "conceded") return other(state.winner) + " conceded — " + state.winner + " wins";
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
      l.ranks.forEach(r => { counts[rankName(r)] = (counts[rankName(r)] || 0) + 1; });
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
    // A move is shown made from now until it is answered, whatever the answer; see `pending`.
    const move = body.from !== undefined ? body : null;
    if (move) { pending = move; render(); }
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
  function signedIn() { refresh(); refreshMessages(); }

  /* Whether there is any point asking for the state. With a login configured and no session, the
   * answer is a 401 — and asking every two seconds scrolls the console with them and, worse, kept
   * rebuilding the sign-in form under the player's cursor. Public boards and the trusted local mode
   * have no session to wait for and are fetched as before. */
  function mayFetch() { return !noSeat && (!login || publicView || isSignedIn()); }

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
  refreshMessages();
  keepCurrent(() => Promise.all([refresh(), refreshMessages()]), () => mayFetch() && (!state || !state.completed));
</script>
</body>
</html>
"""
    }

    /** One icon per rank, drawn for this page. The officers and the sergeant wear simplified US Army insignia, which as
      * works of the US government are free to use, in their metals: four silver stars for the Marshal (a general), one
      * for the General (a brigadier), a silver eagle, a gold oak leaf, two silver bars joined, one silver bar, and
      * three chevrons, in green. The Miner's brown pick, the Scout's galloping horse — knees and hocks folding its legs
      * in under it, as a horse's do — the black mask and bomb, and the flag — in the other army's colour, through
      * `--flag` — are plain symbols, not any published Stratego edition's artwork, which is copyrighted.
      *
      * Each is a 24-unit square, except the four stars, which are a strip so that they can take a piece's whole width.
      * What is not metal is `currentColor`, the white of a coloured piece. Placed with `<use href="#rank-Marshal">`,
      * and decoration only — a square's label names its rank in words.
      */
    /** The key's lines, strongest first, named as the page's `NAME` names them. Numbered the classic European way — 1
      * is the Marshal, and the lower number wins — which is only how ranks are shown: `Rank.strength` is what decides a
      * fight. The page's `NUMBER` must agree.
      */
    private val key: String =
        List(
          ("Marshal", "1", "General", "The strongest piece — but it falls to the Spy, if the Spy attacks it."),
          ("General", "2", "Brigadier General", ""),
          ("Colonel", "3", "Colonel", ""),
          ("Major", "4", "Major", ""),
          ("Captain", "5", "Captain", ""),
          ("Lieutenant", "6", "Lieutenant", ""),
          ("Sergeant", "7", "Sergeant", ""),
          ("Miner", "8", "Miner", "The only piece that survives attacking a bomb, which it takes."),
          ("Scout", "9", "Scout", "Moves any distance in a straight line, and may attack at the end of it."),
          ("Spy", "", "Spy", "Loses any other battle — but takes the General, if the Spy attacks first."),
          ("Bomb", "", "Bomb", "Never moves. Destroys any piece that attacks it except a Miner."),
          ("Flag", "", "Flag", "Never moves. Take the enemy's to win.")
        ).map((rank, number, name, note) => keyEntry(rank, number, name, note)).mkString("\n      ")

    /** A line of the key: the icon and number a rank is drawn with, its name, and what is special about it. */
    private def keyEntry(rank: String, number: String, name: String, note: String): String = {
        val noted = if (note.isEmpty) "" else s"<span>$note</span>"
        s"""<dt><span class="chip">${iconOf(rank)}</span>$number</dt><dd><strong>$name</strong>$noted</dd>"""
    }

    private def iconOf(rank: String): String =
        s"""<svg class="icon${
                if (rank == "Marshal") " wide" else ""
            }" aria-hidden="true" focusable="false"><use href="#rank-$rank"/></svg>"""

    private val icons: String =
        """<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false"><defs>
  <symbol id="rank-Marshal" viewBox="0 8.4 24 7.2"><path style="fill:var(--silver)" stroke="none" d="M3.20 8.60 L4.04 10.84 L6.43 10.95 L4.56 12.44 L5.20 14.75 L3.20 13.43 L1.20 14.75 L1.84 12.44 L-0.03 10.95 L2.36 10.84zM9.07 8.60 L9.91 10.84 L12.30 10.95 L10.43 12.44 L11.07 14.75 L9.07 13.43 L7.07 14.75 L7.71 12.44 L5.84 10.95 L8.23 10.84zM14.93 8.60 L15.77 10.84 L18.16 10.95 L16.29 12.44 L16.93 14.75 L14.93 13.43 L12.93 14.75 L13.57 12.44 L11.70 10.95 L14.09 10.84zM20.80 8.60 L21.64 10.84 L24.03 10.95 L22.16 12.44 L22.80 14.75 L20.80 13.43 L18.80 14.75 L19.44 12.44 L17.57 10.95 L19.96 10.84z"/></symbol>
  <symbol id="rank-General" viewBox="0 0 24 24"><path style="fill:var(--silver)" stroke="none" d="M12.00 2.10 L14.59 9.03 L21.99 9.36 L16.19 13.96 L18.17 21.09 L12.00 17.01 L5.83 21.09 L7.81 13.96 L2.01 9.36 L9.41 9.03z"/></symbol>
  <symbol id="rank-Colonel" viewBox="0 0 24 24"><path style="fill:var(--silver)" stroke="none" d="M12 6.2c-1 0-1.8.8-1.8 1.8 0 .5.2.9.5 1.2L3.2 4.6c-.7-.4-1.5.2-1.2 1l2 5.6c.6 1.7 2.2 2.8 4 2.8h1.4l-1.6 2.6 1.9.3-.6 3.3 1.6-1.1 1.3 2.1 1.3-2.1 1.6 1.1-.6-3.3 1.9-.3-1.6-2.6H16c1.8 0 3.4-1.1 4-2.8l2-5.6c.3-.8-.5-1.4-1.2-1l-7.5 4.6c.3-.3.5-.7.5-1.2 0-1-.8-1.8-1.8-1.8z"/><path style="fill:var(--silver)" stroke="none" d="M10.3 7.6 8.6 8.3l1.9.6z"/></symbol>
  <symbol id="rank-Major" viewBox="0 0 24 24"><path style="fill:var(--gold)" stroke="none" d="M12 1.8c1.6 1.3 2 2.7 1.3 4 1.7-.7 3.2-.3 3.7 1-.6 1-1.6 1.5-2.8 1.6 1.9.2 3.3 1.1 3.5 2.7-1 .7-2.3.7-3.6.2 1.5 1 2.3 2.3 2 3.9-1.3.4-2.6 0-3.6-.9.4 1.5.1 2.9-.9 3.9l-.6 1.2v3.2h-1v-3.2l-.6-1.2c-1-1-1.3-2.4-.9-3.9-1 .9-2.3 1.3-3.6.9-.3-1.6.5-2.9 2-3.9-1.3.5-2.6.5-3.6-.2.2-1.6 1.6-2.5 3.5-2.7-1.2-.1-2.2-.6-2.8-1.6.5-1.3 2-1.7 3.7-1-.7-1.3-.3-2.7 1.3-4z"/><path d="M12 5.5v13" style="stroke:#0005" stroke-width="1"/></symbol>
  <symbol id="rank-Captain" viewBox="0 0 24 24"><g style="fill:var(--silver)" stroke="none"><rect x="5" y="3" width="4.5" height="18" rx=".6"/><rect x="14.5" y="3" width="4.5" height="18" rx=".6"/><rect x="9" y="5" width="6" height="1.6"/><rect x="9" y="17.4" width="6" height="1.6"/></g></symbol>
  <symbol id="rank-Lieutenant" viewBox="0 0 24 24"><rect style="fill:var(--silver)" stroke="none" x="9.5" y="3" width="5" height="18" rx=".6"/></symbol>
  <symbol id="rank-Sergeant" viewBox="0 0 24 24"><g fill="none" stroke-linecap="butt" stroke-linejoin="miter"><path style="stroke:var(--halo)" stroke-width="4.4" d="m4 9 8-5 8 5M4 14.5l8-5 8 5M4 20l8-5 8 5"/><path style="stroke:var(--green)" stroke-width="2.6" d="m4 9 8-5 8 5M4 14.5l8-5 8 5M4 20l8-5 8 5"/></g></symbol>
  <symbol id="rank-Miner" viewBox="0 0 24 24"><g transform="rotate(-35 12 12)" style="fill:none;stroke-linecap:round"><path d="M3 9.5c5-5 13-5 18 0M12 6v15.5" style="stroke:var(--halo);stroke-width:4"/><path d="M3 9.5c5-5 13-5 18 0M12 6v15.5" style="stroke:var(--brown);stroke-width:2.2"/></g></symbol>
  <symbol id="rank-Scout" viewBox="0 0 24 24"><g fill="currentColor" stroke="none"><ellipse cx="11" cy="9.8" rx="5.6" ry="2.9" transform="rotate(-4 11 9.8)"/><path d="M13.6 7.4 17.4 2.8 20.2 3.9 16.8 10.8Z"/><path d="M17.2 3 17.8.9 19 2.3 23.3 6.3C23.8 6.8 23.4 7.6 22.8 7.5L21 7.4 18 5.8Z"/><path d="M17.4 3.1 16 4.6 15.2 6.6 16.6 5.2Z"/><path d="M5.8 8.2C3.6 6.6 1.8 6.4.4 7.4 1.8 7.8 2.6 9.2 2.8 11.4 3.8 10 4.8 9.6 6.2 10Z"/></g><g fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15.8 10.8 20 12.8 19.2 16.2"/><path d="M15 11.6 18 13.6 16.2 16.4"/><path d="M7.4 10.8 3.4 12.6 3.8 16.2"/><path d="M8.6 11.8 7 15 9.4 17.4"/></g></symbol>
  <symbol id="rank-Spy" viewBox="0 0 24 24"><path fill-rule="evenodd" style="fill:var(--black);stroke:var(--halo);stroke-width:2;paint-order:stroke;stroke-linejoin:round" d="M2 9c0-1.5 1-2 2.5-2C8 7 10 9 12 9s4-2 7.5-2C21 7 22 7.5 22 9c0 4-2 7.5-5 7.5-2.5 0-3.5-2.5-5-2.5s-2.5 2.5-5 2.5c-3 0-5-3.5-5-7.5zM4.5 10.5a2.75 1.75 0 1 0 5.5 0 2.75 1.75 0 1 0-5.5 0zM14 10.5a2.75 1.75 0 1 0 5.5 0 2.75 1.75 0 1 0-5.5 0z"/></symbol>
  <symbol id="rank-Bomb" viewBox="0 0 24 24"><circle cx="10" cy="14" r="7" style="fill:var(--black);stroke:var(--halo);stroke-width:2;paint-order:stroke;stroke-linejoin:round"/><path d="m14.5 9.5 3-3M20 2v2M23 5h-2M22 3l-1.5 1.5"/></symbol>
  <symbol id="rank-Flag" viewBox="0 0 24 24"><path d="M6 21.5V3"/><path style="fill:var(--flag);stroke:var(--halo);stroke-width:2;paint-order:stroke;stroke-linejoin:round" d="M6 4h12l-3 4 3 4H6z"/></symbol>
</defs></svg>"""

    private def outcome(state: Protocol.StateResponse): String =
        if (state.clock.exists(_.timedOut.nonEmpty)) "time ran out"
        else if (state.draw) "drawn"
        else if (state.ending.contains("conceded"))
            state.winner.flatMap(Side.parse).map(w => s"${w.other} conceded — $w wins").getOrElse("over")
        else if (state.ending.contains("flag")) state.winner.map(w => s"$w captured the flag").getOrElse("over")
        else state.winner.map(w => s"$w wins").getOrElse("over")
}
