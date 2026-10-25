# Local browser testing

How to reproduce a UI or engine bug locally. Read it when a bug only shows in a browser.

- **Stack:** API `DB_NAME=… mill --no-daemon … matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer` (:8080); UI `mill … matchmaker.ui.fastLinkJS`, `cp matchmaker/ui/config.local.js matchmaker/ui/config.js` (gitignored — delete after), `python3 -m http.server 5173 --directory matchmaker/ui`; identity is `local-dev-1`. Engines: `MATCHMAKER_OFFLINE=true … engines.stratego.runMain com.vivi.stratego.LocalServer` (:8093, `?as=`), in-memory — a restart forgets matches.
- **Mill lock:** a running `runMain` holds the build lock, so a second mill command (tests, another server) waits forever. Run the second server with `java -cp "$(mill show <module>.runClasspath …)"`, or stop servers before running tests.
- **Shared DB noise:** the local `matchmaker` DB is the test suite's; generated games make the menu take seconds to draw and look like a hang. For a clean repro use `create database X template matchmaker` and prune it, then drop it. (Games were wiped 2026-10-03 but tests re-add them.)
- **Playwright:** headless Chrome at `~/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome`; install `playwright-core` in a scratch directory. If the page's main thread is busy, Playwright calls hang — log with `fs.appendFileSync` (stdout is lost on kill) and probe responsiveness with a timed `evaluate`. A CDP CPU profile (`Profiler.start/stop`) beats `Debugger.pause` for finding where time goes.
- **ETag revalidation:** DevTools/Playwright report a 304 revalidation as 200; confirm with `curl -H 'If-None-Match: …'`.
- Players created by local mode land in the shared DB (`local dev one`, id 77516).
- **Full engine loop (worked 2026-10-14):** start the API with `MATCHMAKER_BASE_URL=http://localhost:8080` or matchmaker sends the engine no callback urls and no moves or results ever come back (silently). The template DB can have no tournament models: `POST /tournament-models/sync` as an admin installs them, then offer 'RR' via `tournament_model_game`. A one-match competition: `POST /games/{g}/tournaments {"name":…,"tournamentModelId":"RR","defaultPoolSize":2,"settings":{"isAutoStarted":true,"maxEntrants":2}}`, then `POST /games/{g}/tournaments/{t}/open` and `…/entries` for each player, header `X-External-Id`.

