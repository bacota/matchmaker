---
paths:
  - "engines/**"
  - "terraform/modules/engine/**"
---

# Engines

Some names in the engines are fixed by things outside the Scala code, and renaming one breaks something
the compiler cannot see.

- Each engine's `Handler` class name must not change. Terraform names it as the Lambda handler,
  `com.vivi.<game>.Handler::handleRequest` (`terraform/modules/engine/variables.tf`).
- `engines.common` must not depend on `matchmaker`. The wire types are restated in `common` rather than
  shared, and `ProtocolSpec` compares the two statements. Only tests may depend on `matchmaker`.
- The `SignIn` storage prefixes (`rps.`, `tictactoe.`, `boxing.`, …) must not change. They name each
  engine's tokens in the browser's storage, so renaming one signs every player of that game out.

## Polled state is revalidated

The play pages poll their state route every two seconds. `EngineRoutes` answers the state routes through
`revalidated` — an ETag of the body, `private, no-cache` and `Vary: Authorization`, so an unchanged state is a 304. A
new engine whose page polls a different route uses it too. DevTools and Playwright report a 304 as 200; confirm one
with `curl -H 'If-None-Match: …'`. A page's render never triggers a fetch.

## Replays

Before giving an engine a replay, read `design/game-replay.md`: the decisions made for Stratego's apply to every
engine's.
