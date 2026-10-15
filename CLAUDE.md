# Working in this repo

Conventions that are not visible from the code alone. 

## Build and test

Always pass both flags: `mill -j 4 --ticker false <target>`. The ticker's progress redraws make
the output unreadable when it is being piped or read back.

Several targets are joined with `+`: `mill -j 4 --ticker false matchmaker.test + engines.boxing.test`.
Without it mill passes the later targets as arguments to the first, which for a test task filters the
run down to nothing and still exits 0.

Test suites talk to a real local Postgres — user, database and password all `matchmaker` — and
migrate it on first use via `TestMigration.ensure()`. There is no in-memory substitute; a failure
to connect means the local server is not up, not that the code is wrong.

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
repository.

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

## Rules for one part of the code

Back-end and UI conventions live in `.claude/rules/backend.md`, `.claude/rules/ui.md` and
`.claude/rules/mobile-and-accessible.md` (the app and the engines' pages), and load only when a file
they cover is being worked on.
