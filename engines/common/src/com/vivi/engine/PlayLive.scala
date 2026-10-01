package com.vivi.engine

import HtmlText.escapeJs

/** The play page's half of Play Live: the switch a player turns it on with, and what keeps the page current either way.
  *
  * A page puts [[PlayLive.markup]] where the switch belongs and [[PlayLive.css]] among its styles, includes [[script]]
  * after the sign-in's, and hands its own refresh to `keepCurrent` in place of the poll it would otherwise run:
  *
  * {{{
  * keepCurrent(refresh, () => mayFetch() && (!state || !state.completed));
  * }}}
  *
  * With Play Live off — the default, and the only thing on offer when the engine has no live url — that is the
  * two-second poll every page has always run. With it on, the page opens a connection and refreshes when told the match
  * has changed, checking once a minute besides in case a push was lost. While the connection is down the page polls as
  * before and reconnects with a growing pause, so turning Play Live on can make a page slower to hear of a move only
  * for as long as a connection is failing, and never stops it hearing at all.
  *
  * Every answer a page shows — a refresh, a move's answer, the first fetch — is asked for with a ticket from `ask()`
  * and shown only if `latest(ticket)`:
  *
  * {{{
  * const ticket = ask();
  * const response = await send(stateUrl, {});
  * ...
  * if (latest(ticket)) { state = answer; render(); }
  * }}}
  *
  * so that an answer overtaken by one asked for later is dropped rather than shown over it. Answers do arrive out of
  * order — a push lands while a fetch is out, a move's answer comes back after the refresh its own push started — and
  * with Play Live on, an older state shown over a newer one would stay until the minute check. Asked-for order is the
  * right order because every change is pushed after it is committed: the fetch that push starts is asked for after the
  * change, and so is never overtaken by an answer that predates it.
  *
  * The line a move's refusal is shown on is ordered separately, with `tell(ticket, message)`: a refusal, or the
  * clearing of one by a success, reaches it only if no answer asked for later has written there. Separately, because
  * the two orders differ. A refusal carries no state, so counting it as shown would drop a fetch asked for before it
  * whose state may be newer than the page's; and a success whose state is the newest shown may still be older news than
  * a refusal already on the line, which it must not clear. A refusal is also dropped under a newer state — `if
  * (!overtaken(ticket))` — since what that state shows says more.
  *
  * The choice is the player's and is remembered in `localStorage` under the game's name, like the sign-in's tokens are
  * kept under it: a player who wants it for one match of a game wants it for the next.
  *
  * @param storagePrefix
  *   the game's name, as its [[SignIn]] is given it
  */
class PlayLive(storagePrefix: String) {

