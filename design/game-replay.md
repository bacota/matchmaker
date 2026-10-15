# Game replay

Replay was built for Stratego (`engines/stratego`: `MatchState.opening`, `Engine.replayOf`, `Html.replayScript`).
These decisions apply to any engine that gets a replay.

## Storage
- Every move is already stored. Hidden starting information goes into the move that creates it, as an optional field with a `None` default. Stratego keeps each side's setup in its deployment move: `MoveRecord.setup: Option[List[Rank]] = None`.
- upickle omits default fields, so old stored match JSON stays valid; pin the shape in `StoredMatchSpec`.
- Matches recorded before the field existed get their opening rebuilt from the moves, using what battles revealed and the pieces still standing.

## Who sees what
- A finished match hides exactly what it hid during play. A player sees their own pieces; the public sees only what was revealed in play.
- Hidden information is never sent to the browser, in play or in replay.
- The replay's opening is masked by what is *still unseen now*, not by what was unseen at each step. So a rank revealed at move 30 is visible from move 0. The page's marks show what was known at the step on screen.

## Marks
- An underline means "the enemy has seen this piece". A dot means "it has moved but has not been revealed" (so it is not immovable).
- Both marks apply to *either* side, in play and in replay alike. Otherwise a mark changes when you step to the latest move, which is the live view.
- Screen-reader labels say the same things: "revealed", "not yet revealed", "has moved".

## Computing a frame
- The page computes each position from `opening` plus `moves`.
- That code lives in its own script (`replayFrame`), so a Node test can replay random games and compare every frame with the engine's own boards (`ReplaySpec`).

## Controls
- The bar reads ◀◀ ◀ "Move N of M" ▶ ▶▶, with 44px buttons.
- Buttons use `aria-disabled`, not `disabled`, so focus stays on a button at either end.
- The left and right arrow keys step through the replay anywhere on the page, except in selects, inputs, dialogs and popovers, and on a board square focused from the keyboard (there the arrows move between squares).
- Home and End jump to the ends only when focus is on the board or the replay bar, because elsewhere they scroll the page.
- While replaying:
  - the board is ringed and cannot be played on
  - the news live region announces "Move N: …"
  - the other player's new moves are appended to the end
- The matchmaker UI link for a finished match reads "Review game".

## Names on the page
- Renaming a piece only changes the page's display map (`NAME` in Stratego's `Html.scala`). The engine's names stay, because the API and stored JSON use them.

