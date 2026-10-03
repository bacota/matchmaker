package com.vivi.engine

/** The play page's half of the message boards — see [[Messages]] — which every game's page lays out the same way: the
  * players' board on the left of the game and the observers' on the right, and on a narrow screen both under it.
  *
  * A page wraps its `<main>` in [[layoutStart]] and [[layoutEnd]], puts [[css]] among its styles, includes [[script]]
  * after the sign-in's, and refreshes the boards with its own state, which with Play Live means when anything changes:
  *
  * {{{
  * keepCurrent(() => Promise.all([refresh(), refreshMessages()]), ...);
  * }}}
  *
  * The public board offers its sign-in only to a watcher who asks to write, which is what `mbWantsSignIn()` tells the
  * page's own render.
  *
  * Messages are drawn as text, never as markup, so nothing anybody writes can run on anybody else's page. New messages
  * are appended rather than the list redrawn, since the list is a live region and a redraw would read the whole board
  * out again.
  */
object MessageBoards {

    private def panel(board: String, title: String, label: String): String =
        s"""<aside class="mb" id="mb-$board" aria-labelledby="mb-$board-title" hidden>
    <h2 id="mb-$board-title">$title</h2>
    <ol class="mb-list" id="mb-$board-list" role="log" aria-labelledby="mb-$board-title"></ol>
    <p class="mb-note" id="mb-$board-note" hidden></p>
    <form class="mb-form" id="mb-$board-form" hidden>
      <label for="mb-$board-text">$label</label>
      <textarea id="mb-$board-text" rows="2" aria-describedby="mb-$board-count"></textarea>
      <div class="mb-row">
        <span class="mb-count" id="mb-$board-count"></span>
        <button type="submit">Send</button>
      </div>
      <p class="mb-error" id="mb-$board-error" role="alert"></p>
    </form>
  </aside>"""

    /** Opens the three columns, with the players' board first; the page's `<main>` follows. */
    val layoutStart: String =
        s"""<div class="mb-layout">
  ${panel("players", "Players' messages", "Message to your opponent")}"""

    /** Closes the three columns, with the observers' board last. */
    val layoutEnd: String =
        s"""  ${panel("observers", "Observers' messages", "Message to the other observers")}
</div>"""

    /** The styles of the layout and the boards, for a page to put among its own. */
    val css: String =
        """  .mb-layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: 0 1.5rem; width: 100%; max-width: 96rem;
                box-sizing: border-box; align-items: start; }
  .mb-layout > main { order: 1; }
  #mb-players { order: 2; }
  #mb-observers { order: 3; }
  /* Wide enough for three columns: each board beside the game, at the height of the page's top. */
  @media (min-width: 1100px) {
    .mb-layout { grid-template-columns: minmax(16rem, 1fr) minmax(0, 40rem) minmax(16rem, 1fr); }
    .mb-layout > main, #mb-players, #mb-observers { order: 0; }
    #mb-players { grid-column: 1; }
    .mb-layout > main { grid-column: 2; }
    #mb-observers { grid-column: 3; }
    .mb { margin-top: 1.5rem; }
    .mb-list { max-height: 60vh; }
  }
  .mb[hidden] { display: none; }
  .mb { margin: 1rem 16px; padding: .75rem 1rem; border: 1px solid var(--line); border-radius: 8px; text-align: left;
        font-size: .9375rem; }
  .mb h2 { font-size: 1rem; margin: 0 0 .5rem; }
  .mb-list { list-style: none; margin: 0; padding: 0; max-height: 40vh; overflow-y: auto; }
  .mb-list li { padding: .375rem .5rem; border-radius: 6px; }
  .mb-list li + li { margin-top: .25rem; }
  .mb-list li.mine { background: color-mix(in srgb, var(--ink) 7%, transparent); }
  .mb-who { font-weight: 600; }
  .mb-when { font-size: .8125rem; opacity: .7; margin-left: .375rem; }
  .mb-text { margin: .125rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .mb-note { margin: .25rem 0 0; font-size: .875rem; opacity: .8; }
  .mb-note[hidden] { display: none; }
  .mb-form { margin-top: .75rem; }
  .mb-form[hidden] { display: none; }
  .mb-form label { display: block; font-size: .875rem; margin-bottom: .25rem; }
  /* 16px, or iOS zooms the page when the box is focused. */
  .mb-form textarea { display: block; width: 100%; box-sizing: border-box; font: inherit; font-size: 16px;
                      padding: .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--paper);
                      color: var(--ink); resize: vertical; }
  .mb-row { display: flex; justify-content: space-between; align-items: center; gap: .5rem; margin-top: .375rem; }
  .mb-count { font-size: .8125rem; opacity: .7; }
  .mb button { font: inherit; font-size: 1rem; min-height: 44px; min-width: 44px; padding: 0 1rem; border-radius: 6px;
               border: 1px solid var(--line); background: var(--ink); color: var(--paper); cursor: pointer; }
  .mb button.mb-signin { background: var(--paper); color: var(--ink); margin-top: .75rem; }
  .mb button[hidden] { display: none; }
  .mb :focus-visible { outline: 3px solid currentColor; outline-offset: 2px; }
  .mb-error { margin: .375rem 0 0; font-size: .875rem; color: var(--error, #b3261e); }
  .mb-error:empty { display: none; }"""

