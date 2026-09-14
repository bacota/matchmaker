# Working in this repo

Conventions that are not visible from the code alone. Everything here is the kind of thing that
gets silently undone by someone who did not know it was a decision.

## Build and test

Always pass both flags: `mill -j 4 --ticker false <target>`. The ticker's progress redraws make
the output unreadable when it is being piped or read back.

The test suites talk to a real local Postgres — user, database and password all `matchmaker` — and
migrate it on first use via `TestMigration.ensure()`. There is no in-memory substitute; a failure
to connect means the local server is not up, not that the code is wrong.

## Test timeouts are ceilings, not budgets

A DB-backed property spec builds its whole fixture per case — register players, make a game,
create and accept a challenge, start a match — with four test workers running at once on a JVM
that may be cold. Several seconds per case is ordinary, and the first run after a full recompile
is slower still.

So the per-case `.timeout(...)` in these specs exists to cap a case that has *hung*. It is
deliberately generous, and tightening one to "how long this ought to take" converts an ordinary
slow run into a flaky failure. `MatchStartedNotificationSpec.caseTimeout` is the worked example.

## Formatting

`.scalafmt.conf` is committed and is the only authority on layout: `indent.main=4`,
`maxColumn=120`, dialect `scala3`. Run `scalafmt` rather than matching surrounding style by eye —
running it with the defaults instead of this config reformats the entire repository.

## A new API route is three changes

Adding a `case` to `Router.scala` is a third of an endpoint. The route must also be listed in
`local.routes` in `terraform/modules/api/main.tf` (or `local.engine_routes`, for the engine's own
callbacks), or the gateway returns 404 and the handler is never reached — the comment above the
match in `Router.scala` says the same thing at the point of the mistake. And it must be added to
`routed` in `RouterSpec`, whose count assertion fails until it is: that list is what proves the
route reaches a service and refuses an unauthenticated caller.

A route's request body is parsed by the shared `Json` codecs, so write the test body the way
upickle writes it — an `Option` field is the bare value, and absent when there is none, not a
one-element array.

## Reads that lead to a write take `FOR UPDATE`

Where a repo reads a row in order to decide whether to modify it, the read locks the row and the
decision is re-checked inside the lock — see `requireMatchForUpdate` and its counterparts in
`MatchRepo`, `GameRepo`, `CharacterRepo` and `OpenChallengeRepo`. This is for the tables that
actually contend; it is not a blanket rule for every select.

## UI work is mobile and accessible by default

Not a separate pass to be asked for: 16px form fields (smaller ones make iOS zoom on focus),
44px touch targets, a real label per control, live regions for anything that updates without a
reload, and visible focus. Assume every change will be used on a phone.
