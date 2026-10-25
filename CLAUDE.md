# Working in this repo

Conventions that are not visible from the code alone. 

## Build and test

Run tests with `./test.sh`: `./test.sh matchmaker.test engines.boxing.test`, or
`./test.sh matchmaker.test.testOnly com.vivi.matchmaker.service.TournamentServiceSpec`. It points `FLYWAY_URL` at
local Postgres, runs without the daemon, joins the targets with `+`, and prints a count per module, each failure, any
stray trace, and compile errors. It fails a run that ran no tests or left a stray trace. The full log is in
`out/test-logs/`, its path printed last; read it only when the summary is not enough.

For anything else, always pass both flags: `mill -j 4 --ticker false <target>`. The ticker's progress redraws make
the output unreadable when it is being piped or read back.

Several targets are joined with `+`: `mill -j 4 --ticker false matchmaker.test + engines.boxing.test`.
Without it mill passes the later targets as arguments to the first, which for a test task filters the
run down to nothing and still exits 0.

Test suites talk to a real local Postgres — user, database and password all `matchmaker` — and
migrate it on first use via `TestMigration.ensure()`. There is no in-memory substitute; a failure
to connect means the local server is not up, not that the code is wrong.

`flyway-dev.sh` runs mill with `FLYWAY_URL` pointing at the deployed RDS database, and a mill daemon keeps that
environment. `matchmaker.test`'s `migrated` task runs `Migrate` with the daemon's environment, so a plain test run
afterwards can try to migrate RDS instead of local Postgres. `./test.sh` avoids this; a plain mill run of the DB
tests is written `FLYWAY_URL=jdbc:postgresql://localhost:5432/matchmaker mill --no-daemon -j 4 --ticker false …`, and
if in doubt check the log for `rds.amazonaws`.

A test that fails something on purpose mixes in `QuietTests` and is written `test("...".tag(Quiet))`. There are two
copies of the trait: `com.vivi.engine.QuietTests` in `engines/common/testkit`, and `com.vivi.matchmaker.QuietTests`
for matchmaker. Before calling a run green, its output must have no lines starting `at ` and no `ERROR handling`
outside a failure. `./test.sh` checks this and lists any it finds; after a plain mill run, grep for them.

To reproduce a UI or engine bug in a browser — the local stack, the mill lock, Playwright — see
`.claude/local-browser-testing.md`.

## Test timeouts are ceilings, not budgets

A DB-backed property spec builds its whole fixture per case with four test workers running at once on a JVM
that may be cold. Several seconds per case is ordinary, and the first run after a full recompile
is slower.

The per-case `.timeout(...)` in these specs exists to cap a case that has *hung*. It is
deliberately generous, and tightening one to "how long this ought to take" converts an ordinary
slow run into a flaky failure. `MatchStartedNotificationSpec.caseTimeout` is the worked example.

## Formatting

`.scalafmt.conf` is committed and is the only authority on layout.
Run `scalafmt` rather than matching surrounding style by eye — running
it with the defaults instead of this config reformats the entire
repository. Never pass `build.mill` to it: it reformats the whole file.

## Arguments are passed by name

A call to a method or constructor with three or more parameters passes its arguments by name —
`register(nickname = n, externalId = id, email = e)`, not `register(n, id, e)`. This is a strong
preference: a positional call to a wide signature hides which value goes where, and two parameters
of the same type can be swapped without the compiler noticing. Case-class construction counts.

With two parameters, naming is preferred but not required: leave the call positional where the
meaning is plain from the arguments, as in `max(a, b)` or a key and its value.

The exceptions are calls the language or the callee rules out. A placeholder (`f(a, b, _)`) cannot be
named, so a call that uses one stays positional. A varargs list stays positional. A function value has
no parameter names. And methods from libraries and Java are not ours to name, since their
parameter names can change underneath us.

## Two features are called "live"

- **Play Live** is the websocket push of "match changed" to the play pages: `Live`, `PlayLive` and `LocalLiveServer`
  in `engines.common`.
- **A live match** is one whose turns and clock the engine runs: `isLive` on a tournament and its matches.
  Matchmaker sends `CreateGameRequest.live = LiveTerms(timeLimitSeconds, kind)`, `kind` being `PER_TURN` or `TOTAL`
  (a chess clock). The engine keeps a `TurnClock`, sends no move callbacks and ends a timed-out match by forfeit,
  and the play pages count down with `TurnTimer`. A player's clock starts only once they have opened the board
  (`TurnClock.opened`), so a no-show stalls the match — that is intended. Matchmaker never marks a live match's
  seats pending or due, and `enforceTimeouts` skips it.

Name new code after the one it means: push, or clock and turns.

## No internal ids in anything a person reads

An internal id is never text a user can see: not a match uuid, a numeric player, game, challenge or character id, a
Cognito subject or a participant id. That covers matchmaker's UI, engine pages (their `<title>` included), emails,
result lines and user-facing error messages. Use the human name — the game's name, the match or challenge message, a
nickname or character name — and where there is none, a plain phrase ("an unnamed match"), never the id. Ids in URLs,
log lines and API paths are fine. Fix an id fallback such as `s"match ${matchId.value}"` when you come across one.

The text of an error or warning a person can read lives in `matchmaker/errors.properties` (generated into
`com.vivi.matchmaker.ErrorText`, for the server and the UI) or, for the engines, `engines/common/errors.properties`
(`com.vivi.engine.ErrorText`), not in the code. A new one adds a key there; each file's header gives its rules.

## Boolean names

The is/has/can rule was applied across the code in PR #152; `design/boolean-names.md` has the rewordings of the
awkward ones (`canTie`, `hasRoleChoice`, `is_notified_of_*`, …). `NotificationLevels.unsaid` is left alone on
purpose, and `NotificationType.column` derives `is_notified_of_<code>`.

## Rules for one part of the code

Back-end, UI and engine conventions live in `.claude/rules/backend.md`, `.claude/rules/ui.md`,
`.claude/rules/mobile-and-accessible.md` (the app and the engines' pages) and `.claude/rules/engines.md`,
and load only when a file they cover is being worked on.