    /** `liveUrl` is the engine's, when it offers Play Live. Expects `freshIdToken` from the sign-in's script, and
      * `publicView` and `show` from the page.
      */
    def script(liveUrl: Option[String], matchId: String): String =
        s"""  const liveUrl = ${liveUrl.map(u => s"\"${escapeJs(u)}\"").getOrElse("null")};
  const liveMatch = "${escapeJs(matchId)}";
  const LiveChoiceKey = "$storagePrefix.playLive";

  /* Answers are shown in the order they were asked for: states by `latest`, and the message line
   * by `tell`, each in its own order. See PlayLive. */
  let lastAsked = 0, lastShown = 0, lastTold = 0;
  function ask() { return ++lastAsked; }
  /* Whether a state asked for later has been shown, without marking anything. */
  function overtaken(ticket) { return ticket < lastShown; }
  function latest(ticket) {
    if (overtaken(ticket)) return false;
    lastShown = ticket;
    return true;
  }
  /* Shows `message` — a refusal, or "" to clear one — unless an answer asked for later already
   * wrote to the line. */
  function tell(ticket, message) {
    if (ticket < lastTold) return;
    lastTold = ticket;
    show(message);
  }

  /* Keeps the page current: by polling, or with Play Live on, by being told. `refresh` fetches the
   * state; `active` says whether there is still anything to wait for. */
  function keepCurrent(refresh, active) {
    const box = document.getElementById("live");
    const toggle = document.getElementById("live-toggle");
    const note = document.getElementById("live-status");
    let socket = null, open = false, opening = false, retries = 0, retryTimer = null;
    let lastFetch = Date.now(), lastPing = 0;

    box.hidden = !liveUrl;
    toggle.checked = !!liveUrl && chosen();
    toggle.addEventListener("change", () => { choose(toggle.checked); sync(); });

    // Storage can be refused outright — a private window, a browser set to block site data — and
    // the switch must still work for the visit, just without being remembered.
    function chosen() { try { return localStorage.getItem(LiveChoiceKey) === "on"; } catch (e) { return false; } }
    function choose(on) { try { localStorage.setItem(LiveChoiceKey, on ? "on" : "off"); } catch (e) {} }
    // Only when it changes: the note is a live region, and repeating it would repeat the announcement.
    function say(text) { if (note.textContent !== text) note.textContent = text; }
    /* One refresh out at a time. A push that arrives while one is out asks for exactly one more
     * after it, rather than being dropped — the one out may have been answered before the change
     * the push reports — or starting a race with it. */
    let fetching = false, again = false;
    async function fetchNow() {
      lastFetch = Date.now();
      if (fetching) { again = true; return; }
      fetching = true;
      try {
        do { again = false; await settled(refresh()); } while (again);
      } finally { fetching = false; }
    }

    /* A refresh, or ten seconds, whichever is first. A fetch has no deadline of its own, and one
     * that never answers — a phone changing networks — must not hold every later refresh behind
     * it for good. If it does answer late, its ticket drops it under anything newer. */
    function settled(refreshing) {
      return Promise.race([refreshing, new Promise(resolve => setTimeout(resolve, 10000))]);
    }
    function wanted() { return !!liveUrl && toggle.checked && active(); }

    async function connect() {
      opening = true;
      say("connecting…");
      const url = new URL(liveUrl);
      url.searchParams.set("match", liveMatch);
      if (publicView) url.searchParams.set("board", "1");
      // The trusted local mode's `?as=`, which is who the player is there; deployed, the token is.
      const as = new URLSearchParams(location.search).get("as");
      if (as) url.searchParams.set("as", as);
      let token = null;
      try { token = await freshIdToken(); } catch (e) {}
      // In the url because a browser cannot put a header on a WebSocket. It is verified when the
      // connection opens and not again: what comes down it is only ever "something changed".
      if (token) url.searchParams.set("token", token);
      opening = false;
      if (!wanted()) { sync(); return; }

      const s = new WebSocket(url.toString());
      socket = s;
      s.onopen = () => {
        if (socket !== s) return;
        open = true;
        retries = 0;
        lastPing = Date.now();
        say("live");
        // Whatever changed while the connection was opening was pushed to nobody.
        fetchNow();
      };
      s.onmessage = () => { if (socket === s) fetchNow(); };
      // Also what follows a refused connection, which a browser does not tell apart from a failed one.
      s.onclose = () => {
        if (socket !== s) return;
        socket = null;
        open = false;
        if (wanted()) { say("reconnecting — checking every 2 seconds meanwhile"); retryLater(); } else say("");
      };
    }

    function retryLater() {
      const delay = Math.min(30000, 2000 * Math.pow(2, retries++));
      retryTimer = setTimeout(() => { retryTimer = null; sync(); }, delay);
    }

    /* Brings the connection into line with whether one is wanted: opened when Play Live is on and
     * there is something to wait for, and closed when it is turned off, the match ends, or the
     * player signs out. */
    function sync() {
      if (wanted()) {
        if (!socket && !opening && !retryTimer) connect();
        return;
      }
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
      retries = 0;
      if (socket) { const s = socket; socket = null; open = false; s.close(); }
      say("");
    }

    sync();
    setInterval(() => {
      sync();
      if (!active()) return;
      // The gateway closes a connection idle for ten minutes; this keeps a quiet match's open.
      if (open && Date.now() - lastPing >= 300000) {
        lastPing = Date.now();
        try { socket.send(JSON.stringify({ action: "ping" })); } catch (e) {}
      }
      // Every tick without a connection, as the page always polled; once a minute with one.
      if (Date.now() - lastFetch >= (open ? 60000 : 2000) - 100) fetchNow();
    }, 2000);
  }
"""
}

object PlayLive {

    /** The switch, hidden until the script finds the engine offers Play Live. */
    val markup: String =
        """<div id="live" hidden>
    <label for="live-toggle"><input type="checkbox" id="live-toggle" aria-describedby="live-hint"> Play Live</label>
    <span id="live-status" role="status" aria-live="polite"></span>
    <p id="live-hint">See every move the moment it is made, instead of every few seconds.</p>
  </div>"""

    /** The styles of the switch, for a page to put among its own. */
    val css: String =
        """  #live { margin: 1rem auto 0; display: flex; flex-wrap: wrap; align-items: center; justify-content: center;
          gap: 0 .75rem; font-size: .875rem; }
  #live[hidden] { display: none; }
  /* The label is the target, so it is the one held to 44px; the box inside it is drawn larger
     than a browser's default so that it reads as the control it is. */
  #live label { display: inline-flex; align-items: center; gap: .5rem; min-height: 44px; padding: 0 .5rem;
                font-size: 1rem; cursor: pointer; }
  #live input { width: 1.25rem; height: 1.25rem; margin: 0; accent-color: seagreen; cursor: pointer; }
  #live input:focus-visible { outline: 3px solid seagreen; outline-offset: 2px; }
  #live-status:empty { display: none; }
  #live-hint { flex-basis: 100%; margin: 0; text-align: center; opacity: .75; }"""
}
