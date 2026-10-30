/* Accessibility and mobile check of matchmaker's UI and the tic-tac-toe engine's play pages.
 *
 * Matchmaker and the engine run from their modules' classes on ports of their own, against a database made for the run
 * and dropped after it, and a fixture is built over HTTP: two players, a game, an open challenge, a match being
 * played, a finished one and an invitation. Each screen is then opened in headless Chromium twice -- at 375px with
 * touch, as a phone, and at desktop width -- and checked:
 *
 *   - by axe-core, against the WCAG 2.2 A and AA rules;
 *   - on the phone only, by the checks axe does not make: no page-wide horizontal scroll, form fields of at least
 *     16px (smaller ones make iOS zoom on focus), and touch targets of at least 44px.
 *
 *     ./a11y-check.sh
 *
 * which installs the npm packages on first use, links the UI with fastLinkJS and runs this. UI_BUNDLE=<dir> checks a
 * different linked UI, such as fullLinkJS's.
 *
 * The run asks mill for the classpaths, compiling what needs it, so it waits on a mill holding the build lock (a
 * running `runMain`).
 * Needs the local Postgres the tests use, whose `matchmaker` user may create databases. Ports 18081, 18091, 18191
 * and 15173 must be free. Chromium is the one `playwright-core` installs (`npx playwright-core install chromium`),
 * or CHROME=<path>. Logs, screenshots and report.json go to e2e/out/a11y/.
 *
 * What it cannot judge -- whether focus lands somewhere sensible after a screen change, whether a live region is
 * announced, whether a label makes sense -- is the ui-reviewer agent's (.claude/agents/ui-reviewer.md).
 *
 * Exits 1 if anything was found, or a screen could not be reached.
 */
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdirSync, createWriteStream, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import { chromium } from "playwright-core";
import AxeBuilder from "@axe-core/playwright";

const REPO = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const OUT = join(REPO, "e2e", "out", "a11y");
const MM = "http://localhost:18081";
const ENGINE = "http://localhost:18091";
const UI_PORT = 15173;
const UI = `http://localhost:${UI_PORT}`;
const RUN = randomBytes(4).toString("hex");
const DB = `matchmaker_a11y_${RUN}`;
const EXTERNAL_ID = `a11y-${RUN}`;
// The linked UI to serve: fastLinkJS's unless told otherwise.
const UI_BUNDLE = process.env.UI_BUNDLE ?? join(REPO, "out", "matchmaker", "ui", "fastLinkJS.dest");

const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];
const PHONE = { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 };
const DESKTOP = { viewport: { width: 1280, height: 800 } };

const procs = [];

function start(name, cmd, args, env) {
    const log = createWriteStream(join(OUT, `${name}.log`));
    const p = spawn(cmd, args, { env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] });
    p.stdout.pipe(log);
    p.stderr.pipe(log);
    procs.push(p);
    return p;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function waitFor(url, what) {
    for (let i = 0; i < 240; i++) {
        try {
            await fetch(url);
            return;
        } catch {
            await sleep(500);
        }
    }
    throw new Error(`${what} did not come up`);
}

async function call(method, url, body, sub) {
    const headers = { "content-type": "application/json" };
    if (sub) headers["x-external-id"] = sub;
    const r = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let json = text;
    try {
        json = text ? JSON.parse(text) : null;
    } catch {}
    if (r.status >= 300) throw new Error(`${method} ${url}: ${r.status} ${text.slice(0, 300)}`);
    return json;
}

async function eventually(read, what, tries = 120) {
    for (let i = 0; i < tries; i++) {
        const found = await read();
        if (found != null) return found;
        await sleep(250);
    }
    throw new Error(`${what} did not happen`);
}

function psql(sql, db = DB) {
    return execFileSync("psql", ["-X", "-h", "localhost", "-U", "matchmaker", db, "-At", "-c", sql], {
        env: { ...process.env, PGPASSWORD: "matchmaker" },
        encoding: "utf8"
    }).trim();
}

/* A module's runtime classpath, as mill reports it, compiled if it needs to be. The jars are run from their modules'
 * classes rather than from assemblies: flyway's assembly drops the service file that registers its Postgres support,
 * and the others are what a deploy uploads, which a check has no business rebuilding. */
