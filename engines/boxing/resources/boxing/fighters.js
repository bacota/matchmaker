  const pageData = JSON.parse(document.getElementById("page-data").textContent);
  const rules = pageData.rules;
  // Derived from this page's own url, as the board's are: behind API Gateway the path carries a
  // stage prefix. The query goes along for the local `?as=`.
  const base = location.pathname.replace(new RegExp("/+$"), "");
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

  /* Each characteristic's explanation is a tip behind an ⓘ rather than a line under it. It still
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
      ' aria-controls="f-' + key + '-hint"><span class="tip-icon" aria-hidden="true">i</span></button>' +
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

  /* The change forms whose request is out, by their button's id, and how to bring each one's button
   * up to date. Here rather than in the form: `showMine` draws every form again when the list comes
   * back, and a form drawn while its own save was still out started not sending, so its button came
   * back enabled and a second press sent the change twice. */
  const sendingForms = new Set();
  const formRefreshers = new Map();

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
    // `disabled` for a fighter not worth sending yet; `aria-disabled` while one is on its way, since a
    // disabled button drops the keyboard's focus. The submit handler refuses a press meanwhile.
    const button = document.getElementById("build-submit");
    button.disabled = bad || nameInput.value.trim() === "";
    button.setAttribute("aria-disabled", String(building));
  }

  Object.values(inputs).forEach(i => i.addEventListener("input", left));
  nameInput.addEventListener("input", left);

  function show(message) { document.getElementById("error").textContent = message || ""; }

  /* Said after something the player did, and focused if the focus was lost with the list it is
   * about. Not otherwise: `showMine` gives the focus back to the field or button that had it, which
   * may be another fighter's form the player has gone on to type in. */
  function announce(message) {
    const status = document.getElementById("status");
    status.textContent = message;
    if (!document.activeElement || document.activeElement === document.body) status.focus();
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
      show(login ? errors.signIn : errors.sayWho);
      return null;
    }
    return response;
  }

  /* Only the latest load may draw -- an earlier one answering late is dropped, success or failure.
   *
   * The list stands while a load is out. A load follows every save, and the player may be typing in
   * another fighter's form meanwhile: emptying the list took that form, and the focus, away under
   * them. Another player's list is never left standing, because `forget` empties it when the session
   * ends. */
  async function loadMine() {
    const ticket = ++loads;
    const note = document.getElementById("yours-note");
    if (!document.getElementById("fighters").children.length) {
      note.textContent = "Loading your fighters…";
      note.hidden = false;
    }
    try {
      const response = await send(mineUrl, {});
      if (!response || ticket !== loads) return;
      const answer = await response.json().catch(() => ({}));
      if (ticket !== loads) return;
      if (!response.ok) { note.textContent = answer.error || response.statusText; return; }
      showMine(answer);
    } catch (err) {
      if (ticket === loads) note.textContent = errors.unreachable;
    }
  }

  /* Built from elements rather than markup: a name is whatever a player typed.
   *
   * Drawn afresh, and then given back what had been typed into the forms it replaces and the field
   * that had the focus, by the fields' ids: one fighter's save must not wipe an edit half made to
   * another. */
  function showMine(fighters) {
    const note = document.getElementById("yours-note");
    note.textContent = fighters.length ? "" : "You have no fighters yet. Build one below.";
    note.hidden = fighters.length > 0;
    const list = document.getElementById("fighters");
    const typed = {};
    list.querySelectorAll("input").forEach(i => { if (i.value !== i.defaultValue) typed[i.id] = i.value; });
    const focused = list.contains(document.activeElement) ? document.activeElement.id : null;
    list.replaceChildren(...fighters.map(fighterItem));
    Object.entries(typed).forEach(([id, value]) => {
      const input = document.getElementById(id);
      if (!input || !list.contains(input)) return;
      input.value = value;
      // So its form's button agrees with what is in the field.
      input.dispatchEvent(new Event("input"));
    });
    const refocus = focused && document.getElementById(focused);
    if (refocus && list.contains(refocus)) refocus.focus();
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
      unbuilt.textContent = errors.notBuilt;
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
    // The default as well as the value, which is how `showMine` tells what has been typed since.
    input.defaultValue = value;
    input.value = value;
    field.append(label, input);
    return { field, input };
  }

  /* A form that sends one change about a fighter. `ready` says whether what is typed is worth
   * sending, `request` makes the call, and `done` says what happened. Each is held in `sendingForms`
   * while its request is out, for the reason `building` is the build form's: editing a field, or
   * drawing the form again, must not re-enable a button whose request has not answered. */
  function changeForm(fields, buttonText, ready, request, done) {
    const form = document.createElement("form");
    form.noValidate = true;
    const button = document.createElement("button");
    button.type = "submit";
    button.className = "primary";
    button.textContent = buttonText;
    // Named after its first field, so that `showMine` can give it the focus back when it draws the list again.
    button.id = fields[0].input.id + "-submit";
    const key = button.id;
    // `disabled` while nothing worth sending is typed; `aria-disabled` while the change is on its way,
    // since a disabled button drops the keyboard's focus. The submit handler refuses a press meanwhile.
    const refresh = () => {
      button.disabled = !ready();
      button.setAttribute("aria-disabled", String(sendingForms.has(key)));
    };
    formRefreshers.set(key, refresh);
    fields.forEach(f => f.input.addEventListener("input", refresh));
    refresh();
    form.append(...fields.map(f => f.field), button);
    form.addEventListener("submit", async e => {
      e.preventDefault();
      if (sendingForms.has(key) || !ready()) return;
      sendingForms.add(key);
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
        if (asked === session) show(errors.unreachable);
      } finally {
        sendingForms.delete(key);
        // The form now on the page, which is a newer one than this if the list was drawn meanwhile.
        formRefreshers.get(key)();
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
    left();
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
      if (asked === session) show(errors.unreachable);
    } finally {
      building = false;
      left();
    }
  });

  reset();
  render();
  if (!login || isSignedIn()) loadMine();
