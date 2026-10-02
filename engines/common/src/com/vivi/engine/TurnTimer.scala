package com.vivi.engine

/** The play page's half of a live match's [[TurnClock]]: a countdown of the turn being played.
  *
  * A page puts [[TurnTimer.markup]] where the clock belongs and [[TurnTimer.css]] among its styles, includes
  * [[TurnTimer.script]], and hands its own refresh to `turnClock` once:
  *
  * {{{
  * const showClock = turnClock(refresh);
  * ...
  * showClock(state && state.clock, me);   // from render()
  * }}}
  *
  * `state.clock` is the [[ClockView]] a game puts in its state answer, absent for a match that is not live — and then
  * the clock is not shown at all. `me` is the viewer's participant id, or nothing on the public board: the viewer's own
  * clock is the one shown and warned about while they are being waited on, and otherwise the other player's.
  *
  * What the page counts down is the time the engine said was left when it answered, not a deadline: a phone whose clock
  * is a minute out still shows the right amount. And it is only a display. The engine is what ends the match, when it
  * is next asked about it — so when the first running clock reaches nothing, whoever's it is, the page asks, and goes
  * on asking every second, one request at a time, until the engine agrees. That is what makes a live match end on time
  * while anybody is looking at it, Play Live or not: the page that saw the clock run out is the request that ends the
  * match, and Play Live tells everybody else.
  *
  * Under a chess clock (`kind` TOTAL) the face says what is left on the running clock, and adds the viewer's own budget
  * while it is the other player's move.
  *
  * Accessibility: the face is a `timer`, which is not announced as it ticks — a reader announcing every second would be
  * unusable. Instead one warning is spoken, politely, when ten seconds are left of the viewer's own move.
  */
object TurnTimer {

    val markup: String =
        """<p id="turn-clock" role="timer" aria-live="off" hidden></p>
  <p id="turn-clock-warning" class="turn-clock-sr" role="status" aria-live="polite"></p>"""

    val css: String =
        """  #turn-clock { margin: 0 0 1rem; font-size: 1.25rem; font-weight: 700; font-variant-numeric: tabular-nums; }
  #turn-clock.short { color: var(--error); }
  /* Read out, not shown: the face above already says the same thing to anyone who can see it. */
  .turn-clock-sr { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
                   clip: rect(0 0 0 0); white-space: nowrap; border: 0; }"""

    val script: String =
        """  /* A live match's turn clock; see TurnTimer. Answers the function render() calls with the state's
   * clock and the viewer's participant id. */
  function turnClock(refresh) {
    const face = document.getElementById("turn-clock");
    const warning = document.getElementById("turn-clock-warning");
    // `deadline` is the clock on the face; `expiry` the first running clock to run out, which is
    // when the engine has to be asked. They differ when the face is the viewer's own clock and
    // another player's runs out sooner.
    let deadline = null, expiry = null, turn = null, yours = false, chess = false, warned = false, ticker = null;
    // Under a chess clock, the viewer's own budget while it is not running: what they will have
    // when it is their move again.
    let banked = null;
    // The clock last shown, so that rendering the same answer again keeps the deadlines it set
    // when it arrived instead of restarting them from now.
    let seen = null;
    // One check of the engine at a time, and a pause between a failed one and the next.
    let checking = false, lastCheck = 0;

    function clockText(ms) {
      const s = Math.ceil(ms / 1000);
      return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
    }
    function say(text) { if (face.textContent !== text) face.textContent = text; }
    function stop() { if (ticker) { clearInterval(ticker); ticker = null; } deadline = null; expiry = null; }

    /* The engine ends the match when it is next asked, so a page that has watched a clock run out
     * asks — at the first clock to run out, whoever's it is, since that is when the match ended.
     * Asked again every second until an answer says otherwise: a check that failed, or that
     * reached an engine whose clock is a moment behind, must not leave the page waiting for the
     * once-a-minute check. */
    function check() {
      if (expiry === null || Date.now() < expiry + 250 || checking || Date.now() - lastCheck < 1000) return;
      checking = true;
      lastCheck = Date.now();
      Promise.resolve()
        .then(refresh)
        .catch(() => {})
        .finally(() => { checking = false; });
    }

    function tick() {
      check();
      if (deadline === null) return;
      const left = Math.max(0, deadline - Date.now());
      say(left > 0 ? clockText(left) + " left " + (chess ? (yours ? "on your clock" : "on their clock")
                                                     : (yours ? "for your move" : "for their move"))
                   : "time is up") + (banked != null ? " · you have " + clockText(banked) : "");
      face.classList.toggle("short", left <= 10000);
      if (yours && !warned && left > 0 && left <= 10000) {
        warned = true;
        warning.textContent = "Ten seconds left on your clock.";
      }
    }

    return function show(clock, me) {
      face.hidden = !clock;
      if (!clock) { seen = null; stop(); return; }
      // The same answer rendered again: its deadlines were set when it arrived.
      if (clock === seen) { tick(); return; }
      seen = clock;
      face.classList.remove("short");
      if (!clock.seats.length) {
        stop();
        say(clock.timedOut.length ? "time ran out" : "");
        face.hidden = !clock.timedOut.length;
        return;
      }
      chess = clock.kind === "TOTAL";
      const now = Date.now();
      const running = clock.seats.filter(s => s.running);
      expiry = running.length ? now + Math.min(...running.map(s => s.remainingMillis)) : null;
      // The face shows the viewer's own clock when it is running; otherwise whichever runs out
      // first, since that is the next thing that can happen.
      const mine = running.find(s => s.participantId === me);
      const shown = mine || running.slice().sort((a, b) => a.remainingMillis - b.remainingMillis)[0];
      const own = clock.seats.find(s => s.participantId === me);
      banked = chess && own && !own.running && own.remainingMillis != null ? own.remainingMillis : null;
      if (!shown) {
        // Everyone being waited on has yet to open the board, and nobody's clock has started.
        stop();
        say("their clock starts when they open the game");
        return;
      }
      // A new turn rather than the same one read again: its clock started somewhere else.
      const id = shown.participantId + "@" + shown.startedAt;
      if (id !== turn) { turn = id; warned = false; warning.textContent = ""; }
      deadline = now + shown.remainingMillis;
      yours = !!mine;
      if (!ticker) ticker = setInterval(tick, 250);
      tick();
    };
  }
"""
}