function classpath(module) {
    const refs = JSON.parse(
        execFileSync("mill", ["-j", "4", "--ticker", "false", "show", `${module}.runClasspath`], {
            cwd: REPO,
            encoding: "utf8",
            stdio: ["ignore", "pipe", "ignore"]
        })
    );
    return refs.map(ref => ref.replace(/^q?ref:v\d+:[0-9a-f]+:/, "")).join(":");
}

/* The UI as `python3 -m http.server` would serve it, with two differences: main.js is read from mill's output rather
 * than through the symlink a developer makes, and config.js is not served at all -- each browser context answers it
 * itself, with the player it is signed in as. Nothing in matchmaker/ui is written to. */
function serveUi() {
    const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".map": "application/json" };
    const server = createServer((req, res) => {
        const path = new URL(req.url, UI).pathname;
        const name = path === "/" ? "index.html" : path.slice(1);
        const file = name.startsWith("main.js") ? join(UI_BUNDLE, name) : join(REPO, "matchmaker", "ui", name);
        if (name.includes("..") || !existsSync(file)) {
            res.writeHead(404).end();
            return;
        }
        res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
        res.end(readFileSync(file));
    });
    server.listen(UI_PORT, "127.0.0.1");
    return server;
}

/* Resolves once nothing has been asked of the network for `quiet` ms: the screen has drawn what it fetched. The
 * engine's play page polls every two seconds, which leaves a quiet window between asks. */
function quietTracker(page) {
    let inFlight = 0;
    let last = Date.now();
    const bump = d => () => {
        inFlight += d;
        last = Date.now();
    };
    page.on("request", bump(1));
    page.on("requestfinished", bump(-1));
    page.on("requestfailed", bump(-1));
    return async (quiet = 750, limit = 20000) => {
        const until = Date.now() + limit;
        while (Date.now() < until) {
            if (inFlight <= 0 && Date.now() - last >= quiet) return;
            await sleep(100);
        }
    };
}

/* What axe does not check: the phone-only rules from .claude/rules/mobile-and-accessible.md. */
function mobileChecks() {
    const found = [];
    const describe = el => {
        const text = (el.getAttribute("aria-label") || el.innerText || el.value || "").trim().replace(/\s+/g, " ");
        const cls = typeof el.className === "string" && el.className.trim() ? "." + el.className.trim().split(/\s+/).join(".") : "";
        return `${el.tagName.toLowerCase()}${el.id ? "#" + el.id : ""}${cls}${text ? ` "${text.slice(0, 40)}"` : ""}`;
    };
    const shown = el => {
        const r = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none" && !el.closest("[inert]");
    };

    // The sizes below are what the stylesheet gives a coarse pointer; measured without one, every target fails.
    if (!matchMedia("(pointer: coarse)").matches)
        return [{ rule: "emulation", target: "html", detail: "the page does not see a coarse pointer, so its touch sizes are not in effect" }];

    const root = document.documentElement;
    if (root.scrollWidth > root.clientWidth + 1)
        found.push({ rule: "horizontal-scroll", target: "html", detail: `the page is ${root.scrollWidth}px wide in a ${root.clientWidth}px viewport` });

    const fields = "input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]):not([type=reset]), select, textarea";
    for (const el of document.querySelectorAll(fields)) {
        if (!shown(el)) continue;
        const size = parseFloat(getComputedStyle(el).fontSize);
        if (size < 16) found.push({ rule: "field-font-size", target: describe(el), detail: `${size}px; iOS zooms on focus below 16px` });
    }

    const targets = "button, [role=button], select, summary, input:not([type=hidden])";
    for (const el of document.querySelectorAll(targets)) {
        if (!shown(el)) continue;
        // A checkbox or radio inside its label is tapped through the label.
        const hit = (el.type === "checkbox" || el.type === "radio") && el.closest("label") ? el.closest("label") : el;
        const r = hit.getBoundingClientRect();
        if (r.width < 43.5 || r.height < 43.5)
            found.push({ rule: "target-size-44", target: describe(hit), detail: `${Math.round(r.width)}x${Math.round(r.height)}px` });
    }
    return found;
}

const report = [];
// Screens with findings, and what stopped a screen or the run from being checked at all.
const failures = [];
const broken = [];

