---
paths:
  - "matchmaker/ui/**"
  - "engines/*/src/**/Html.scala"
  - "engines/common/src/**/SignIn.scala"
  - "engines/common/src/**/PlayLive.scala"
  - "engines/common/src/**/Finale.scala"
  - "engines/common/src/**/TurnTimer.scala"
---

## UI work is mobile and accessible by default

Not a separate pass to be asked for: 16px form fields (smaller ones make iOS zoom on focus),
44px touch targets, a real label per control, live regions for anything that updates without a
reload, and visible focus. Assume every change will be used on a phone.

For the code touched, check and fix:

- Fields at 16px (`1rem`), each with a real visible label (a wrapping `<label>`). `aria-label` only
  where a caption genuinely cannot fit, and never one that contradicts a visible caption.
- Tap targets about 44px under `@media (pointer: coarse)`. Layout works at 375px with no page-wide
  horizontal scroll; tables scroll in `.table-scroll` or become cards.
- A busy button uses `aria-disabled` and a guard in its handler, not `disabled`, which drops focus.
  `disabled` is only for "not usable yet".
- Focus never falls to `<body>`: when the focused element is removed — a screen change, a rebuilt
  row, a closed dialog or panel, a replaced form step — move it somewhere sensible.
- State is announced as well as styled: `aria-current`, `aria-expanded`, live regions and
  `role="alert"` mounted *before* their text, `aria-invalid` on a field the form says is wrong.
- Meaning carried by a glyph, emoji or icon is also in `.sr-only` text — not an `aria-label` on a
  plain span, which screen readers ignore. `:focus-visible` outlines show, including on
  programmatic focus targets.
- Tips go through `withTip` (a button that works on hover, focus and tap), never hover-only or
  `title`-only.

Check in a headless browser at 375px with touch, and at desktop width, where practical, and say
plainly when a change was only read rather than exercised.

`e2e/a11y/check.mjs` does the mechanical part on every screen: axe-core's WCAG 2.2 AA rules, plus
horizontal scroll, 16px fields and 44px targets on the phone. Its header says how to run it. The
`ui-reviewer` agent (`.claude/agents/ui-reviewer.md`) reviews what the check cannot judge — focus,
keyboard use, announcements, whether labels make sense — when asked for it.

The a11y check is run only by the `ui-reviewer`. When implementing a UI change, don't run `check.mjs`
or any other a11y check; leave it to the reviewer, and say in the report that it has not been run.