    /** The boards' script. Expects `publicView`, `render`, and the sign-in's `isSignedIn` and `freshIdToken`. */
    val script: String =
        """  /* ---- the message boards ---------------------------------------------------------------
   * Urls are this page's own, as the page's state's are. A watcher of the public board who is not
   * signed in reads the boards as anyone may; anybody else reads them as who they are. */
  const mbHere = location.pathname.replace(new RegExp("/(play|board)$"), "");
  const mbTrustedAs = !!new URLSearchParams(location.search).get("as");
  const MB_BOARDS = ["players", "observers"];
  let mbView = null, mbSignInWanted = false;
  const mbShown = { players: [], observers: [] };

  function mbAnonymous() { return publicView && !isSignedIn() && !mbTrustedAs; }
  function mbReadUrl() { return mbHere + (mbAnonymous() ? "/board/messages" : "/messages") + location.search; }

  /* Whether a watcher of the public board has asked to sign in, so that the page shows its sign-in. */
  function mbWantsSignIn() { return mbSignInWanted && !isSignedIn() && !mbTrustedAs; }

  async function mbHeaders() {
    const token = mbAnonymous() ? null : await freshIdToken();
    return token ? { authorization: "Bearer " + token } : {};
  }

  async function refreshMessages() {
    try {
      const response = await fetch(mbReadUrl(), { headers: await mbHeaders() });
      // Not signed in yet, no seat in a private match, an engine with no boards: nothing to show.
      mbView = response.ok ? await response.json() : null;
    } catch (e) {
      return; // unreachable for now; the next refresh tries again, and what is shown stays
    }
    mbDraw();
  }

  function mbKey(m) { return m.at + "\u0000" + m.name + "\u0000" + m.text + "\u0000" + String(m.mine); }

  function mbItem(m) {
    const li = document.createElement("li");
    if (m.mine) li.className = "mine";
    const who = document.createElement("span");
    who.className = "mb-who";
    who.textContent = m.mine ? m.name + " (you)" : m.name;
    const when = document.createElement("time");
    when.className = "mb-when";
    when.dateTime = m.at;
    when.textContent = new Date(m.at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    const text = document.createElement("p");
    text.className = "mb-text";
    text.textContent = m.text;
    li.append(who, when, text);
    return li;
  }

  /* Appends what is new; redraws only if what was shown is no longer the start of the board. */
  function mbList(board, messages) {
    const list = document.getElementById("mb-" + board + "-list");
    const shown = mbShown[board];
    const keys = messages.map(mbKey);
    if (shown.length > keys.length || shown.some((k, i) => k !== keys[i])) {
      list.textContent = "";
      shown.length = 0;
    }
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
    messages.slice(shown.length).forEach(m => { list.appendChild(mbItem(m)); shown.push(mbKey(m)); });
    if (nearBottom) list.scrollTop = list.scrollHeight;
  }

  function mbNote(board, text) {
    const note = document.getElementById("mb-" + board + "-note");
    note.hidden = !text;
    note.textContent = text || "";
  }

  function mbDraw() {
    MB_BOARDS.forEach(board => {
      const panel = document.getElementById("mb-" + board);
      const messages = mbView ? mbView[board] : null;
      // The observers' board belongs only to a public match.
      panel.hidden = !mbView || (board === "observers" ? !mbView.isPublic : messages === null);
      if (panel.hidden) return;
      const form = document.getElementById("mb-" + board + "-form");
      const writable = mbView.canWrite.includes(board);
      form.hidden = !writable;
      document.getElementById("mb-" + board + "-text").maxLength = mbView.maxLength;
      mbCount(board);
      if (messages === null) {
        mbList(board, []);
        mbNote(board, "What the observers say is shown here once the match is over.");
      } else {
        mbList(board, messages);
        mbNote(board, messages.length === 0 ? "No messages yet." : mbView.over ? "The match is over; this board is closed." : "");
      }
      let signIn = panel.querySelector(".mb-signin");
      const offer = board === "observers" && mbView.signInToWrite;
      if (offer && !signIn) {
        signIn = document.createElement("button");
        signIn.type = "button";
        signIn.className = "mb-signin";
        signIn.textContent = "Sign in to write";
        signIn.addEventListener("click", () => {
          mbSignInWanted = true;
          render();
          const form = document.getElementById("signin");
          if (form) { form.scrollIntoView({ block: "center" }); const first = form.querySelector("input"); if (first) first.focus(); }
        });
        panel.appendChild(signIn);
      }
      if (signIn) signIn.hidden = !offer;
    });
  }

  function mbCount(board) {
    const box = document.getElementById("mb-" + board + "-text");
    document.getElementById("mb-" + board + "-count").textContent =
      box.value.length + " of " + box.maxLength + " characters";
  }

  async function mbSend(board) {
    const box = document.getElementById("mb-" + board + "-text");
    const error = document.getElementById("mb-" + board + "-error");
    const text = box.value.trim();
    if (!text) return;
    error.textContent = "";
    try {
      const response = await fetch(mbHere + "/messages" + location.search, {
        method: "POST",
        headers: Object.assign({ "content-type": "application/json" }, await mbHeaders()),
        body: JSON.stringify({ board, text })
      });
      const answer = await response.json().catch(() => ({}));
      if (!response.ok) { error.textContent = answer.error || "the message was not sent"; return; }
      box.value = "";
      mbView = answer;
      mbDraw();
    } catch (e) {
      error.textContent = "could not reach the engine; the message was not sent";
    }
  }

  MB_BOARDS.forEach(board => {
    const form = document.getElementById("mb-" + board + "-form");
    const box = document.getElementById("mb-" + board + "-text");
    form.addEventListener("submit", e => { e.preventDefault(); mbSend(board); });
    box.addEventListener("input", () => mbCount(board));
    // Enter sends, as in any chat; Shift+Enter is a new line.
    box.addEventListener("keydown", e => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); mbSend(board); }
    });
  });
"""
}
