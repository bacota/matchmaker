---
paths:
  - "matchmaker/ui/**"
---

# UI

## A response can outlive the session that asked for it

A fetch made while one player is signed in can be answered after they have signed out, or after
their token expired — `sessionExpired()` has already cleared everything by then, and the late
answer writes one player's data into a store the next player is about to read.

So `Store` counts sign-ins. Anything that holds onto an answer takes `currentSignIn` before the
request and checks `isStillSignedInAs` before committing; `Store.load` and `Store.reload` do it for
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
