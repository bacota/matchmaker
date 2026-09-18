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

## An updating API call is one transaction, and its reads take `FOR UPDATE`

Every API call that updates the database wraps *all* of its database access in a single
`session.transaction.use` — not just the writes. A read taken outside the transaction that
decides what the write does is a read of state that may already be gone by the time it is used.

Within that transaction, any `SELECT` whose answer influences an update takes `FOR UPDATE` — see
`requireMatchForUpdate` and its counterparts in `MatchRepo`, `GameRepo`, `CharacterRepo` and
`OpenChallengeRepo`. Locking the row is not enough on its own: the decision has to be re-derived
from what was read under the lock. Two things are exempt, and only these two.

### The exception: a reference table read on the way to a write elsewhere

A small, slowly changing table read in order to write a large, fast-moving one is read plainly.
`game`, `game_role` and `game_parameter` are the cases: a start reads the game's roles to decide
what participants to write, `enforceTimeouts` reads its timeout action to decide how to end a
match, and neither locks it. The catalogue changes when an admin edits it, which is rarely and
never concurrently with the play it governs, and locking it on every move would serialize the
whole game's traffic behind one row for no race anybody has.

What this does not excuse is a read of such a table on the way to writing *it* —
`GameService.createOrUpdate` takes `lockForUpdate` on the game it is about to rewrite — or a read
of a contended table (`match`, `participant`, `open_challenge`, `acceptance`, `result`) merely
because the write lands on a different one. The exception is about the asymmetry: slow read, fast
write. Where an existence check has to outlive the insert that relies on it, `FOR SHARE` is the
middle course — `GameRepo.lockForShare` and `PlayerRepo.readForShare` exist for it, and
`CharacterService.create` is the worked example. It blocks a concurrent delete of the row without
making two inserts against it queue behind each other.

Say so at the read. An unlocked `SELECT` inside a transaction that writes looks like the mistake
this rule is about, so the exception is written down where it is being taken — the comments on
`requireGame` in `GameEngineService` and `OpenChallengeService` are that note.

### The exception: a call to an external service

A transaction is never held open across a request to something outside the database — the game
engine above all. A remote call can take as long as it likes, and a transaction waiting on one
holds its locks and a pooled connection for exactly that long.

So a call that writes both before and after an engine request is two transactions with a gap in
the middle, and **nothing read before the gap may be assumed still true after it**. Start a fresh
transaction for the second half and re-read, `FOR UPDATE`, anything the writes there depend on —
including the row the first half wrote, whose state somebody else may have changed while the
engine was answering. `GameEngineService.start` is the worked example: transaction A claims the
challenge, the engine creates its game, and transaction B re-reads under lock to finish.

The other half of that gap is that the second half cannot be undone by rolling back the first —
once the engine has made its game, the only way out is forward. Repairing rather than unwinding
(`refresh`) is the pattern for that.

## A response can outlive the session that asked for it

A fetch made while one player is signed in can be answered after they have signed out, or after
their token expired — `sessionExpired()` has already cleared everything by then, and the late
answer writes one player's data into a store the next player is about to read.

So `Store` counts sign-ins. Anything that holds onto an answer takes `currentSignIn` before the
request and checks `stillSignedInAs` before committing; `Store.load` and `Store.reload` do it for
you, and every fetch in `Store` goes through one of them. Use those rather than a bare
`onComplete` or `run` for anything that writes into the store — `run` is for a button, where the
answer is about a click somebody is waiting on, and it deliberately does not drop anything.

A screen that writes back into the store from its own request needs the same treatment, which is
why the two methods are `private[ui]` rather than private: `Account`'s rename is the one that
does. Guarding a `case Success(...)` with it means adding a `case Success(_) => ()` as well, or
the match throws for the case being guarded against.

Most of these staled harmlessly — the lists are re-fetched at the next sign-in — but
`loadNotifications` skips its fetch when something is already held, so a stale answer there is
permanent and the next player sees, and can save, somebody else's settings. That is the bug this
rule was written for.

## UI work is mobile and accessible by default

Not a separate pass to be asked for: 16px form fields (smaller ones make iOS zoom on focus),
44px touch targets, a real label per control, live regions for anything that updates without a
reload, and visible focus. Assume every change will be used on a phone.
