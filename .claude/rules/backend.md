---
paths:
  - "matchmaker/src/**"
  - "matchmaker/api/**"
  - "terraform/modules/api/**"
---

# Back end

## A new API route is three changes

Adding a `case` to `Router.scala` is a third of an endpoint. The route must also be listed in
`local.routes` in `terraform/modules/api/main.tf` (or `local.engine_routes`, for the engine's own
callbacks), or the gateway returns 404 and the handler is never reached — the comment above the
match in `Router.scala` says the same thing at the point of the mistake. And it must be added to
`routed` in `RouterSpec`, whose count assertion fails until it is: that list is what proves the
route reaches a service and refuses an unauthenticated caller.

A route missing from terraform fails silently: the Lambda never runs, so nothing reaches its log and
it looks as if the UI never made the call. To tell from outside, curl the path unauthenticated — a
listed route answers 401 from the authorizer, an unlisted one 404 from the gateway. A terraform-only
change still needs `deploy.sh <env>` to apply.

A route's request body is parsed by the shared `Json` codecs, so write the test body the way
upickle writes it — an `Option` field is the bare value, and absent when there is none, not a
one-element array.

## An updating API call is one transaction, and its reads take `FOR UPDATE`

Every API call that updates the database wraps *all* of its database access in a single
`session.transaction.use` — not just the writes. A read taken outside the transaction that
decides what the write does is a read of state that may already be gone by the time it is used.

Within that transaction, any `SELECT` whose answer influences an update takes `FOR UPDATE` — see
`requireMatchForUpdate` and its counterparts in `MatchRepo`, `GameRepo`, `CharacterRepo` and
`TournamentRepo`. Locking the row is not enough on its own: the decision has to be re-derived
from what was read under the lock. Two things are exempt, and only these two.

### The exception: a reference table read on the way to a write elsewhere

A small, slowly changing table read in order to write a large, fast-moving one is read plainly.
`game`, `game_role` and `game_parameter` are the cases: a start reads the game's roles to decide
what participants to write, `enforceTimeouts` reads its timeout action to decide how to end a
match, and neither locks it. The catalogue changes when an admin edits it, which is rarely and
never concurrently with the play it governs, and locking it on every move would serialize the
whole game's traffic behind one row for no race anybody has.

But `GameService.createOrUpdate` must take `lockForUpdate` on the game
it is about to rewrite — or a read of a contended table (`match`,
`participant`, `tournament`, `entrant`, `result`) merely because the
write lands on a different one. This exception is about the asymmetry:
slow read, fast write. Where an existence check has to outlive the
insert that relies on it, `FOR SHARE` is the middle course —
`GameRepo.lockForShare` and `PlayerRepo.readForShare` exist for it,
and `CharacterService.create` is the worked example. It blocks a
concurrent delete of the row without making two inserts against it
queue behind each other.

Say so at the read. An unlocked `SELECT` inside a transaction that writes looks like the mistake
this rule is about, so the exception is written down where it is being taken — the comments on
`requireGame` in `TournamentService` and `TournamentRounds` are that note.

### The exception: a call to an external service

A transaction is never held open across a request to something outside the database — the game
engine above all. A remote call can take as long as it likes, and a transaction waiting on one
holds its locks and a pooled connection for exactly that long.

So a call that writes both before and after an engine request is two transactions with a gap in
the middle, and **nothing read before the gap may be assumed still true after it**. Start a fresh
transaction for the second half and re-read, `FOR UPDATE`, anything the writes there depend on —
including the row the first half wrote, whose state somebody else may have changed while the
engine was answering. `TournamentRounds.createMatch` is the worked example: transaction A claims the
due match under its tournament's and its own locks, the engine creates its game
(`GameEngineService.launchTournamentMatch`), and what follows writes only the urls the engine answered
with, since anything else on the row may have changed meanwhile.

The other half of that gap is that the second half cannot be undone by rolling back the first —
once the engine has made its game, the only way out is forward. Repairing rather than unwinding
(`refresh`) is the pattern for that.
