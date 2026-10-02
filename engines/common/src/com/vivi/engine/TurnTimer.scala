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
  * is next asked about it — so when the count reaches nothing, the page asks, and goes on asking every half second
  * until the engine agrees. That is what makes a live match end on time while anybody is looking at it, Play Live or
  * not: the page that saw the clock run out is the request that ends the match, and Play Live tells everybody else.
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
    let deadline = null, turn = null, yours = false, chess = false, warned = false, asked = false, ticker = null;
    // Under a chess clock, the viewer's own budget while it is not running: what they will have
    // when it is their move again.
    let banked = null;

    function clockText(ms) {
      const s = Math.ceil(ms / 1000);
      return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
    }
    function say(text) { if (face.textContent !== text) face.textContent = text; }
    function stop() { if (ticker) { clearInterval(ticker); ticker = null; } deadline = null; }

    function tick() {
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
      // The engine ends the match when it is next asked, so the page that watched the clock run out
      // asks. Once per answer: the next state re-arms it, and says so if the engine's clock has not
      // quite got there yet.
      if (left === 0 && !asked) { asked = true; setTimeout(refresh, 500); }
    }

    return function show(clock, me) {
      face.hidden = !clock;
      if (!clock) { stop(); return; }
      asked = false;
      face.classList.remove("short");
      if (!clock.seats.length) {
        stop();
        say(clock.timedOut.length ? "time ran out" : "");
        face.hidden = !clock.timedOut.length;
        return;
      }
      chess = clock.kind === "TOTAL";
      // The viewer's own clock when it is running; otherwise whichever running clock runs out first,
      // since that is the next thing that can happen.
      const running = clock.seats.filter(s => s.running);
      const mine = running.find(s => s.participantId === me);
      const shown = mine || running.sort((a, b) => a.remainingMillis - b.remainingMillis)[0];
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
      deadline = Date.now() + shown.remainingMillis;
      yours = !!mine;
      if (!ticker) ticker = setInterval(tick, 250);
      tick();
    };
  }
"""
}
