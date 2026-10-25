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

## Explanations are tips, not paragraphs

On a form or other functional page, the text that explains a field or section goes in an ⓘ tip, not a visible
paragraph: `withTip` or `tipField` in `Views.scala`, and `refreshableSection(..., tip = Some(id -> text))` for a
refreshable section's heading. Status messages, live regions and empty-state text stay visible.

A tip's text lives in `matchmaker/ui/tips.properties`, not in the code: the build generates `Tips` from it, so
`game.roles` there is `Tips.game.roles`, and a `{name}` in a tip makes it a method taking `name`. A new tip adds a key
there. A key the code asks for and the file lacks, or leaves empty, is `""` rather than a build failure, and the tip
helpers show no ⓘ for it -- so a misspelt key builds, and only shows as a missing tip.

## Fetches go through the store

The fetch loop (a fetch on mount inside a view that re-renders when the store changes) has happened here: expanding a
game on the player page once sent about sixteen `GET /games/{id}/challenges` a second. The guards against it:

- `Store.loading`, `known` and `failed` derive from the one `outcomes` map that every fetch updates, through
  `Outcomes.of`, which applies `.distinct`. A new signal derived from a shared `Var` does the same.
- Every `Store` fetch goes through `Coalescer`: one in flight per list, a trailing request for asks made meanwhile,
  starts spaced at least a second apart. A new fetch uses `load` or `reload` to get it, not a bare `ApiClient` call.
- Prefer fetching on navigation (`Store.show`) to fetching on mount.

## Captions sit beside their fields

Wherever the screen is wide enough, a field's caption and its control share one line. `label.field` does this for
every form from 48rem up (the rule in `app.css`) and stacks below that. Use `Views.field` or `Views.tipField` for a
captioned control so it gets this, rather than a layout of its own.

A caption's text lives in `matchmaker/ui/labels.properties`, generated into `Labels` the same way as tips: `game.name`
there is `Labels.game.name`. A field with a tip uses the same key in both files. A new field adds a key there. A
caption that comes from data, such as a game parameter's display name, stays in the code.

A competition format can reword the competition form's captions and tips while it is the one chosen, or leave them
out: `StandardModel.formOverrides`, keyed `labels.<key>` or `tips.<key>`, reaches the form on the stored model, and a
caption's `None` leaves out its field, a tip's `None` the tip. So a caption or tip on that form goes through the form's
`labelOf` or `tipOf` with its key, not straight to `Labels` or `Tips`, and a field's setting goes through
`unlessLeftOut`, which saves what a new competition starts with for a field left out. A format's own special case on
the form is written this way, not as a test of its id: Round Robin leaves out the most players and starting when full.
`FormOverridesSpec` fails a format whose override names a key neither file has, or leaves out a field that is always
shown.