async function check(context, name, open, phone) {
    const page = await context.newPage();
    const settle = quietTracker(page);
    const label = `${name} [${phone ? "phone" : "desktop"}]`;
    try {
        await open(page, settle);
        await settle();
    } catch (e) {
        broken.push(`${label}: could not be reached: ${e.message.split("\n")[0]}`);
        await shoot(page, label, phone).catch(() => {});
        await page.close();
        return;
    }
    const axe = await new AxeBuilder({ page }).withTags(WCAG).analyze();
    const found = axe.violations.map(v => ({
        rule: v.id,
        impact: v.impact,
        help: v.help,
        url: v.helpUrl,
        // With axe's own reason for each element, which for contrast is the ratio it measured.
        targets: v.nodes.map(n => {
            const why = [...n.any, ...n.all, ...n.none].map(c => c.message).find(Boolean);
            return why ? `${n.target.join(" ")} — ${why}` : n.target.join(" ");
        })
    }));
    if (phone) {
        const byRule = {};
        for (const f of await page.evaluate(mobileChecks)) (byRule[f.rule] ??= []).push(`${f.target} — ${f.detail}`);
        for (const [rule, targets] of Object.entries(byRule)) found.push({ rule, impact: "mobile", targets });
    }
    await shoot(page, label, phone);
    report.push({ screen: label, found });
    console.log(found.length === 0 ? `  ok   ${label}` : `  FAIL ${label}`);
    for (const f of found) {
        console.log(`         ${f.rule} (${f.impact}): ${f.help ?? ""} — ${f.targets.length} element(s)`);
        for (const t of f.targets.slice(0, 5)) console.log(`           ${t}`);
        if (f.targets.length > 5) console.log(`           … and ${f.targets.length - 5} more`);
    }
    if (found.length) failures.push(label);
    await page.close();
}

/* A picture of the whole page. Last, after the measurements: a full-page shot of a page taller than the viewport
 * changes how the page is emulated while it takes one, and on the phone the coarse pointer does not survive that --
 * the picture would show the page without its touch sizes. So the phone's viewport is made as tall as the page
 * instead, which keeps the emulation, and the shot is of the viewport. */
async function shoot(page, label, phone) {
    const path = join(OUT, `${slug(label)}.png`);
    if (!phone) return page.screenshot({ path, fullPage: true });
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: PHONE.viewport.width, height: Math.min(height, 16000) });
    await page.screenshot({ path });
}

const slug = s => s.replace(/[^a-z0-9]+/gi, "-").replace(/^-|-$/g, "").toLowerCase();

async function signedInAs(browser, sub, device) {
    const context = await browser.newContext(device);
    await context.route(`${UI}/config.js`, route =>
        route.fulfill({
            contentType: "text/javascript",
            body: `window.matchmakerConfig = ${JSON.stringify({ authMode: "header", localExternalId: sub, apiEndpoint: MM })};`
        })
    );
    return context;
}

let uiServer;
let created = false;
try {
    mkdirSync(OUT, { recursive: true });
    if (!existsSync(join(UI_BUNDLE, "main.js"))) throw new Error(`no main.js in ${UI_BUNDLE}: build matchmaker.ui.fastLinkJS first`);

    psql(`CREATE DATABASE ${DB}`, "matchmaker");
    created = true;
    const [flyway, api, engine] = ["matchmaker.flyway", "matchmaker.api", "engines.tictactoe"].map(classpath);
    execFileSync("java", ["-cp", flyway, "com.vivi.matchmaker.flyway.Migrate"], {
        env: {
            ...process.env,
            FLYWAY_URL: `jdbc:postgresql://localhost:5432/${DB}`,
            FLYWAY_USER: "matchmaker",
            FLYWAY_PASSWORD: "matchmaker",
            MIGRATION_DIR: join(REPO, "matchmaker/resources/db/migration")
        },
        stdio: ["ignore", "ignore", "inherit"]
    });

    start("matchmaker", "java", ["-cp", api, "com.vivi.matchmaker.api.LocalServer"], {
        PORT: "18081",
        DB_NAME: DB,
        LOCAL_CORS_ORIGINS: UI,
        MATCHMAKER_BASE_URL: MM,
        ARCHIVE_DIR: join(OUT, "archive")
    });
    start("engine", "java", ["-cp", engine, "com.vivi.tictactoe.LocalServer"], {
        PORT: "18091",
        LIVE_PORT: "18191",
        GAME_EXTERNAL_ID: EXTERNAL_ID,
        MATCHMAKER_URL: MM
    });
    uiServer = serveUi();
    await waitFor(`${MM}/games`, "matchmaker");
    await waitFor(`${ENGINE}/health`, "engine");

    // The fixture. The game as register-game.sql makes it; the players over the API, the first an admin.
    const gameId = psql(`WITH game AS (
        INSERT INTO game (game_type, name, display_name, description, url, is_active, external_id)
        VALUES ('P', 'tictactoe', 'Tic-Tac-Toe', 'Three in a row.', '${ENGINE}/games', true, '${EXTERNAL_ID}') RETURNING game_id)
      INSERT INTO game_role (game_id, name, display_name)
      SELECT game_id, role, role FROM game, (VALUES ('X'), ('O')) AS roles(role) RETURNING game_id`).split("\n")[0];
    const roles = Object.fromEntries(
        psql(`SELECT name, game_role_id FROM game_role WHERE game_id = ${gameId}`).split("\n").map(l => l.split("|"))
    );
    const alice = `sub-alice-${RUN}`;
    const bob = `sub-bob-${RUN}`;
    const newcomer = `sub-new-${RUN}`;
    await call("POST", `${MM}/register`, { nickname: "alice" }, alice);
    await call("POST", `${MM}/register`, { nickname: "bob" }, bob);
    const aliceId = (await call("GET", `${MM}/me`, undefined, alice)).playerId;
    const bobId = (await call("GET", `${MM}/me`, undefined, bob)).playerId;
    psql(`UPDATE player SET is_admin = true WHERE player_id = ${aliceId}`);
    await call("POST", `${MM}/tournament-models/sync`, {}, alice);
    await call("PUT", `${MM}/games/${gameId}/tournament-models/RR`, {}, alice);

    // A one-match competition of the round robin format, as the Issue Challenge or Create Tournament dialog makes one: created with its
    // owner entered, and its invitations sent.
    async function challenge({ poolSize, isAutoStarted = false, isOpen, isPublic = false, invitations = [] }, sub = alice) {
        const t = (await call("POST", `${MM}/games/${gameId}/tournaments`, {
            name: "A challenge", tournamentModelId: "RR", defaultPoolSize: poolSize,
            settings: { isInvitational: !isOpen, isAutoStarted, isPublic, maxEntrants: poolSize },
            entry: {},
        }, sub)).tournamentId;
        for (const playerId of invitations)
            await call("POST", `${MM}/games/${gameId}/tournaments/${t}/invitations`, { playerId }, sub);
        return t;
    }
    async function newMatch(isPublic = false) {
        const t = await challenge({ poolSize: 2, isAutoStarted: true, isOpen: false, isPublic, invitations: [bobId] });
        await call("POST", `${MM}/games/${gameId}/tournaments/${t}/entries`, {}, bob);
        const matchId = await eventually(async () => {
            const d = await call("GET", `${MM}/games/${gameId}/tournaments/${t}`, undefined, alice);
            return d.round?.length ? d.round[0].matchId : null;
        }, "the challenge's round");
        await call("POST", `${MM}/games/${gameId}/tournaments/${t}/matches/${matchId}/role`, { gameRoleId: Number(roles.X) }, alice);
        await eventually(async () => {
            const active = await call("GET", `${MM}/me/matches`, undefined, alice);
            return active.some(m => m.matchId === matchId) ? matchId : null;
        }, "the match in the player's list");
        // Listed is not yet created: the engine's create arrives a moment after matchmaker lists the match, and a move
        // sent before it is answered "there is no such match". The engine here runs with no matchmaker key, so its
        // status route is open.
        await eventually(async () => {
            const r = await fetch(`${ENGINE}/matches/${matchId}/status`);
            await r.text();
            return r.ok ? matchId : null;
        }, "the engine's game");
        return matchId;
    }
    // Public, so that its board can be checked too.
    const playing = await newMatch(true);
    await call("POST", `${ENGINE}/matches/${playing}/moves?as=${alice}`, { cell: 4 });
    const finished = await newMatch();
    for (const [sub, cell] of [[alice, 0], [bob, 3], [alice, 1], [bob, 4], [alice, 2]])
        await call("POST", `${ENGINE}/matches/${finished}/moves?as=${sub}`, { cell });
    const open = await challenge({ poolSize: 2, isOpen: true });
    const openName = (await call("GET", `${MM}/games/${gameId}/tournaments/${open}`, undefined, alice)).summary.tournament.name;
    await challenge({ poolSize: 2, isOpen: false, invitations: [bobId] });

    // The screens. Each `open` takes the page to its screen; the menu is the only navigation the UI has.
    // An engine page that answers an error is not the page meant, and is not checked as if it were.
    const visit = url => async page => {
        const response = await page.goto(url);
        if (!response.ok()) throw new Error(`${url} answered ${response.status()}`);
    };
    const home = async (page, settle) => {
        await page.goto(UI);
        await page.getByRole("button", { name: "Main page" }).waitFor();
        await settle();
    };
    const menu = name => async (page, settle) => {
        await home(page, settle);
        await page.locator(".menu").getByRole("button", { name, exact: true }).click();
        await settle();
    };
    const game = menu("Tic-Tac-Toe");
    const screens = [
        ["registration", newcomer, async page => {
            await page.goto(UI);
            await page.getByRole("heading", { name: "Choose a Nickname" }).waitFor();
        }],
        ["main page", alice, home],
        ["main page with an invitation", bob, home],
        ["account panel", alice, async (page, settle) => {
            await home(page, settle);
            await page.getByRole("button", { name: "Account" }).click();
        }],
        ["find players", alice, async (page, settle) => {
            await menu("Find Players")(page, settle);
            await page.getByRole("searchbox").fill("bo");
            await page.getByRole("button", { name: "Search" }).click();
            await settle();
        }],
        ["a player's page", alice, async (page, settle) => {
            await menu("Find Players")(page, settle);
            await page.getByRole("searchbox").fill("bo");
            await page.getByRole("button", { name: "Search" }).click();
            await settle();
            await page.getByRole("button", { name: "bob", exact: true }).click();
            await settle();
        }],
        ["game page", alice, game],
        ["tournament page", alice, async (page, settle) => {
            await game(page, settle);
            await page.getByRole("button", { name: openName, exact: true }).first().click();
            await settle();
        }],
        ["new competition dialog", alice, async (page, settle) => {
            await game(page, settle);
            await page.getByRole("button", { name: "Issue Challenge or Create Tournament" }).click();
            await page.getByRole("dialog").waitFor();
        }],
        ["add a game form", alice, menu("Add a Game")],
        ["engine: match being played", null, visit(`${ENGINE}/matches/${playing}/play?as=${alice}`)],
        ["engine: finished match", null, visit(`${ENGINE}/matches/${finished}/play?as=${alice}`)],
        ["engine: public board", null, visit(`${ENGINE}/matches/${playing}/board`)]
    ];

    const browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});
    for (const [device, phone] of [[PHONE, true], [DESKTOP, false]]) {
        console.log(phone ? "At 375px, with touch" : "At 1280px");
        for (const [name, sub, open] of screens) {
            const context = sub ? await signedInAs(browser, sub, device) : await browser.newContext(device);
            await check(context, name, open, phone);
            await context.close();
        }
    }
    await browser.close();
} catch (e) {
    broken.push(`the run itself: ${e.message}`);
} finally {
    for (const p of procs) p.kill();
    uiServer?.close();
    await sleep(1000);
    if (created) {
        try {
            psql(`DROP DATABASE ${DB} WITH (FORCE)`, "matchmaker");
        } catch (e) {
            console.log(`could not drop ${DB}: ${e.message}`);
        }
    }
}

writeFileSync(join(OUT, "report.json"), JSON.stringify(report, null, 2));
console.log();
const found = report.reduce((n, r) => n + r.found.length, 0);
if (failures.length === 0 && broken.length === 0) console.log(`all ${report.length} screens passed`);
else if (failures.length) console.log(`${found} finding(s) on ${failures.length} of ${report.length} screens; report in e2e/out/a11y/`);
for (const b of broken) console.log(`  ${b}`);
process.exit(failures.length || broken.length ? 1 : 0);
